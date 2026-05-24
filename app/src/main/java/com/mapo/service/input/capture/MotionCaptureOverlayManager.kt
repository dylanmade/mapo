package com.mapo.service.input.capture

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import com.mapo.service.overlay.keyboard.KeyboardOverlayService
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.CountDownLatch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Production motion-capture overlay. Mounts an invisible focused
 * `TYPE_APPLICATION_OVERLAY` whose [MotionCaptureView] captures
 * `onGenericMotionEvent` and forwards it through [motionCallback].
 *
 * **Why focused.** The window must own input focus to receive motion events on
 * Android 13 — non-focusable windows never see `onGenericMotionEvent`. The
 * focused variant of `TYPE_APPLICATION_OVERLAY` does NOT break GameNative's
 * software-rendered cursor (verified by [com.mapo.service.input.MotionProbeAppOverlay]
 * on 2026-05-19), unlike `TYPE_ACCESSIBILITY_OVERLAY` which did.
 *
 * **Side effects.** While attached, focus-owning means the IME, app-switcher
 * gesture, and back gesture stop working. That cost is acceptable because
 * Brick 4's [MotionCaptureCoordinator] (next) gates attachment behind
 * `(foreground app is in profile's bound apps) AND (active config has an
 * analog mode configured)` — the side-effect window is "while playing a game
 * the user opted into analog modes for," same opt-in model Steam Input uses on
 * Steam Deck.
 *
 * **Brick 3 status.** Not yet gated by the coordinator. [attach] / [detach]
 * are driven by a debug toggle (Brick 4 replaces that with the predicate).
 *
 * Thread model: all mutations are serialized onto the main thread because
 * `WindowManager.addView` / `removeView` require it. Callers can invoke from
 * any thread.
 */
@Singleton
class MotionCaptureOverlayManager @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    private val mainHandler = Handler(Looper.getMainLooper())
    private var attachedView: MotionCaptureView? = null
    /**
     * Live [WindowManager.LayoutParams] for the currently-attached overlay.
     * Retained alongside [attachedView] so [withFocusReleasedForInject] can
     * mutate the focusable flag without having to rebuild the params.
     */
    private var attachedParams: WindowManager.LayoutParams? = null

    private val _isAttached = MutableStateFlow(false)
    val isAttached: StateFlow<Boolean> = _isAttached.asStateFlow()

    @Volatile
    private var motionCallback: ((MotionEvent) -> Unit)? = null

    /**
     * Wire the callback that should receive every captured [MotionEvent]. The
     * [InputAccessibilityService] sets this in `onServiceConnected` to route
     * through `InputEvaluator.handleMotion`. Idempotent — overwriting an
     * existing callback is fine (last-writer-wins).
     */
    fun setMotionCallback(callback: (MotionEvent) -> Unit) {
        motionCallback = callback
        // If we're already attached when the callback arrives, retroactively
        // patch it into the live view so motion events stop being silently
        // dropped between attach() and setMotionCallback().
        runOnMain {
            attachedView?.onMotion = callback
        }
    }

    fun canShow(): Boolean = Settings.canDrawOverlays(context)

    /**
     * Idempotent: attaching an already-attached overlay is a no-op. Mutations
     * happen on the main thread.
     */
    fun attach() {
        runOnMain {
            if (attachedView != null) return@runOnMain
            attachInternal()
        }
    }

    /**
     * Idempotent: detaching when not attached is a no-op.
     */
    fun detach() {
        runOnMain {
            val view = attachedView ?: return@runOnMain
            detachInternal(view)
        }
    }

    /** Convenience for the debug toggle. */
    fun toggle() {
        runOnMain {
            val view = attachedView
            if (view == null) attachInternal() else detachInternal(view)
        }
    }

    private fun attachInternal() {
        if (!canShow()) {
            Log.w(TAG, "attach skipped: SYSTEM_ALERT_WINDOW not granted")
            return
        }
        val wm = context.getSystemService(Context.WINDOW_SERVICE) as? WindowManager
        if (wm == null) {
            Log.w(TAG, "attach skipped: WindowManager unavailable")
            return
        }

        val view = MotionCaptureView(context).apply {
            setBackgroundColor(Color.TRANSPARENT)
            onMotion = motionCallback
        }

        // Window mechanics mirror the working motion probe (`MotionProbeAppOverlay`):
        // same size class, same PixelFormat, same flags. Only the visual is
        // different — fully transparent background instead of the probe's red
        // square. Why this matters:
        //  - `PixelFormat.TRANSPARENT` tells the platform "no surface contents"
        //    and the input dispatcher can skip the window when assigning focus,
        //    silently dropping motion events. `TRANSLUCENT` keeps the surface
        //    real but alpha-blended — empty draw + zero alpha = invisible.
        //  - Sub-pixel-sized windows (1×1) can be treated as not-a-real-window
        //    by some OEM dispatchers; the probe-sized footprint (240×110dp)
        //    is known good. With `FLAG_NOT_TOUCH_MODAL` touches outside the
        //    bounds still pass through, and the (top-left) anchor means even
        //    touches inside the 240×110dp region land on the status-bar/notch
        //    safe area rather than stealing taps the user actually wanted.
        //
        // Deliberately NO `FLAG_NOT_FOCUSABLE` — that flag would silence
        // motion events on every Android version we target.
        val density = context.resources.displayMetrics.density
        val flags = WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
        val params = WindowManager.LayoutParams(
            (240 * density).toInt(),
            (110 * density).toInt(),
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            flags,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 0
            y = 0
        }

        try {
            wm.addView(view, params)
            attachedView = view
            attachedParams = params
            // Borrow the keyboard overlay's foreground-service for process
            // priority. While Mapo's activity is backgrounded (the normal case
            // during gameplay) Android 12+ will otherwise drop our process
            // priority and break overlay event delivery within seconds.
            // Side effect: the keyboard FGS notification appears even when the
            // keyboard isn't mounted. Acceptable cost for analog input.
            context.startForegroundService(Intent(context, KeyboardOverlayService::class.java))
            _isAttached.value = true
            Log.i(TAG, "attached — focused TYPE_APPLICATION_OVERLAY 1×1 active")
        } catch (e: Exception) {
            Log.e(TAG, "attach failed", e)
        }
    }

    private fun detachInternal(view: MotionCaptureView) {
        attachedView = null
        attachedParams = null
        view.onMotion = null
        try {
            (context.getSystemService(Context.WINDOW_SERVICE) as? WindowManager)?.removeView(view)
        } catch (e: Exception) {
            Log.w(TAG, "detach failed (already gone?)", e)
        }
        // Intentionally NOT stopping the keyboard FGS — KeyboardOverlayManager
        // owns its lifecycle. If the keyboard isn't also mounted, the FGS
        // keeps running until the process exits.
        _isAttached.value = false
        Log.i(TAG, "detached")
    }

    /**
     * Brick 5 follow-up: relinquish key focus for the duration of [block].
     *
     * **Why.** The motion-capture overlay holds focus on its display so it
     * can receive `MotionEvent`s from the gamepad — that's the only way to
     * read joystick / trigger axes on Android 13 (no `AccessibilityService
     * .onMotionEvent` until API 34, no `InputMonitor` without signature-
     * privileged permission). But any `KeyEvent` Mapo injects via
     * `InputManager.injectInputEvent` routes to the focused window on the
     * target display — i.e. lands in the overlay, not in the foreground
     * game. Briefly removing the overlay from `WindowManager` forces focus
     * onto the next-lowest focusable window (the game); the inject lands
     * there; we then re-add the overlay to resume motion capture.
     *
     * **Why removeView / addView and not a flag toggle.** Approach C
     * (toggle `FLAG_NOT_FOCUSABLE` via `updateViewLayout`) was tried
     * 2026-05-21 — failed on Thor. `updateViewLayout` queues the layout
     * update; focus reassignment is dispatched asynchronously and the
     * inject ran before it propagated. `removeView` is synchronous w.r.t.
     * focus reassignment: the window's surface is destroyed in the call,
     * so WindowManager has to reassign focus before returning. Validated
     * empirically by user device-test feedback in that session.
     *
     * **Thread model.** `WindowManager` operations require the thread
     * that originally added the view (the main thread). Callers from
     * coroutine dispatchers (e.g. hold-to-repeat timers) post-and-wait
     * via a [CountDownLatch]; callers already on main run inline.
     *
     * No-op fast path: when the overlay isn't currently attached there's
     * no focus to release — [block] runs directly.
     */
    fun withFocusReleasedForInject(block: () -> Unit) {
        val view = attachedView
        val params = attachedParams
        if (view == null || params == null) {
            block()
            return
        }
        if (Looper.myLooper() == Looper.getMainLooper()) {
            doDetachInjectReattach(view, params, block)
        } else {
            // Off main: post to main and synchronously wait. The latch
            // dance is required because WindowManager operations must run
            // on the view-creating thread. The added thread-hop is paid
            // only on coroutine-driven injects (e.g. hold-to-repeat timers).
            val latch = CountDownLatch(1)
            var thrown: Throwable? = null
            mainHandler.post {
                try {
                    doDetachInjectReattach(view, params, block)
                } catch (t: Throwable) {
                    thrown = t
                } finally {
                    latch.countDown()
                }
            }
            latch.await()
            thrown?.let { throw it }
        }
    }

    private fun doDetachInjectReattach(
        view: View,
        params: WindowManager.LayoutParams,
        block: () -> Unit,
    ) {
        val wm = context.getSystemService(Context.WINDOW_SERVICE) as? WindowManager
        if (wm == null) {
            block()
            return
        }
        // We deliberately don't go through detachInternal()/attachInternal()
        // here — those mutate _isAttached state and null out the motion
        // callback. From the outside the overlay should remain "attached"
        // across this transient gymnastics; only the WindowManager's view
        // tree changes for the few ms of the inject.
        val removed = try {
            wm.removeView(view)
            true
        } catch (e: Exception) {
            // Race: detach() ran on another caller between our null check
            // and the removeView call. Proceed without the detach — the
            // inject path is still safe to run, it just won't bypass focus.
            Log.w(TAG, "focus-release: removeView failed — inject without detach", e)
            false
        }
        try {
            if (removed) Log.d(TAG, "focus-release: overlay detached for inject")
            block()
        } finally {
            if (removed) {
                try {
                    wm.addView(view, params)
                    Log.d(TAG, "focus-release: overlay reattached")
                } catch (e: Exception) {
                    // Re-add failed (window destroyed under us, OOM, etc.).
                    // The overlay is now genuinely detached — sync our cached
                    // state so a subsequent attach() rebuilds fresh.
                    Log.e(TAG, "focus-release: reattach failed — clearing attached state", e)
                    attachedView = null
                    attachedParams = null
                    _isAttached.value = false
                }
            }
        }
    }

    private fun runOnMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block() else mainHandler.post(block)
    }

    companion object {
        private const val TAG = "MotionCaptureOverlay"
    }
}
