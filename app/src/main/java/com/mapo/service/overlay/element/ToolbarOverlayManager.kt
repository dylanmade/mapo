package com.mapo.service.overlay.element

import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.ComposeView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.mapo.MainActivity
import com.mapo.data.repository.ProfileRepository
import com.mapo.service.keyboard.KeyboardController
import com.mapo.service.overlay.OverlayLifecycleOwner
import com.mapo.service.overlay.keyboard.KeyboardOverlayService
import com.mapo.steam.auth.SteamCredentialStore
import com.mapo.ui.screen.MainBottomToolbar
import com.mapo.ui.theme.MapoTheme
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * **Brick 1 of the overlay-toolbar workstream (`OVERLAY_TOOLBAR_PLAN.md`).** Mounts the
 * Mapo home chrome ([MainBottomToolbar]) in a single non-focusable
 * `TYPE_APPLICATION_OVERLAY` window so it floats over the foregrounded game and gamepad
 * input flows past it — the inverse of the in-activity home, which owns focus.
 *
 * Brick 1 is **touch-only and coexists** with the in-activity toolbar (nothing deleted):
 * the master switch + the profile / options menus all work by touch; gamepad navigation
 * of this non-focusable window (Implementation B) lands in Brick 3. Navigation items that
 * need a full screen launch [MainActivity] for now; precise deep-route launching is Brick 2.
 *
 * **Flag matrix** is identical to [OverlayElementWindowManager]'s per-button windows —
 * `TYPE_APPLICATION_OVERLAY` + `FLAG_NOT_FOCUSABLE` (key/gamepad events route past us to the
 * game) + `FLAG_NOT_TOUCH_MODAL` (out-of-bounds taps fall through) + `FLAG_LAYOUT_IN_SCREEN`
 * + `FLAG_LAYOUT_NO_LIMITS` + `FLAG_HARDWARE_ACCELERATED` (WindowManager windows don't inherit
 * it from the manifest; without it the ComposeView renders in software with banded gradients
 * and jagged edges — see `project_overlay_windows_need_hw_accel`).
 *
 * **FGS.** Reuses [KeyboardOverlayService] for process priority while up — same shared-service
 * caveat as [OverlayElementWindowManager] (last detacher stops it for both); they don't
 * coexist in the MVP, and a ref-counted FGS owner is the documented follow-up.
 */
@Singleton
class ToolbarOverlayManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val profileRepository: ProfileRepository,
    private val steamCredentialStore: SteamCredentialStore,
    private val keyboardController: KeyboardController,
    private val overlayPresenter: OverlayPresenter,
    private val overlayLiveEditController: OverlayLiveEditController,
) {

    private val mainHandler = Handler(Looper.getMainLooper())
    private val windowManager: WindowManager =
        context.getSystemService(Context.WINDOW_SERVICE) as WindowManager

    private var attached: Attached? = null
    private val _showing = MutableStateFlow(false)
    val showing: StateFlow<Boolean> = _showing.asStateFlow()

    fun canShow(): Boolean = Settings.canDrawOverlays(context)

    fun toggle() {
        if (attached != null) hide() else show()
    }

    fun show() = runOnMain {
        if (attached != null) return@runOnMain
        if (!canShow()) {
            Log.w(TAG, "show skipped: SYSTEM_ALERT_WINDOW not granted")
            return@runOnMain
        }
        val owner = OverlayLifecycleOwner()
        val composeView = ComposeView(context).apply {
            setViewTreeLifecycleOwner(owner)
            setViewTreeViewModelStoreOwner(owner)
            setViewTreeSavedStateRegistryOwner(owner)
            // M3 button focus states are the intended visual indicator; suppress the
            // Android system focus rectangle that would otherwise draw on top.
            defaultFocusHighlightEnabled = false
            setContent {
                MapoTheme { ToolbarContent() }
            }
        }
        owner.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
        owner.handleLifecycleEvent(Lifecycle.Event.ON_START)
        owner.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)

        try {
            windowManager.addView(composeView, layoutParams())
        } catch (e: Exception) {
            Log.e(TAG, "addView failed", e)
            owner.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
            return@runOnMain
        }
        attached = Attached(composeView, owner)
        _showing.value = true
        startService()
        Log.i(TAG, "toolbar overlay attached")
    }

    fun hide() = runOnMain {
        val entry = attached ?: return@runOnMain
        attached = null
        _showing.value = false
        runCatching { windowManager.removeViewImmediate(entry.view) }
            .onFailure { Log.w(TAG, "removeView failed (already gone?)", it) }
        entry.owner.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE)
        entry.owner.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
        entry.owner.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
        stopService()
        Log.i(TAG, "toolbar overlay detached")
    }

    // ── content ───────────────────────────────────────────────────────────────

    @androidx.compose.runtime.Composable
    private fun ToolbarContent() {
        val profile by profileRepository.activeProfile.collectAsState()
        val steamName by steamCredentialStore.credentials
            .map { it?.accountName }
            .collectAsState(initial = null)
        val remapEnabled by keyboardController.remapEnabled.collectAsState()
        val overlayShowing by overlayPresenter.showing.collectAsState()

        MainBottomToolbar(
            profileName = profile?.name ?: "None",
            steamAccountName = steamName,
            // Master toggle drives remap AND the button overlay to the same value in lockstep,
            // mirroring the in-activity home (MainScreen).
            mapoEnabled = remapEnabled,
            onSetMapoEnabled = { target ->
                if (remapEnabled != target) keyboardController.toggleRemap()
                if (overlayShowing != target) overlayPresenter.toggle()
            },
            onEditOverlay = { overlayLiveEditController.requestEdit() },
            // Brick 1: deep-screen items launch the activity; Brick 2 routes to the exact
            // destination via an intent extra. For now they all open the activity home.
            onEditControls = ::launchActivity,
            onOpenProfile = ::launchActivity,
            onOpenAutoSwitch = ::launchActivity,
            onOpenBlocklist = ::launchActivity,
            onOpenThemeStudio = ::launchActivity,
            onOpenShizukuSetup = ::launchActivity,
            onOpenSteamSetup = ::launchActivity,
            onOpenCompactGallery = ::launchActivity,
            onOpenColorPickerDemo = ::launchActivity,
        )
    }

    // ── internals ─────────────────────────────────────────────────────────────

    private fun launchActivity() {
        val intent = Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }

    private fun layoutParams(): WindowManager.LayoutParams {
        val flags = WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
            WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED
        return WindowManager.LayoutParams(
            // WRAP_CONTENT width so the window is exactly the pill's width — taps to the
            // left/right of the pill land on no window and fall through to the game (the
            // job the in-activity tap-catcher used to do).
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            flags,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            y = (24 * context.resources.displayMetrics.density).toInt()
        }
    }

    private fun startService() {
        context.startForegroundService(Intent(context, KeyboardOverlayService::class.java))
    }

    private fun stopService() {
        context.stopService(Intent(context, KeyboardOverlayService::class.java))
    }

    private fun runOnMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block() else mainHandler.post(block)
    }

    private data class Attached(
        val view: View,
        val owner: OverlayLifecycleOwner,
    )

    companion object {
        private const val TAG = "ToolbarOverlayMgr"
    }
}
