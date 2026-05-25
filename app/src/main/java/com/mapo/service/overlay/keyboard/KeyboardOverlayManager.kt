package com.mapo.service.overlay.keyboard

import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.Display
import android.view.View
import android.view.WindowManager
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.ComposeView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.mapo.service.overlay.OverlayLifecycleOwner
import com.mapo.ui.theme.MapoTheme
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Hosts the virtual-keyboard overlay window(s) via [WindowManager]. Sibling of
 * [com.mapo.service.overlay.OverlayManager] (not a subclass) — different lifecycle
 * and a different focus / touch flag matrix.
 *
 * **Brick 1 of the single-screen refactor.** API is shaped multi-window-capable and
 * display-aware from the start so FC2 (Thor bottom screen as canvas extension of
 * the active overlay) is a local change later, not an API rewrite. Only one window
 * is ever attached in this brick (the placeholder POC); the [overlayId] / [displayId]
 * parameters are real seams, not stubs.
 *
 * **Flag matrix.** `TYPE_APPLICATION_OVERLAY` + `FLAG_NOT_FOCUSABLE` +
 * `FLAG_NOT_TOUCH_MODAL`. Touches inside the window still reach Compose (touch
 * routing is governed by `FLAG_NOT_TOUCHABLE`, which is absent), touches outside
 * pass through to the foreground app, and **key events route past the overlay**
 * to whatever window holds keyboard focus underneath — so the foreground game
 * keeps receiving gamepad input while the keyboard is mounted. Discovered the
 * hard way during Brick 1 device verification: without `FLAG_NOT_FOCUSABLE` the
 * overlay absorbed all key events and the user's gamepad started navigating the
 * overlay's Compose focus tree instead of driving the game.
 *
 * **Foreground service.** Attaching the first overlay starts
 * [KeyboardOverlayService] (Android 12+ kills cached processes within seconds —
 * without an FGS the overlay window vanishes the moment the launching activity is
 * backgrounded). The service is stopped when the last overlay detaches.
 */
@Singleton
class KeyboardOverlayManager @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    private val mainHandler = Handler(Looper.getMainLooper())
    private val attached = mutableMapOf<String, AttachedKeyboardOverlay>()

    fun canShow(): Boolean = Settings.canDrawOverlays(context)

    /**
     * Attach an overlay window under [overlayId]. Replaces any existing window
     * registered under the same id. The window is touchable + focusable; taps
     * inside reach Compose and taps outside reach the foreground app. [displayId]
     * targets a specific physical display — defaults to [Display.DEFAULT_DISPLAY];
     * full multi-display routing lands in Brick 5.
     */
    fun attach(
        overlayId: String,
        displayId: Int = Display.DEFAULT_DISPLAY,
        content: @Composable () -> Unit,
    ) {
        runOnMain {
            if (!canShow()) {
                Log.w(TAG, "attach($overlayId) skipped: SYSTEM_ALERT_WINDOW not granted")
                return@runOnMain
            }
            detachInternal(overlayId)

            val displayContext = displayContextOrFallback(displayId)
            val windowManager = displayContext.getSystemService(Context.WINDOW_SERVICE) as WindowManager

            val owner = OverlayLifecycleOwner()
            val composeView = ComposeView(displayContext).apply {
                setViewTreeLifecycleOwner(owner)
                setViewTreeViewModelStoreOwner(owner)
                setViewTreeSavedStateRegistryOwner(owner)
                defaultFocusHighlightEnabled = false
                setContent { MapoTheme { content() } }
            }
            owner.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
            owner.handleLifecycleEvent(Lifecycle.Event.ON_START)
            owner.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)

            try {
                windowManager.addView(composeView, layoutParams(displayContext))
            } catch (e: Exception) {
                Log.e(TAG, "addView($overlayId) failed", e)
                owner.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
                return@runOnMain
            }
            val wasEmpty = attached.isEmpty()
            attached[overlayId] = AttachedKeyboardOverlay(
                view = composeView,
                owner = owner,
                windowManager = windowManager,
                displayId = displayId,
            )
            if (wasEmpty) startService()
            Log.i(TAG, "attached '$overlayId' on display $displayId (active=${attached.size})")
        }
    }

    fun detach(overlayId: String) {
        runOnMain { detachInternal(overlayId) }
    }

    fun detachAll() {
        runOnMain { attached.keys.toList().forEach { detachInternal(it) } }
    }

    fun isAttached(overlayId: String): Boolean = attached.containsKey(overlayId)

    fun activeOverlayCount(): Int = attached.size

    // ── internals ─────────────────────────────────────────────────────────────

    private fun detachInternal(overlayId: String) {
        val entry = attached.remove(overlayId) ?: return
        try {
            entry.windowManager.removeViewImmediate(entry.view)
        } catch (e: Exception) {
            Log.w(TAG, "removeView($overlayId) failed (already gone?)", e)
        }
        entry.owner.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE)
        entry.owner.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
        entry.owner.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
        Log.d(TAG, "detached '$overlayId' (active=${attached.size})")
        if (attached.isEmpty()) stopService()
    }

    private fun displayContextOrFallback(displayId: Int): Context {
        if (displayId == Display.DEFAULT_DISPLAY) return context
        val displayManager = context.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
        val display = displayManager.getDisplay(displayId)
        if (display == null) {
            Log.w(TAG, "display $displayId not found; falling back to default")
            return context
        }
        return context.createDisplayContext(display)
    }

    private fun layoutParams(ctx: Context): WindowManager.LayoutParams {
        // FLAG_NOT_FOCUSABLE — gamepad / key events route past us to the focused
        //   foreground app. Required: without it the overlay absorbs every key
        //   press and the user's gamepad navigates the overlay instead of the game.
        // FLAG_NOT_TOUCH_MODAL — touches outside the window's bounds pass through
        //   to whatever's underneath. (FLAG_NOT_TOUCHABLE is intentionally absent
        //   so in-bounds taps still reach Compose's clickable handlers.)
        val flags = WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
        return WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            flags,
            PixelFormat.TRANSLUCENT,
        )
    }

    private fun startService() {
        val intent = Intent(context, KeyboardOverlayService::class.java)
        context.startForegroundService(intent)
    }

    private fun stopService() {
        context.stopService(Intent(context, KeyboardOverlayService::class.java))
    }

    private fun runOnMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block() else mainHandler.post(block)
    }

    private data class AttachedKeyboardOverlay(
        val view: View,
        val owner: OverlayLifecycleOwner,
        val windowManager: WindowManager,
        val displayId: Int,
    )

    companion object {
        private const val TAG = "KeyboardOverlayMgr"
    }
}
