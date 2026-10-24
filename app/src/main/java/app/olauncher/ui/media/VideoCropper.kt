package app.olauncher.ui.media

import android.graphics.SurfaceTexture
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.media.MediaMuxer
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.view.Surface
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import kotlin.math.max

/**
 * Re-encodes a video keeping only a rectangle of the frame (and optionally a time range).
 * Pipeline: decoder → SurfaceTexture → OpenGL quad sampling the crop → encoder input surface →
 * muxer. Audio samples are copied as they are. [crop] is in source pixels, before rotation is
 * applied for display (the rotation flag is carried over, so the result shows the same way).
 */
class VideoCropper(private val src: File, private val dst: File) {

    class Crop(val left: Int, val top: Int, val width: Int, val height: Int)

    /** Leave the audio track out. */
    var mute = false

    /** Quarter turns (degrees, clockwise) added to the video's own orientation flag. */
    var extraRotation = 0

    /** Called with the progress (0..1) from the encoding thread. */
    var onProgress: ((Float) -> Unit)? = null

    fun run(crop: Crop, startUs: Long = 0, endUs: Long = Long.MAX_VALUE): Boolean {
        val extractor = MediaExtractor()
        val audioExtractor = MediaExtractor()
        var decoder: MediaCodec? = null
        var encoder: MediaCodec? = null
        var muxer: MediaMuxer? = null
        var gl: Gl? = null
        try {
            extractor.setDataSource(src.absolutePath)
            val videoTrack = (0 until extractor.trackCount).firstOrNull {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true
            } ?: return false
            val inFormat = extractor.getTrackFormat(videoTrack)
            extractor.selectTrack(videoTrack)
            val durationUs = inFormat.getLong(MediaFormat.KEY_DURATION)
            val rangeEnd = minOf(endUs, durationUs)

            // Output size: even numbers, capped to keep encoders happy
            val outW = (crop.width / 2) * 2
            val outH = (crop.height / 2) * 2
            val frameRate = if (inFormat.containsKey(MediaFormat.KEY_FRAME_RATE)) inFormat.getInteger(MediaFormat.KEY_FRAME_RATE) else 30
            val bitRate = max(2_000_000, (outW.toLong() * outH * frameRate * 0.12).toInt())
            val outFormat = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, outW, outH).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                setInteger(MediaFormat.KEY_BIT_RATE, bitRate)
                setInteger(MediaFormat.KEY_FRAME_RATE, frameRate)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
            }
            encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
            encoder.configure(outFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            val inputSurface = encoder.createInputSurface()
            encoder.start()

            gl = Gl(inputSurface, outW, outH)
            val srcW = inFormat.getInteger(MediaFormat.KEY_WIDTH)
            val srcH = inFormat.getInteger(MediaFormat.KEY_HEIGHT)
            gl.setCrop(crop, srcW, srcH)

            decoder = MediaCodec.createDecoderByType(inFormat.getString(MediaFormat.KEY_MIME)!!)
            // The decoder would rotate the frames on the surface by the track's rotation: keep them as
            // stored, the crop is in stored pixels and the rotation is carried over by the muxer
            if (inFormat.containsKey(MediaFormat.KEY_ROTATION)) inFormat.setInteger(MediaFormat.KEY_ROTATION, 0)
            decoder.configure(inFormat, gl.decoderSurface, null, 0)
            decoder.start()

            muxer = MediaMuxer(dst.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            var own = 0
            try {
                val r = MediaMetadataRetriever()
                r.setDataSource(src.absolutePath)
                own = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
                r.release()
            } catch (e: Exception) {
                // no rotation info
            }
            muxer.setOrientationHint(((own + extraRotation) % 360 + 360) % 360)

            // Audio track copied as is
            audioExtractor.setDataSource(src.absolutePath)
            val audioTrack = if (mute) null else (0 until audioExtractor.trackCount).firstOrNull {
                audioExtractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            }
            var audioOut = -1
            if (audioTrack != null) {
                audioExtractor.selectTrack(audioTrack)
                audioOut = muxer.addTrack(audioExtractor.getTrackFormat(audioTrack))
            }

            extractor.seekTo(startUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
            val info = MediaCodec.BufferInfo()
            var videoOut = -1
            var muxerStarted = false
            var inputDone = false
            var decoderDone = false
            var encoderDone = false
            val timeBase = max(0L, extractor.sampleTime)
            var frames = 0L

            while (!encoderDone) {
                // Feed the decoder
                if (!inputDone) {
                    val index = decoder.dequeueInputBuffer(10_000)
                    if (index >= 0) {
                        val buf = decoder.getInputBuffer(index)!!
                        val size = extractor.readSampleData(buf, 0)
                        val time = extractor.sampleTime
                        if (size < 0 || time > rangeEnd) {
                            decoder.queueInputBuffer(index, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            decoder.queueInputBuffer(index, 0, size, time, 0)
                            extractor.advance()
                        }
                    }
                }
                // Drain the decoder onto the GL surface, render into the encoder
                if (!decoderDone) {
                    val index = decoder.dequeueOutputBuffer(info, 10_000)
                    if (index >= 0) {
                        val eos = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                        val render = info.size > 0 && info.presentationTimeUs >= startUs
                        decoder.releaseOutputBuffer(index, render)
                        if (render) {
                            gl.awaitFrame()
                            gl.draw()
                            gl.setPresentationTime((info.presentationTimeUs - timeBase) * 1000)
                            gl.swap()
                            frames++
                            onProgress?.invoke(((info.presentationTimeUs - startUs).toFloat() / (rangeEnd - startUs)).coerceIn(0f, 1f))
                        }
                        if (eos) {
                            decoderDone = true
                            encoder.signalEndOfInputStream()
                        }
                    }
                }
                // Drain the encoder into the muxer
                while (true) {
                    val index = encoder.dequeueOutputBuffer(info, 0)
                    if (index == MediaCodec.INFO_TRY_AGAIN_LATER) break
                    if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        videoOut = muxer.addTrack(encoder.outputFormat)
                        muxer.start()
                        muxerStarted = true
                        continue
                    }
                    if (index < 0) continue
                    val buf = encoder.getOutputBuffer(index)!!
                    if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) info.size = 0
                    if (info.size > 0 && muxerStarted) muxer.writeSampleData(videoOut, buf, info)
                    val eos = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                    encoder.releaseOutputBuffer(index, false)
                    if (eos) {
                        encoderDone = true
                        break
                    }
                }
            }
            if (frames == 0L || !muxerStarted) return false

            if (audioTrack != null) {
                audioExtractor.seekTo(startUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
                val buf = ByteBuffer.allocate(1 shl 20)
                val ainfo = MediaCodec.BufferInfo()
                val abase = max(0L, audioExtractor.sampleTime)
                while (true) {
                    ainfo.offset = 0
                    ainfo.size = audioExtractor.readSampleData(buf, 0)
                    if (ainfo.size < 0) break
                    val t = audioExtractor.sampleTime
                    if (t > rangeEnd) break
                    ainfo.presentationTimeUs = t - abase
                    ainfo.flags = if (audioExtractor.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0
                    muxer.writeSampleData(audioOut, buf, ainfo)
                    audioExtractor.advance()
                }
            }
            muxer.stop()
            return true
        } catch (e: Throwable) {
            e.printStackTrace()
            return false
        } finally {
            try { decoder?.stop(); decoder?.release() } catch (e: Exception) { }
            try { encoder?.stop(); encoder?.release() } catch (e: Exception) { }
            try { muxer?.release() } catch (e: Exception) { }
            gl?.release()
            extractor.release()
            audioExtractor.release()
        }
    }

    /** EGL context on the encoder surface; the decoder draws into an external texture that is cropped onto it. */
    private class Gl(surface: Surface, private val width: Int, private val height: Int) : SurfaceTexture.OnFrameAvailableListener {
        private var display: EGLDisplay = EGL14.EGL_NO_DISPLAY
        private var context: EGLContext = EGL14.EGL_NO_CONTEXT
        private var eglSurface: EGLSurface = EGL14.EGL_NO_SURFACE
        private val textureId: Int
        private val surfaceTexture: SurfaceTexture
        val decoderSurface: Surface
        private val program: Int
        private val lock = Object()
        private var frameAvailable = false
        private val vertices: FloatBuffer
        private var texCoords: FloatBuffer
        private val transform = FloatArray(16)

        init {
            display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
            val version = IntArray(2)
            EGL14.eglInitialize(display, version, 0, version, 1)
            val attribs = intArrayOf(
                EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8,
                EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT, 0x3142 /* EGL_RECORDABLE_ANDROID */, 1, EGL14.EGL_NONE,
            )
            val configs = arrayOfNulls<EGLConfig>(1)
            val num = IntArray(1)
            EGL14.eglChooseConfig(display, attribs, 0, configs, 0, 1, num, 0)
            context = EGL14.eglCreateContext(display, configs[0], EGL14.EGL_NO_CONTEXT, intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE), 0)
            eglSurface = EGL14.eglCreateWindowSurface(display, configs[0], surface, intArrayOf(EGL14.EGL_NONE), 0)
            EGL14.eglMakeCurrent(display, eglSurface, eglSurface, context)

            val tex = IntArray(1)
            GLES20.glGenTextures(1, tex, 0)
            textureId = tex[0]
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
            surfaceTexture = SurfaceTexture(textureId).also { it.setOnFrameAvailableListener(this) }
            decoderSurface = Surface(surfaceTexture)

            program = GLES20.glCreateProgram()
            GLES20.glAttachShader(program, shader(GLES20.GL_VERTEX_SHADER, VERTEX))
            GLES20.glAttachShader(program, shader(GLES20.GL_FRAGMENT_SHADER, FRAGMENT))
            GLES20.glLinkProgram(program)
            vertices = floats(floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f))
            texCoords = floats(floatArrayOf(0f, 0f, 1f, 0f, 0f, 1f, 1f, 1f))
        }

        private fun shader(type: Int, src: String): Int = GLES20.glCreateShader(type).also {
            GLES20.glShaderSource(it, src)
            GLES20.glCompileShader(it)
        }

        private fun floats(a: FloatArray): FloatBuffer =
            ByteBuffer.allocateDirect(a.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().also { it.put(a).position(0) }

        /**
         * Texture coordinates of the crop rectangle (source pixels, top-left origin). Texture v grows
         * upwards, so the bottom vertices take the crop's bottom edge.
         */
        fun setCrop(crop: Crop, srcW: Int, srcH: Int) {
            val l = crop.left.toFloat() / srcW
            val r = (crop.left + crop.width).toFloat() / srcW
            val top = 1f - crop.top.toFloat() / srcH
            val bottom = 1f - (crop.top + crop.height).toFloat() / srcH
            texCoords = floats(floatArrayOf(l, bottom, r, bottom, l, top, r, top))
        }

        override fun onFrameAvailable(st: SurfaceTexture) {
            synchronized(lock) {
                frameAvailable = true
                lock.notifyAll()
            }
        }

        fun awaitFrame() {
            synchronized(lock) {
                val deadline = System.currentTimeMillis() + 2500
                while (!frameAvailable && System.currentTimeMillis() < deadline) lock.wait(100)
                frameAvailable = false
            }
            surfaceTexture.updateTexImage()
            surfaceTexture.getTransformMatrix(transform)
        }

        fun draw() {
            GLES20.glViewport(0, 0, width, height)
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
            GLES20.glUseProgram(program)
            val pos = GLES20.glGetAttribLocation(program, "aPosition")
            val tc = GLES20.glGetAttribLocation(program, "aTexCoord")
            GLES20.glEnableVertexAttribArray(pos)
            GLES20.glVertexAttribPointer(pos, 2, GLES20.GL_FLOAT, false, 0, vertices)
            GLES20.glEnableVertexAttribArray(tc)
            GLES20.glVertexAttribPointer(tc, 2, GLES20.GL_FLOAT, false, 0, texCoords)
            GLES20.glUniformMatrix4fv(GLES20.glGetUniformLocation(program, "uTransform"), 1, false, transform, 0)
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId)
            GLES20.glUniform1i(GLES20.glGetUniformLocation(program, "uTexture"), 0)
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        }

        fun setPresentationTime(ns: Long) {
            android.opengl.EGLExt.eglPresentationTimeANDROID(display, eglSurface, ns)
        }

        fun swap() {
            EGL14.eglSwapBuffers(display, eglSurface)
        }

        fun release() {
            decoderSurface.release()
            surfaceTexture.release()
            if (display != EGL14.EGL_NO_DISPLAY) {
                EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
                EGL14.eglDestroySurface(display, eglSurface)
                EGL14.eglDestroyContext(display, context)
                EGL14.eglTerminate(display)
            }
        }

        companion object {
            private const val VERTEX = """
                attribute vec4 aPosition;
                attribute vec2 aTexCoord;
                uniform mat4 uTransform;
                varying vec2 vTexCoord;
                void main() {
                    gl_Position = aPosition;
                    vTexCoord = (uTransform * vec4(aTexCoord, 0.0, 1.0)).xy;
                }
            """
            private const val FRAGMENT = """
                #extension GL_OES_EGL_image_external : require
                precision mediump float;
                varying vec2 vTexCoord;
                uniform samplerExternalOES uTexture;
                void main() {
                    gl_FragColor = texture2D(uTexture, vTexCoord);
                }
            """
        }
    }
}
