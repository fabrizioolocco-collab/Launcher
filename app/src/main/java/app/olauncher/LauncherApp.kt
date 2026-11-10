package app.olauncher

import android.app.Application
import app.olauncher.helper.Skin

class LauncherApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // The look chosen in the settings colours every screen of the launcher
        Skin.register(this)
    }
}
