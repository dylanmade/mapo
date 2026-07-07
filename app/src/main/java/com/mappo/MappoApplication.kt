package com.mappo

import android.app.Application
import android.os.Build
import android.util.Log
import com.mappo.service.autoswitch.ProfileAutoSwitcher
import com.mappo.service.input.GyroLifecycleCoordinator
import com.mappo.service.overlay.OverlayCoordinator
import com.mappo.service.shizuku.ShizukuConnection
import com.mappo.service.shizuku.ShizukuHealthNotification
import com.mappo.service.shizuku.ShizukuMotionStream
import dagger.hilt.android.HiltAndroidApp
import org.lsposed.hiddenapibypass.HiddenApiBypass
import javax.inject.Inject

@HiltAndroidApp
class MappoApplication : Application() {

    @Inject lateinit var autoSwitcher: ProfileAutoSwitcher
    @Inject lateinit var overlayCoordinator: OverlayCoordinator

    /**
     * Eager-injected so its constructor runs at app start: the state machine
     * needs to be live before any UI surface or `InputAccessibilityService`
     * touches it. Without this, `ShizukuConnection` would lazy-construct on
     * first navigation to the Setup screen, missing earlier permission grants
     * the user may have done out-of-band.
     */
    @Inject lateinit var shizukuConnection: ShizukuConnection

    /**
     * Eager-inject so the Shizuku callback gets re-registered the moment the
     * UserService binds — independent of whether any UI is observing
     * `analogEvents`. Brick D adds the InputEvaluator wire-up.
     */
    @Inject lateinit var shizukuMotionStream: ShizukuMotionStream

    /**
     * Brick G: persistent health notification. Subscribes from app start so
     * the post-game / next-launch reminder fires even if the user never opens
     * a Mappo activity during the session that broke Shizuku.
     */
    @Inject lateinit var shizukuHealthNotification: ShizukuHealthNotification

    /**
     * Brick D.2: gyro lifecycle coordinator. Watches compiled config + active
     * set / layers and registers the gyro sensor listener only when a real
     * gyro mode is configured for the active scope. Started from
     * `onCreate` so the sensor stays unregistered (battery) when no profile
     * uses gyro, and lights up the moment one does.
     */
    @Inject lateinit var gyroLifecycleCoordinator: GyroLifecycleCoordinator

    override fun onCreate() {
        super.onCreate()
        Log.i("MappoApplication", "onCreate: enter")
        installUncaughtExceptionLogger()
        installHiddenApiExemptions()
        autoSwitcher.start()
        overlayCoordinator.start()
        shizukuHealthNotification.start()
        gyroLifecycleCoordinator.start()
        // Touch the Shizuku singletons so Hilt actually constructs them.
        // Field access by itself is enough; `lateinit` injection has already
        // run by this point (Application is the Hilt root).
        Log.i("MappoApplication", "Shizuku connection state: ${shizukuConnection.state.value}")
        Log.i("MappoApplication", "onCreate: exit")
    }

    /**
     * Chain a logger onto the JVM's default uncaught-exception handler so any
     * thread crash leaves a tagged breadcrumb before the OS-default handler
     * tears the process down. Critical for diagnosing the Shizuku-revocation
     * teardown — Android's default handler prints the FATAL EXCEPTION block to
     * the `AndroidRuntime` tag, but if anything intervenes (a native crash, a
     * silent process exit) we'd otherwise have no signal. Logging here is
     * additive — we still delegate to the original handler so default crash
     * reporting is unaffected.
     */
    private fun installUncaughtExceptionLogger() {
        val prior = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            Log.e(
                "MappoApplication",
                "UNCAUGHT EXCEPTION on thread '${thread.name}' (id=${thread.id})",
                throwable,
            )
            prior?.uncaughtException(thread, throwable)
        }
    }

    /**
     * Exempts hidden-API surfaces from runtime enforcement:
     *
     *  - `android.view.InputEvent`: `setDisplayId(int)` / `getDisplayId()` so
     *    [InputAccessibilityService] can stamp a target display on injected `KeyEvent`s.
     *    Without this, every injected event defaults to `Display.DEFAULT_DISPLAY`,
     *    dropping any remap output meant for the AYN Thor's bottom screen.
     *
     * (The former `android.view.ViewTreeObserver` exemption — for the legacy keyboard
     * overlay's per-rect touchable-region bridge — was removed when that overlay was
     * gutted; the rebuilt overlay uses one window per button, so no `@hide` insets API.)
     *
     * Scoped to specific class signatures so we don't blanket-exempt the whole runtime.
     * No-op on Android <9 (no hidden-API enforcement); the library handles the
     * version-specific bypass mechanism internally.
     */
    private fun installHiddenApiExemptions() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return
        try {
            HiddenApiBypass.addHiddenApiExemptions(
                "Landroid/view/InputEvent;",
            )
            Log.i("MappoApplication", "Hidden API exemptions installed (InputEvent)")
        } catch (e: Throwable) {
            Log.w("MappoApplication", "Failed to install hidden API exemptions", e)
        }
    }
}
