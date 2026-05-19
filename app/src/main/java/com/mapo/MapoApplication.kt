package com.mapo

import android.app.Application
import android.os.Build
import android.util.Log
import com.mapo.service.autoswitch.ProfileAutoSwitcher
import com.mapo.service.overlay.OverlayCoordinator
import dagger.hilt.android.HiltAndroidApp
import org.lsposed.hiddenapibypass.HiddenApiBypass
import javax.inject.Inject

@HiltAndroidApp
class MapoApplication : Application() {

    @Inject lateinit var autoSwitcher: ProfileAutoSwitcher
    @Inject lateinit var overlayCoordinator: OverlayCoordinator

    override fun onCreate() {
        super.onCreate()
        installHiddenApiExemptions()
        autoSwitcher.start()
        overlayCoordinator.start()
    }

    /**
     * Exempts `android.view.InputEvent`'s hidden API methods (`setDisplayId(int)`,
     * `getDisplayId()`) from runtime hidden-API enforcement so [InputAccessibilityService]
     * can stamp a target display on injected `KeyEvent`s. Without this, every injected
     * event defaults to `Display.DEFAULT_DISPLAY`, dropping any remap output meant for
     * the AYN Thor's bottom screen.
     *
     * Scoped to the `InputEvent` class signature so we don't blanket-exempt the whole
     * runtime. No-op on Android <9 (no hidden-API enforcement); the library handles the
     * version-specific bypass mechanism internally.
     */
    private fun installHiddenApiExemptions() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return
        try {
            HiddenApiBypass.addHiddenApiExemptions("Landroid/view/InputEvent;")
            Log.i("MapoApplication", "Hidden API exemption installed for InputEvent")
        } catch (e: Throwable) {
            Log.w("MapoApplication", "Failed to install hidden API exemption for InputEvent", e)
        }
    }
}
