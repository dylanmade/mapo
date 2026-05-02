package com.mapo.service.overlay

import android.content.Context
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionContext
import androidx.compose.ui.platform.ComposeView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.mapo.service.InputAccessibilityService
import com.mapo.ui.theme.MapoTheme
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Hosts a single Compose-rendered overlay window via WindowManager. All public methods
 * are safe to call from any thread; UI work is dispatched onto the main thread.
 *
 * Two surfaces are supported: a non-focusable transient toast and a focusable prompt
 * with interactive buttons. While the focusable prompt is up, [InputAccessibilityService]
 * routes gamepad A → ENTER and B → BACK so DPAD nav + click works on the overlay.
 */
@Singleton
class OverlayManager @Inject constructor(
    @ApplicationContext private val context: Context
) {

    private val windowManager: WindowManager =
        context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val mainHandler = Handler(Looper.getMainLooper())

    private var current: AttachedOverlay? = null

    fun canShow(): Boolean = Settings.canDrawOverlays(context)

    fun showToast(message: String, durationMs: Long = TOAST_DURATION_MS) {
        runOnMain {
            attach(focusable = false) { dismiss ->
                OverlayToast(
                    message = message,
                    autoDismissMs = durationMs,
                    onDismiss = dismiss
                )
            }
        }
    }

    fun showCreatePrompt(
        appLabel: String,
        onYes: () -> Unit,
        onNo: () -> Unit,
        onNever: () -> Unit
    ) {
        runOnMain {
            attach(focusable = true) { dismiss ->
                OverlayCreatePrompt(
                    appLabel = appLabel,
                    onYes = { onYes(); dismiss() },
                    onNo = { onNo(); dismiss() },
                    onNever = { onNever(); dismiss() }
                )
            }
        }
    }

    fun dismiss() {
        runOnMain { detach() }
    }

    // ── internals ─────────────────────────────────────────────────────────────

    private fun attach(focusable: Boolean, content: @Composable (dismiss: () -> Unit) -> Unit) {
        if (!canShow()) {
            Log.w(TAG, "attach skipped: overlay permission not granted")
            return
        }
        detach()

        val owner = OverlayLifecycleOwner()
        val composeView = ComposeView(context).apply {
            setViewTreeLifecycleOwner(owner)
            setViewTreeViewModelStoreOwner(owner)
            setViewTreeSavedStateRegistryOwner(owner)
            setContent {
                MapoTheme {
                    content { dismiss() }
                }
            }
        }
        owner.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
        owner.handleLifecycleEvent(Lifecycle.Event.ON_START)
        owner.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)

        try {
            windowManager.addView(composeView, layoutParams(focusable))
        } catch (e: Exception) {
            Log.e(TAG, "addView failed", e)
            owner.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
            return
        }

        current = AttachedOverlay(composeView, owner, focusable)
        if (focusable) InputAccessibilityService.overlayFocused = true
        Log.i(TAG, "overlay attached (focusable=$focusable)")
    }

    private fun detach() {
        val attached = current ?: return
        current = null
        if (attached.focusable) InputAccessibilityService.overlayFocused = false
        try {
            windowManager.removeViewImmediate(attached.view)
        } catch (e: Exception) {
            Log.w(TAG, "removeView failed (already gone?)", e)
        }
        attached.owner.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE)
        attached.owner.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
        attached.owner.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
        Log.d(TAG, "overlay detached")
    }

    private fun layoutParams(focusable: Boolean): WindowManager.LayoutParams {
        var flags = WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
        if (!focusable) {
            // Non-focusable + non-touch-modal so input passes through to the underlying
            // app for the toast case.
            flags = flags or
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
        }
        return WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            flags,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            y = 64
        }
    }

    private fun runOnMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block()
        else mainHandler.post(block)
    }

    private data class AttachedOverlay(
        val view: View,
        val owner: OverlayLifecycleOwner,
        val focusable: Boolean
    )

    companion object {
        private const val TAG = "OverlayManager"
        private const val TOAST_DURATION_MS = 3_000L
    }
}
