package app.olauncher.ui.media

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import app.olauncher.R
import app.olauncher.helper.showToast
import java.io.File

/** Video editing (trim, crop): coming in the next version. */
class VideoEditActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        showToast(getString(R.string.ml_edit_soon))
        finish()
    }

    companion object {
        const val EXTRA_PATH = "path"
        fun open(context: Context, file: File) = context.startActivity(
            Intent(context, VideoEditActivity::class.java).putExtra(EXTRA_PATH, file.absolutePath).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }
}
