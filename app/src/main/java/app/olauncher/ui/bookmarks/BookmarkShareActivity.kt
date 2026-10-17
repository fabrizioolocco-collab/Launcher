package app.olauncher.ui.bookmarks

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import app.olauncher.data.Bookmark
import app.olauncher.data.BookmarkStore

/**
 * Target of a browser's Share: shows "Add to bookmarks" over the page (transparent, the page
 * blurred behind), with the name and address already filled in.
 */
class BookmarkShareActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState != null) return
        val text = intent?.getStringExtra(Intent.EXTRA_TEXT)
        val url = BookmarkStore.findUrl(text) ?: intent?.dataString?.takeIf { it.startsWith("http") }.orEmpty()
        // Browsers send the page title as the subject; some put it before the address in the text
        val title = intent?.getStringExtra(Intent.EXTRA_SUBJECT)?.trim().orEmpty().ifEmpty {
            text?.replace(url, "")?.trim()?.trim('-', '–', '|', ':')?.trim().orEmpty()
        }
        BookmarksUi.showForm(this, Bookmark(0L, title, url), onClosed = { finish() }) {}
    }

    override fun finish() {
        super.finish()
        @Suppress("DEPRECATION")
        overridePendingTransition(0, 0)
    }
}
