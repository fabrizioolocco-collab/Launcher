package app.olauncher.helper

import android.content.Context
import android.net.Uri
import app.olauncher.R
import app.olauncher.data.AppModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** "Change icon" menu: icon from any installed icon pack, from the gallery, or back to default. */
object IconChooser {

    fun show(context: Context, app: AppModel.App, scope: CoroutineScope, pickImage: ((Uri) -> Unit) -> Unit, onChanged: () -> Unit) {
        val entries = mutableListOf(
            MenuEntry(context.getString(R.string.ml_icon_from_pack)) { pickFromPack(context, app, scope, onChanged) },
            MenuEntry(context.getString(R.string.ml_icon_from_gallery)) {
                pickImage { uri ->
                    scope.launch {
                        val ok = withContext(Dispatchers.IO) {
                            try {
                                CustomIcons.setFromImage(context, app.appPackage, app.activityClassName, app.user, uri)
                            } catch (e: Exception) {
                                e.printStackTrace()
                                false
                            }
                        }
                        if (!ok) context.showToast(context.getString(R.string.ml_icon_image_error))
                        onChanged()
                    }
                }
            },
        )
        if (CustomIcons.hasCustom(context, app.appPackage, app.activityClassName, app.user))
            entries += MenuEntry(context.getString(R.string.ml_icon_reset)) {
                CustomIcons.reset(context, app.appPackage, app.activityClassName, app.user)
                onChanged()
            }
        context.showMenuDialog(context.getString(R.string.ml_change_icon), entries)
    }

    private fun pickFromPack(context: Context, app: AppModel.App, scope: CoroutineScope, onChanged: () -> Unit) {
        val packs = IconPackManager.installedPacks(context)
        if (packs.isEmpty()) {
            context.showToast(context.getString(R.string.ml_no_icon_packs))
            return
        }
        fun openPack(pack: IconPackManager.IconPackInfo) {
            scope.launch {
                val names = withContext(Dispatchers.IO) { IconPackManager.packIconNames(context, pack.packageName) }
                if (names.isEmpty()) {
                    context.showToast(context.getString(R.string.ml_no_icons_in_pack))
                    return@launch
                }
                context.showIconGridDialog(
                    title = pack.label,
                    names = names,
                    scope = scope,
                    loadIcon = { name, size -> IconPackManager.loadPackDrawable(context, pack.packageName, name, size) },
                ) { name ->
                    CustomIcons.setFromPack(context, app.appPackage, app.activityClassName, app.user, pack.packageName, name)
                    onChanged()
                }
            }
        }
        if (packs.size == 1) openPack(packs.first())
        else context.showMenuDialog(
            context.getString(R.string.ml_icon_pack),
            packs.map { pack -> MenuEntry(pack.label) { openPack(pack) } }
        )
    }
}
