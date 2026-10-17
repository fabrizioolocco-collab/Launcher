package app.olauncher.ui.bookmarks

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.text.Editable
import android.text.InputType
import android.text.TextUtils
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import app.olauncher.R
import app.olauncher.data.Bookmark
import app.olauncher.data.BookmarkFolder
import app.olauncher.data.BookmarkStore
import app.olauncher.helper.MenuEntry
import app.olauncher.helper.OlDialog
import app.olauncher.helper.dpToPx
import app.olauncher.helper.getColorFromAttr
import app.olauncher.helper.hideKeyboard
import app.olauncher.helper.pillButton
import app.olauncher.helper.showConfirmDialog
import app.olauncher.helper.showMenuDialog
import app.olauncher.helper.showTextInputDialog
import app.olauncher.helper.showToast
import app.olauncher.ui.UiKit

/**
 * The bookmarks: a panel that opens above the home search bar (the screen blurred behind), with
 * the categories as a tree; the form to add or edit one (also reached from a browser's Share).
 */
object BookmarksUi {

    // ---------------------------------------------------------------- panel

    /** Opens the panel with its bottom [bottomOffsetPx] above the screen's bottom edge. */
    fun showPanel(context: Context, bottomOffsetPx: Int) {
        val dialog = OlDialog(context)
        dialog.blurRadius = 22.dpToPx()
        val ctx = dialog.context
        val maxHeight = (ctx.resources.displayMetrics.heightPixels - bottomOffsetPx - 96.dpToPx()).coerceAtLeast(240.dpToPx())
        val card = object : LinearLayout(ctx) {
            override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
                super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(maxHeight, MeasureSpec.AT_MOST))
            }
        }.apply {
            orientation = LinearLayout.VERTICAL
            background = UiKit.cardBackground(ctx, 24)
            setPadding(0, 14.dpToPx(), 0, 8.dpToPx())
        }
        val list = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        val search = EditText(ctx)
        lateinit var render: () -> Unit

        // Title and "+"
        card.addView(LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(18.dpToPx(), 0, 10.dpToPx(), 6.dpToPx())
            addView(TextView(ctx).apply {
                setText(R.string.ml_bm_title)
                textSize = 18f
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(UiKit.primary(ctx))
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(UiKit.iconButton(ctx, R.drawable.ic_m_add, ctx.getString(R.string.ml_bm_new)) {
                ctx.showMenuDialog(ctx.getString(R.string.ml_bm_new), listOf(
                    MenuEntry(ctx.getString(R.string.ml_bm_new_bookmark), icon = R.drawable.ic_m_star) {
                        showForm(ctx, fromClipboard(ctx)) { render() }
                    },
                    MenuEntry(ctx.getString(R.string.ml_bm_new_folder), icon = R.drawable.ic_m_folder) {
                        newFolder(ctx, 0L) { render() }
                    },
                ))
            })
        })
        // Search among the bookmarks
        search.apply {
            hint = ctx.getString(R.string.ml_bm_search)
            setSingleLine(true)
            textSize = 14f
            UiKit.styleInput(this)
            setCompoundDrawablesRelativeWithIntrinsicBounds(R.drawable.ic_m_search_small, 0, 0, 0)
            compoundDrawablePadding = 8.dpToPx()
            compoundDrawableTintList = android.content.res.ColorStateList.valueOf(UiKit.secondary(ctx))
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
                override fun afterTextChanged(s: Editable?) = render()
            })
        }
        card.addView(search, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 42.dpToPx()).apply {
            marginStart = 12.dpToPx(); marginEnd = 12.dpToPx(); bottomMargin = 6.dpToPx()
        })
        card.addView(ScrollView(ctx).apply {
            isVerticalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            addView(list)
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        render = {
            list.removeAllViews()
            val query = search.text.toString().trim()
            if (query.isNotEmpty()) renderSearch(ctx, list, query, dialog) { render() }
            else renderTree(ctx, list, dialog) { render() }
        }
        render()

        val root = FrameLayout(ctx).apply {
            setPadding(16.dpToPx(), 0, 16.dpToPx(), 0)
            addView(card, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM))
        }
        dialog.setView(root)
        dialog.showRespectingStatusBar()
        dialog.window?.apply {
            setBackgroundDrawable(ColorDrawable(0))
            setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            setGravity(Gravity.BOTTOM)
            attributes = attributes.apply { y = bottomOffsetPx }
            setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE or WindowManager.LayoutParams.SOFT_INPUT_STATE_HIDDEN)
        }
    }

    private fun renderTree(ctx: Context, list: LinearLayout, dialog: OlDialog, refresh: () -> Unit) {
        val folders = BookmarkStore.folders(ctx)
        val bookmarks = BookmarkStore.bookmarks(ctx)
        if (folders.isEmpty() && bookmarks.isEmpty()) {
            list.addView(TextView(ctx).apply {
                setText(R.string.ml_bm_empty)
                textSize = 13.5f
                setTextColor(UiKit.secondary(ctx))
                setPadding(18.dpToPx(), 16.dpToPx(), 18.dpToPx(), 18.dpToPx())
            })
            return
        }
        val open = BookmarkStore.openFolders(ctx)
        fun count(folder: Long): Int {
            val ids = BookmarkStore.subtree(ctx, folder)
            return bookmarks.count { it.folder in ids }
        }
        fun walk(parent: Long, depth: Int) {
            if (depth > 30) return
            folders.filter { it.parent == parent }.forEach { f ->
                val isOpen = f.id in open
                list.addView(folderRow(ctx, f, depth, isOpen, count(f.id), {
                    BookmarkStore.setFolderOpen(ctx, f.id, !isOpen)
                    refresh()
                }, { folderMenu(ctx, f, refresh) }))
                if (isOpen) walk(f.id, depth + 1)
            }
            bookmarks.filter { it.folder == parent }.forEach { b ->
                list.addView(bookmarkRow(ctx, b, depth, "", dialog, refresh))
            }
        }
        walk(0L, 0)
    }

    private fun renderSearch(ctx: Context, list: LinearLayout, query: String, dialog: OlDialog, refresh: () -> Unit) {
        val q = query.lowercase()
        val hits = BookmarkStore.bookmarks(ctx).filter { it.title.lowercase().contains(q) || it.url.lowercase().contains(q) }
        if (hits.isEmpty()) {
            list.addView(TextView(ctx).apply {
                setText(R.string.ml_bm_no_results)
                textSize = 13.5f
                setTextColor(UiKit.secondary(ctx))
                setPadding(18.dpToPx(), 12.dpToPx(), 18.dpToPx(), 12.dpToPx())
            })
            return
        }
        hits.forEach { b ->
            list.addView(bookmarkRow(ctx, b, 0, BookmarkStore.path(ctx, b.folder), dialog, refresh))
        }
    }

    private fun rowFrame(ctx: Context, depth: Int) = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        minimumHeight = 46.dpToPx()
        setPadding((12 + 20 * depth).dpToPx(), 3.dpToPx(), 14.dpToPx(), 3.dpToPx())
        foreground = UiKit.ripple(ctx)
        isClickable = true
        isLongClickable = true
    }

    private fun folderRow(ctx: Context, f: BookmarkFolder, depth: Int, open: Boolean, count: Int, onTap: () -> Unit, onMenu: () -> Unit) =
        rowFrame(ctx, depth).apply {
            addView(UiKit.icon(ctx, R.drawable.ic_m_folder, UiKit.primary(ctx), 20).apply {
                (layoutParams as LinearLayout.LayoutParams).apply { marginStart = 5.dpToPx(); marginEnd = 15.dpToPx() }
            })
            addView(TextView(ctx).apply {
                text = f.name
                textSize = 15f
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(UiKit.primary(ctx))
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(TextView(ctx).apply {
                text = count.toString()
                textSize = 12f
                setTextColor(UiKit.secondary(ctx))
                setPadding(8.dpToPx(), 0, 6.dpToPx(), 0)
            })
            addView(UiKit.icon(ctx, if (open) R.drawable.ic_m_chevron_down else R.drawable.ic_m_chevron_right, UiKit.secondary(ctx), 16))
            contentDescription = f.name
            setOnClickListener { onTap() }
            setOnLongClickListener { onMenu(); true }
        }

    /** The bookmark's letter on a small tile (no network: no favicons). */
    private fun letterTile(ctx: Context, b: Bookmark) = TextView(ctx).apply {
        text = (b.title.trim().firstOrNull { it.isLetterOrDigit() } ?: BookmarkStore.domain(b.url).firstOrNull() ?: '•').uppercaseChar().toString()
        textSize = 13f
        setTypeface(typeface, Typeface.BOLD)
        gravity = Gravity.CENTER
        setTextColor(UiKit.primary(ctx))
        alpha = 0.9f
        background = GradientDrawable().apply {
            cornerRadius = 9.dpToPx().toFloat()
            setColor(ctx.getColorFromAttr(R.attr.tileColor))
            setStroke(1.dpToPx(), UiKit.line(ctx))
        }
    }

    private fun bookmarkRow(ctx: Context, b: Bookmark, depth: Int, subtitle: String, dialog: OlDialog, refresh: () -> Unit) =
        rowFrame(ctx, depth).apply {
            addView(letterTile(ctx, b), LinearLayout.LayoutParams(30.dpToPx(), 30.dpToPx()).apply { marginEnd = 10.dpToPx() })
            addView(LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                addView(TextView(ctx).apply {
                    text = b.title.ifBlank { BookmarkStore.domain(b.url) }
                    textSize = 14.5f
                    setTextColor(UiKit.primary(ctx))
                    maxLines = 1
                    ellipsize = TextUtils.TruncateAt.END
                })
                if (subtitle.isNotEmpty()) addView(TextView(ctx).apply {
                    text = subtitle
                    textSize = 11.5f
                    setTextColor(UiKit.secondary(ctx))
                    maxLines = 1
                    ellipsize = TextUtils.TruncateAt.END
                })
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            contentDescription = b.title
            setOnClickListener {
                dialog.currentFocus?.hideKeyboard()
                dialog.dismiss()
                open(ctx, b, b.browser)
            }
            setOnLongClickListener { bookmarkMenu(ctx, b, dialog, refresh); true }
        }

    // ---------------------------------------------------------------- menus

    private fun bookmarkMenu(ctx: Context, b: Bookmark, dialog: OlDialog, refresh: () -> Unit) {
        val siblings = BookmarkStore.bookmarksIn(ctx, b.folder)
        val index = siblings.indexOfFirst { it.id == b.id }
        val browsers = browsers(ctx)
        val defaultName = SamsungBrowser.installed(ctx)?.let { pkg -> browsers.firstOrNull { it.first == pkg }?.second }
            ?: ctx.getString(R.string.ml_bm_browser_default)
        val browserName = browsers.firstOrNull { it.first == b.browser }?.second ?: defaultName
        val entries = mutableListOf(
            MenuEntry(ctx.getString(R.string.ml_bm_edit), icon = R.drawable.ic_m_rename) { showForm(ctx, b) { refresh() } },
            MenuEntry(ctx.getString(R.string.ml_bm_move), icon = R.drawable.ic_m_move) {
                pickFolder(ctx, emptySet()) { folder ->
                    BookmarkStore.saveBookmark(ctx, b.copy(folder = folder))
                    refresh()
                }
            },
        )
        if (index > 0) entries += MenuEntry(ctx.getString(R.string.ml_move_up), icon = R.drawable.ic_m_up) { BookmarkStore.moveBookmark(ctx, b.id, -1); refresh() }
        if (index in 0 until siblings.lastIndex) entries += MenuEntry(ctx.getString(R.string.ml_move_down), icon = R.drawable.ic_m_down) { BookmarkStore.moveBookmark(ctx, b.id, 1); refresh() }
        entries += MenuEntry(ctx.getString(R.string.ml_bm_copy), icon = R.drawable.ic_m_copy) {
            (ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText(b.title, b.url))
            ctx.showToast(ctx.getString(R.string.ml_bm_copied))
        }
        if (browsers.size > 1) {
            entries += MenuEntry(ctx.getString(R.string.ml_bm_open_in), icon = R.drawable.ic_m_globe) {
                ctx.showMenuDialog(ctx.getString(R.string.ml_bm_open_in), browsers.map { (pkg, name) ->
                    MenuEntry(name) {
                        dialog.dismiss()
                        open(ctx, b, pkg)
                    }
                })
            }
            entries += MenuEntry(ctx.getString(R.string.ml_bm_browser), value = browserName, icon = R.drawable.ic_m_settings) {
                ctx.showMenuDialog(ctx.getString(R.string.ml_bm_browser), listOf("" to "${ctx.getString(R.string.ml_bm_browser_default)} ($defaultName)").plus(browsers).map { (pkg, name) ->
                    MenuEntry(name, checked = null) {
                        BookmarkStore.saveBookmark(ctx, b.copy(browser = pkg))
                        refresh()
                    }
                })
            }
        }
        entries += MenuEntry(ctx.getString(R.string.ml_bm_delete), icon = R.drawable.ic_m_delete, destructive = true) {
            ctx.showConfirmDialog(ctx.getString(R.string.ml_bm_delete), ctx.getString(R.string.ml_bm_delete_confirm, b.title), ctx.getString(R.string.ml_bm_delete)) {
                BookmarkStore.deleteBookmark(ctx, b.id)
                refresh()
            }
        }
        ctx.showMenuDialog(b.title.ifBlank { BookmarkStore.domain(b.url) }, entries)
    }

    private fun folderMenu(ctx: Context, f: BookmarkFolder, refresh: () -> Unit) {
        val siblings = BookmarkStore.childFolders(ctx, f.parent)
        val index = siblings.indexOfFirst { it.id == f.id }
        val entries = mutableListOf(
            MenuEntry(ctx.getString(R.string.ml_bm_new_here), icon = R.drawable.ic_m_star) {
                showForm(ctx, fromClipboard(ctx).copy(folder = f.id)) {
                    BookmarkStore.setFolderOpen(ctx, f.id, true)
                    refresh()
                }
            },
            MenuEntry(ctx.getString(R.string.ml_bm_new_sub), icon = R.drawable.ic_m_folder) {
                newFolder(ctx, f.id) {
                    BookmarkStore.setFolderOpen(ctx, f.id, true)
                    refresh()
                }
            },
            MenuEntry(ctx.getString(R.string.ml_bm_rename), icon = R.drawable.ic_m_rename) {
                ctx.showTextInputDialog(ctx.getString(R.string.ml_bm_rename), f.name, ctx.getString(R.string.ml_bm_folder_hint), ctx.getString(R.string.ml_save)) { name ->
                    BookmarkStore.saveFolder(ctx, f.copy(name = name))
                    refresh()
                }
            },
            MenuEntry(ctx.getString(R.string.ml_bm_move), icon = R.drawable.ic_m_move) {
                pickFolder(ctx, BookmarkStore.subtree(ctx, f.id), allowNew = false) { parent ->
                    BookmarkStore.saveFolder(ctx, f.copy(parent = parent))
                    refresh()
                }
            },
        )
        if (index > 0) entries += MenuEntry(ctx.getString(R.string.ml_move_up), icon = R.drawable.ic_m_up) { BookmarkStore.moveFolder(ctx, f.id, -1); refresh() }
        if (index in 0 until siblings.lastIndex) entries += MenuEntry(ctx.getString(R.string.ml_move_down), icon = R.drawable.ic_m_down) { BookmarkStore.moveFolder(ctx, f.id, 1); refresh() }
        entries += MenuEntry(ctx.getString(R.string.ml_bm_delete_folder), icon = R.drawable.ic_m_delete, destructive = true) {
            ctx.showConfirmDialog(ctx.getString(R.string.ml_bm_delete_folder), ctx.getString(R.string.ml_bm_delete_folder_confirm, f.name), ctx.getString(R.string.ml_bm_delete)) {
                BookmarkStore.deleteFolder(ctx, f.id)
                refresh()
            }
        }
        ctx.showMenuDialog(f.name, entries)
    }

    private fun newFolder(ctx: Context, parent: Long, onCreated: (BookmarkFolder) -> Unit) {
        ctx.showTextInputDialog(ctx.getString(R.string.ml_bm_new_folder), "", ctx.getString(R.string.ml_bm_folder_hint), ctx.getString(R.string.ml_save)) { name ->
            onCreated(BookmarkStore.saveFolder(ctx, BookmarkFolder(0L, name, parent)))
        }
    }

    /** A category (0 = none) for a bookmark, or the new parent of a category ([exclude]: itself and what's under it). */
    private fun pickFolder(ctx: Context, exclude: Set<Long>, allowNew: Boolean = true, onPicked: (Long) -> Unit) {
        val entries = mutableListOf(MenuEntry(ctx.getString(R.string.ml_bm_no_folder), icon = R.drawable.ic_m_star) { onPicked(0L) })
        BookmarkStore.folderPaths(ctx).filter { it.first.id !in exclude }.forEach { (f, path) ->
            entries += MenuEntry(path, icon = R.drawable.ic_m_folder) { onPicked(f.id) }
        }
        if (allowNew) entries += MenuEntry(ctx.getString(R.string.ml_bm_new_folder), icon = R.drawable.ic_m_add) {
            newFolder(ctx, 0L) { onPicked(it.id) }
        }
        ctx.showMenuDialog(ctx.getString(R.string.ml_bm_folder), entries)
    }

    // ---------------------------------------------------------------- form

    /** A new bookmark from the address copied to the clipboard, if there is one. */
    fun fromClipboard(ctx: Context): Bookmark {
        val text = try {
            (ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).primaryClip?.getItemAt(0)?.coerceToText(ctx)?.toString()
        } catch (e: Exception) {
            null
        }
        return Bookmark(0L, "", BookmarkStore.findUrl(text).orEmpty())
    }

    /**
     * Add or edit [initial] (id 0 = new). [onSaved] after saving; [onClosed] whenever the form goes
     * (saved or not).
     */
    fun showForm(context: Context, initial: Bookmark, onClosed: () -> Unit = {}, onSaved: () -> Unit) {
        val dialog = OlDialog(context)
        val ctx = dialog.context
        var folder = initial.folder.takeIf { it == 0L || BookmarkStore.folders(ctx).any { f -> f.id == it } } ?: 0L
        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(22.dpToPx(), 20.dpToPx(), 22.dpToPx(), 22.dpToPx())
        }
        root.addView(LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(UiKit.icon(ctx, R.drawable.ic_m_star, UiKit.primary(ctx), 22).apply {
                (layoutParams as LinearLayout.LayoutParams).marginEnd = 10.dpToPx()
            })
            addView(TextView(ctx).apply {
                setText(if (initial.id == 0L) R.string.ml_bm_add else R.string.ml_bm_edit)
                textSize = 19f
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(UiKit.primary(ctx))
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(UiKit.iconButton(ctx, R.drawable.ic_m_close, ctx.getString(R.string.close)) { dialog.dismiss() })
        })
        fun label(res: Int) = TextView(ctx).apply {
            setText(res)
            textSize = 12f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(UiKit.secondary(ctx))
            setPadding(2.dpToPx(), 14.dpToPx(), 0, 6.dpToPx())
        }
        fun input(value: String, type: Int) = EditText(ctx).apply {
            setText(value)
            setSingleLine(true)
            inputType = type
            textSize = 15f
            UiKit.styleInput(this)
        }
        val title = input(initial.title, InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES)
        val url = input(initial.url, InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI)
        root.addView(label(R.string.ml_bm_name))
        root.addView(title, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 46.dpToPx()))
        root.addView(label(R.string.ml_bm_url))
        root.addView(url, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 46.dpToPx()))
        root.addView(label(R.string.ml_bm_folder))

        val folderText = TextView(ctx).apply {
            textSize = 15f
            setTextColor(UiKit.primary(ctx))
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.START
        }
        val chips = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        lateinit var refreshFolder: () -> Unit
        root.addView(LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(14.dpToPx(), 0, 10.dpToPx(), 0)
            background = UiKit.border(ctx, 14).apply { setStroke(1.dpToPx(), UiKit.stroke(ctx)) }
            foreground = UiKit.ripple(ctx)
            isClickable = true
            addView(folderText, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(UiKit.icon(ctx, R.drawable.ic_m_chevron_down, UiKit.secondary(ctx), 18))
            setOnClickListener {
                pickFolder(ctx, emptySet()) { picked ->
                    folder = picked
                    refreshFolder()
                }
            }
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 46.dpToPx()))
        root.addView(HorizontalScrollView(ctx).apply {
            isHorizontalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            addView(chips)
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = 10.dpToPx() })

        fun chip(text: String, selected: Boolean, onClick: () -> Unit) = TextView(ctx).apply {
            this.text = text
            textSize = 12.5f
            setTypeface(typeface, Typeface.BOLD)
            gravity = Gravity.CENTER
            maxLines = 1
            setPadding(12.dpToPx(), 0, 12.dpToPx(), 0)
            val primary = UiKit.primary(ctx)
            background = GradientDrawable().apply {
                cornerRadius = 15.dpToPx().toFloat()
                if (selected) setColor(primary) else setStroke(1.dpToPx(), UiKit.stroke(ctx))
            }
            setTextColor(if (selected) UiKit.card(ctx) else primary)
            isClickable = true
            setOnClickListener { onClick() }
        }
        refreshFolder = {
            folderText.text = BookmarkStore.path(ctx, folder).ifEmpty { ctx.getString(R.string.ml_bm_no_folder) }
            chips.removeAllViews()
            val recent = BookmarkStore.recentFolders(ctx).let { r -> if (folder != 0L && folder !in r) listOf(folder) + r else r }
            fun add(v: View) = chips.addView(v, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, 30.dpToPx()).apply { marginEnd = 8.dpToPx() })
            recent.forEach { id ->
                add(chip(BookmarkStore.path(ctx, id), id == folder) { folder = id; refreshFolder() })
            }
            add(chip(ctx.getString(R.string.ml_bm_no_folder), folder == 0L) { folder = 0L; refreshFolder() })
            add(chip("+ " + ctx.getString(R.string.ml_bm_new_folder_short), false) {
                newFolder(ctx, 0L) { folder = it.id; refreshFolder() }
            })
        }
        refreshFolder()

        val primary = ctx.getColorFromAttr(R.attr.primaryColor)
        root.addView(LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 18.dpToPx(), 0, 0)
            addView(pillButton(ctx, ctx.getString(R.string.ml_todo_cancel), filled = false, color = primary) { dialog.dismiss() },
                LinearLayout.LayoutParams(0, 48.dpToPx(), 1f).apply { marginEnd = 10.dpToPx() })
            addView(pillButton(ctx, ctx.getString(R.string.ml_save), filled = true, color = primary) {
                var address = url.text.toString().trim()
                if (address.isEmpty()) {
                    ctx.showToast(ctx.getString(R.string.ml_bm_need_url))
                    return@pillButton
                }
                if (!address.contains("://")) address = "https://$address"
                val name = title.text.toString().trim().ifEmpty { BookmarkStore.domain(address) }
                BookmarkStore.saveBookmark(ctx, initial.copy(title = name, url = address, folder = folder))
                if (folder != 0L) BookmarkStore.setFolderOpen(ctx, folder, true)
                url.hideKeyboard()
                dialog.dismiss()
                ctx.showToast(ctx.getString(R.string.ml_bm_saved))
                onSaved()
            }, LinearLayout.LayoutParams(0, 48.dpToPx(), 1f))
        })
        dialog.setView(ScrollView(ctx).apply {
            isVerticalScrollBarEnabled = false
            addView(root)
        })
        dialog.setOnDismissListener { onClosed() }
        dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        dialog.showRespectingStatusBar()
        if (initial.title.isEmpty() && initial.url.isEmpty()) url.requestFocus()
    }

    // ---------------------------------------------------------------- opening

    /** Installed browsers (package, name). */
    fun browsers(ctx: Context): List<Pair<String, String>> {
        val pm = ctx.packageManager
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://example.com"))
        return try {
            pm.queryIntentActivities(intent, PackageManager.MATCH_ALL)
                .map { it.activityInfo.packageName to it.loadLabel(pm).toString() }
                .filter { it.first != ctx.packageName }
                .distinctBy { it.first }
                .sortedBy { it.second.lowercase() }
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun open(ctx: Context, b: Bookmark, browser: String) {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(b.url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        // Samsung Internet unless the bookmark has a browser of its own
        val pkg = browser.ifEmpty { SamsungBrowser.installed(ctx).orEmpty() }
        if (pkg.isNotEmpty()) intent.setPackage(pkg)
        try {
            ctx.startActivity(intent)
        } catch (e: Exception) {
            try {
                ctx.startActivity(intent.setPackage(null))
            } catch (e2: Exception) {
                ctx.showToast(ctx.getString(R.string.ml_bm_cant_open))
            }
        }
    }
}
