package app.olauncher.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.result.contract.ActivityResultContract
import java.io.File

/**
 * Asks the launcher's own explorer for files (see [FilePickerActivity]) in place of the system's
 * picker; the files come back as file:// addresses, which this app reads like any other.
 */
class PickFiles : ActivityResultContract<PickFiles.Request, List<Uri>>() {

    /**
     * What can be picked: [types] are MIME types ("audio/…" with a star takes every audio file),
     * [extensions] file endings without the dot; a file that matches either is offered.
     */
    class Request(val types: Array<String> = emptyArray(), val extensions: Array<String> = emptyArray(), val multiple: Boolean = false)

    override fun createIntent(context: Context, input: Request): Intent = Intent(context, FilePickerActivity::class.java)
        .putExtra(FileExplorerActivity.EXTRA_PICK_TYPES, input.types)
        .putExtra(FileExplorerActivity.EXTRA_PICK_EXTENSIONS, input.extensions)
        .putExtra(FileExplorerActivity.EXTRA_PICK_MULTIPLE, input.multiple)

    override fun parseResult(resultCode: Int, intent: Intent?): List<Uri> =
        intent?.getStringArrayListExtra(FileExplorerActivity.EXTRA_PICKED_PATHS).orEmpty().map { Uri.fromFile(File(it)) }
}
