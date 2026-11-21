package app.olauncher.ui.youtube

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import kotlin.math.min

/**
 * Reads a file of YouTube a few megabytes at a time, each piece with its own request: a file
 * asked from a point "to the end" is served at a crawl, or not at all. The length comes from
 * the address itself (clen); an address without it is read the plain way.
 */
@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
class YtChunkedDataSource(private val upstream: DataSource) : DataSource {

    private var spec: DataSpec? = null
    private var position = 0L
    private var end = 0L
    private var chunked = false
    private var open = false
    private var readInChunk = 0L

    override fun addTransferListener(transferListener: TransferListener) = upstream.addTransferListener(transferListener)

    override fun open(dataSpec: DataSpec): Long {
        val total = runCatching { dataSpec.uri.getQueryParameter("clen")?.toLong() }.getOrNull()
        if (total == null || total <= 0 || dataSpec.position >= total) {
            chunked = false
            return upstream.open(dataSpec)
        }
        chunked = true
        spec = dataSpec
        position = dataSpec.position
        end = if (dataSpec.length != C.LENGTH_UNSET.toLong()) min(total, dataSpec.position + dataSpec.length) else total
        openChunk()
        return end - dataSpec.position
    }

    private fun openChunk() {
        val whole = spec ?: return
        val length = min(CHUNK, end - position)
        upstream.open(whole.buildUpon().setPosition(position).setLength(length).build())
        open = true
        readInChunk = 0
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (!chunked) return upstream.read(buffer, offset, length)
        if (length == 0) return 0
        if (position >= end) return C.RESULT_END_OF_INPUT
        if (!open) openChunk()
        val read = upstream.read(buffer, offset, min(length.toLong(), end - position).toInt())
        if (read == C.RESULT_END_OF_INPUT) {
            // This piece is over: the next read opens the one after it
            upstream.close()
            open = false
            // A piece that came empty would be asked for ever
            return if (readInChunk == 0L) C.RESULT_END_OF_INPUT else read(buffer, offset, length)
        }
        readInChunk += read
        position += read
        return read
    }

    override fun getUri(): Uri? = if (chunked) spec?.uri else upstream.uri

    override fun getResponseHeaders(): Map<String, List<String>> = upstream.responseHeaders

    override fun close() {
        open = false
        upstream.close()
    }

    private companion object {
        const val CHUNK = 4L * 1024 * 1024
    }
}
