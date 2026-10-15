package app.olauncher.ui

import android.app.Activity
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.widget.AppCompatCheckBox
import androidx.core.view.isVisible
import androidx.core.widget.CompoundButtonCompat
import app.olauncher.R
import app.olauncher.data.Constants
import app.olauncher.databinding.DialogBaseBinding
import app.olauncher.helper.DetoxManager
import app.olauncher.helper.MenuEntry
import app.olauncher.helper.NotificationRepository
import app.olauncher.helper.OlDialog
import app.olauncher.helper.dpToPx
import app.olauncher.helper.getColorFromAttr
import app.olauncher.helper.isAccessServiceEnabled
import app.olauncher.helper.isOlauncherDefault
import app.olauncher.helper.showMenuDialog
import app.olauncher.helper.showToast
import app.olauncher.listener.DeviceAdmin

/**
 * Home side of detox: the "Detox" line under the date (countdown while a session runs), the start
 * dialog, the session menu and the permissions (welcome) screen.
 */
class DetoxController(
    private val activity: Activity,
    private val bar: TextView,
    /** Opens the launcher chooser to set MinimaLauncher as default home. */
    private val setDefaultLauncher: () -> Unit,
    /** Called before opening a system screen, so the launcher doesn't jump back home meanwhile. */
    private val keepScreen: (Boolean) -> Unit,
) {
    private val handler = Handler(Looper.getMainLooper())
    private val ticker = object : Runnable {
        override fun run() {
            DetoxManager.checkExpired(activity)
            updateBar()
            handler.postDelayed(this, 1000)
        }
    }
    private var pausedCount = 0
    private var permissionsDialog: OlDialog? = null
    private var permissionsRefresh: (() -> Unit)? = null

    init {
        bar.setOnClickListener { onBarClick() }
    }

    fun onResume() {
        handler.removeCallbacks(ticker)
        handler.post(ticker)
        permissionsRefresh?.invoke()
    }

    fun onPause() = handler.removeCallbacks(ticker)

    fun setPausedCount(count: Int) {
        pausedCount = count
        updateBar()
    }

    fun updateBar() {
        val ctx = activity
        if (!DetoxManager.isActive(ctx)) {
            bar.text = ctx.getString(R.string.ml_detox_start_label)
            bar.alpha = 0.6f
            return
        }
        bar.alpha = 1f
        val parts = mutableListOf(ctx.getString(R.string.ml_detox_running, DetoxManager.formatDuration(DetoxManager.remainingMs(ctx))))
        if (pausedCount > 0) parts += ctx.resources.getQuantityString(R.plurals.ml_detox_paused, pausedCount, pausedCount)
        DetoxManager.exitWaitLeftMs(ctx)?.let { left ->
            parts += if (left > 0) ctx.getString(R.string.ml_detox_exit_in, DetoxManager.formatDuration(left))
            else ctx.getString(R.string.ml_detox_exit_ready)
        }
        bar.text = parts.joinToString("  ·  ")
    }

    private fun onBarClick() {
        if (DetoxManager.isActive(activity)) showSessionMenu() else showStartDialog()
    }

    // ---------------------------------------------------------------- start

    private fun showStartDialog() {
        val ctx = activity
        if (DetoxManager.whitelist(ctx).isEmpty()) ctx.showToast(ctx.getString(R.string.ml_detox_empty_whitelist_hint))

        val dialog = OlDialog(ctx)
        val binding = DialogBaseBinding.inflate(LayoutInflater.from(dialog.context))
        val dctx = dialog.context
        val primary = dctx.getColorFromAttr(R.attr.primaryColor)
        binding.tvTitle.text = ctx.getString(R.string.ml_detox)
        binding.ivClose.setOnClickListener { dialog.dismiss() }

        val content = LinearLayout(dctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 16.dpToPx(), 0, 8.dpToPx())
        }
        val presets = listOf(15, 30, 60)
        var minutes = DetoxManager.lastMinutes(ctx)
        val row = LinearLayout(dctx).apply { orientation = LinearLayout.HORIZONTAL }
        val custom = EditText(dctx).apply {
            hint = ctx.getString(R.string.ml_detox_custom_minutes)
            inputType = InputType.TYPE_CLASS_NUMBER
            setSingleLine(true)
            textSize = 18f
            setTextColor(primary)
            setHintTextColor(dctx.getColorFromAttr(R.attr.primaryColorTrans50))
            backgroundTintList = ColorStateList.valueOf(primary)
            if (minutes !in presets) setText(minutes.toString())
        }
        val chips = presets.map { value ->
            TextView(dctx, null, 0, R.style.TextSmall).apply {
                text = ctx.getString(R.string.ml_detox_minutes, value)
                textSize = 18f
                setTextColor(primary)
                gravity = Gravity.CENTER
                setPadding(0, 12.dpToPx(), 0, 12.dpToPx())
                isClickable = true
            }.also { row.addView(it, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)) }
        }
        fun refreshChips() = chips.forEachIndexed { i, chip ->
            val selected = presets[i] == minutes && custom.text.isNullOrBlank()
            chip.setTypeface(null, if (selected) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL)
            chip.alpha = if (selected) 1f else 0.5f
            chip.setBackgroundResource(if (selected) R.drawable.rounded_rect_shade_color else 0)
        }
        chips.forEachIndexed { i, chip ->
            chip.setOnClickListener {
                minutes = presets[i]
                custom.setText("")
                refreshChips()
            }
        }
        custom.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) {
                s?.toString()?.toIntOrNull()?.let { minutes = it }
                refreshChips()
            }
        })
        refreshChips()
        content.addView(row)
        content.addView(custom, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = 8.dpToPx()
        })
        val strict = AppCompatCheckBox(dctx).apply {
            text = ctx.getString(R.string.ml_detox_strict)
            textSize = 17f
            setTextColor(primary)
            isChecked = true
            CompoundButtonCompat.setButtonTintList(this, ColorStateList.valueOf(primary))
        }
        content.addView(strict, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = 12.dpToPx()
        })
        content.addView(TextView(dctx, null, 0, R.style.TextSmall).apply {
            text = ctx.getString(R.string.ml_detox_strict_hint)
            textSize = 13f
            setTextColor(primary)
            alpha = 0.6f
        })

        binding.contentContainer.addView(content)
        binding.contentContainer.isVisible = true
        binding.tvAction.text = ctx.getString(R.string.ml_detox_start)
        binding.tvAction.isVisible = true
        binding.tvAction.setOnClickListener {
            val chosen = minutes.coerceIn(1, 24 * 60)
            val missing = missingPermissions(strict.isChecked)
            if (missing.isNotEmpty()) {
                ctx.showToast(ctx.getString(R.string.ml_detox_missing_permissions))
                dialog.dismiss()
                showPermissions()
                return@setOnClickListener
            }
            dialog.dismiss()
            DetoxManager.start(ctx, chosen, strict.isChecked)
            updateBar()
        }
        dialog.setView(binding.root)
        dialog.showRespectingStatusBar()
    }

    // ---------------------------------------------------------------- running session

    private fun showSessionMenu() {
        val ctx = activity
        val entries = mutableListOf<MenuEntry>()
        if (!DetoxManager.isStrict(ctx)) {
            entries += MenuEntry(ctx.getString(R.string.ml_detox_end)) { DetoxManager.end(ctx) }
        } else {
            val wait = DetoxManager.exitWaitLeftMs(ctx)
            when {
                wait == null -> entries += MenuEntry(ctx.getString(R.string.ml_detox_request_exit)) {
                    DetoxManager.requestExit(ctx)
                    updateBar()
                }

                wait > 0 -> entries += MenuEntry(ctx.getString(R.string.ml_detox_cancel_exit)) {
                    DetoxManager.cancelExitRequest(ctx)
                    updateBar()
                }

                else -> entries += MenuEntry(ctx.getString(R.string.ml_detox_end)) { DetoxManager.end(ctx) }
            }
        }
        ctx.showMenuDialog(bar.text.toString(), entries)
    }

    // ---------------------------------------------------------------- permissions / welcome

    private fun isAdminActive(): Boolean = try {
        (activity.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager)
            .isAdminActive(ComponentName(activity, DeviceAdmin::class.java))
    } catch (e: Exception) {
        false
    }

    private fun missingPermissions(strict: Boolean): List<String> = buildList {
        if (!isAccessServiceEnabled(activity)) add("accessibility")
        if (!NotificationRepository.isAccessGranted(activity)) add("notifications")
        if (strict && !isAdminActive()) add("admin")
    }

    /** Welcome screen: each permission with its state and a shortcut to the right system page. */
    fun showPermissions() {
        permissionsDialog?.dismiss()
        val ctx = activity
        val dialog = OlDialog(ctx)
        val binding = DialogBaseBinding.inflate(LayoutInflater.from(dialog.context))
        val dctx = dialog.context
        val primary = dctx.getColorFromAttr(R.attr.primaryColor)
        binding.tvTitle.text = ctx.getString(R.string.ml_welcome_title)
        binding.ivClose.setOnClickListener { dialog.dismiss() }
        binding.tvMessage.text = ctx.getString(R.string.ml_welcome_message)
        binding.tvMessage.isVisible = true

        val list = LinearLayout(dctx).apply { orientation = LinearLayout.VERTICAL }
        class Item(val title: Int, val desc: Int, val granted: () -> Boolean, val open: () -> Unit)
        val items = listOf(
            Item(R.string.ml_perm_launcher, R.string.ml_perm_launcher_desc, { isOlauncherDefault(ctx) }) {
                setDefaultLauncher()
            },
            Item(R.string.ml_perm_notifications, R.string.ml_perm_notifications_desc, { NotificationRepository.isAccessGranted(ctx) }) {
                NotificationRepository.openAccessSettings(ctx)
            },
            Item(R.string.ml_perm_accessibility, R.string.ml_perm_accessibility_desc, { isAccessServiceEnabled(ctx) }) {
                ctx.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            },
            Item(R.string.ml_perm_admin, R.string.ml_perm_admin_desc, { isAdminActive() }) {
                val intent = Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN)
                    .putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, ComponentName(ctx, DeviceAdmin::class.java))
                    .putExtra(DevicePolicyManager.EXTRA_ADD_EXPLANATION, ctx.getString(R.string.ml_perm_admin_explanation))
                keepScreen(true)
                @Suppress("DEPRECATION")
                ctx.startActivityForResult(intent, Constants.REQUEST_CODE_ENABLE_ADMIN)
            },
        )
        val rows = items.map { item ->
            TextView(dctx, null, 0, R.style.TextSmall).apply {
                setTextColor(primary)
                textSize = 16f
                setPadding(0, 12.dpToPx(), 0, 12.dpToPx())
                isClickable = true
                setOnClickListener {
                    if (!item.granted()) item.open()
                }
            }.also { list.addView(it) }
        }
        fun refresh() = items.forEachIndexed { i, item ->
            val ok = item.granted()
            rows[i].text = (if (ok) "✓  " else "○  ") + ctx.getString(item.title) + "\n" + ctx.getString(item.desc)
            rows[i].alpha = if (ok) 0.55f else 1f
        }
        refresh()
        permissionsRefresh = { if (dialog.isShowing) refresh() }
        binding.contentContainer.addView(list)
        binding.contentContainer.isVisible = true
        binding.tvAction.text = ctx.getString(R.string.ml_done)
        binding.tvAction.isVisible = true
        binding.tvAction.setOnClickListener { dialog.dismiss() }
        dialog.setOnDismissListener {
            permissionsDialog = null
            permissionsRefresh = null
        }
        dialog.setView(binding.root)
        dialog.showRespectingStatusBar()
        permissionsDialog = dialog
    }

    fun release() {
        handler.removeCallbacks(ticker)
        permissionsDialog?.dismiss()
    }
}
