package app.olauncher.ui.recorder

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import app.olauncher.R
import app.olauncher.helper.showToast

/**
 * "Share" of audio files from another app to the recorder: they are copied among the
 * recordings (dated today), ready to be transcribed, and the recorder opens.
 */
class RecorderShareActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val uris = mutableListOf<Uri>()
        @Suppress("DEPRECATION")
        when (intent?.action) {
            Intent.ACTION_SEND -> (intent.getParcelableExtra(Intent.EXTRA_STREAM) as? Uri)?.let { uris += it }
            Intent.ACTION_SEND_MULTIPLE -> intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM)?.let { uris += it }
        }
        val allowed = Build.VERSION.SDK_INT < Build.VERSION_CODES.R || Environment.isExternalStorageManager()
        if (uris.isEmpty() || !allowed) {
            // Without the files access the recorder asks for it itself
            if (uris.isNotEmpty()) showToast(getString(R.string.ml_rec_allow_files))
            open()
            return
        }
        val target = RecorderStore.root(this)
        Thread {
            val failed = uris.count { RecorderStore.importAudio(this, it, target) == null }
            runOnUiThread {
                if (failed > 0) showToast(getString(R.string.ml_rec_import_failed, failed))
                RecorderJobs.changed()
                open()
            }
        }.start()
    }

    private fun open() {
        startActivity(Intent(this, RecorderActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        finish()
    }
}
