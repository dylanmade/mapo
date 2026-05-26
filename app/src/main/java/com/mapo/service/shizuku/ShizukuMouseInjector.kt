package com.mapo.service.shizuku

import android.util.Log
import javax.inject.Inject
import javax.inject.Singleton

/**
 * **Spike (post-Brick-J).** Single decision point for "should this cursor
 * motion go through Shizuku's `SOURCE_MOUSE` inject path instead of the
 * AccessibilityService's `dispatchGesture` synthetic-touch path?"
 *
 * Modeled on [ShizukuKeyInjector]. Distinct class because the gate predicates
 * differ — key inject is gated on `shizukuModeActive` (analog mode in scope
 * for the foreground app); mouse inject is gated on whether the *caller* is
 * in a continuous-cursor session. The AccessibilityService passes that flag
 * in directly since only the service knows.
 *
 * **Why this path is interesting** (architectural note for future-me):
 * synthetic touch routes through the OS touch dispatcher, which runs gesture
 * detectors for notification pull-down, back gesture, and home pill before
 * the touch reaches the foreground app. Synthetic `SOURCE_MOUSE` motion
 * routes through the pointer dispatcher and skips those detectors — same
 * path a real USB mouse uses. Bypassing the touch pipeline removes the
 * entire "where do the bounds margins go" class of problems and the
 * no-teleport vs relative-emulator-cursor tension.
 *
 * Caller still tracks absolute cursor position (we pass both absolute coords
 * and relative deltas to the service for robustness — apps that use absolute
 * positions read the X/Y, apps that read raw deltas use AXIS_RELATIVE_X/Y).
 */
@Singleton
class ShizukuMouseInjector @Inject constructor(
    private val shizukuConnection: ShizukuConnection,
) {

    /**
     * Try to route a cursor delta through the Shizuku UserService as a
     * `SOURCE_MOUSE` motion event.
     *
     * Returns `true` when the Shizuku path was taken (caller does NOT fall
     * back to `dispatchGesture` — taking the touch path mid-cursor-session
     * would mix two semantically different output streams).
     *
     * Returns `false` when Shizuku isn't ready or the binder threw. Caller
     * falls back to its existing touch path.
     */
    fun tryInject(absX: Float, absY: Float, dx: Float, dy: Float, displayId: Int): Boolean {
        if (!shizukuConnection.isReadyFlow.value) return false
        val service = shizukuConnection.service.value ?: return false
        return try {
            val ok = service.injectMouseMotion(absX, absY, dx, dy, displayId)
            if (!ok) Log.d(TAG, "service.injectMouseMotion returned false abs=($absX,$absY) rel=($dx,$dy)")
            true
        } catch (t: Throwable) {
            Log.w(TAG, "Shizuku injectMouseMotion threw — falling back to dispatchGesture", t)
            false
        }
    }

    /**
     * Send a complete click (press + release) for the given button. `btnCode`
     * is one of the `LinuxInputConstants.BTN_*` ints. Returns true iff the
     * Shizuku path was taken; false means caller should fall back to
     * dispatchGesture-based touch-tap synthesis.
     */
    fun tryClick(btnCode: Int): Boolean {
        if (!shizukuConnection.isReadyFlow.value) return false
        val service = shizukuConnection.service.value ?: return false
        return try {
            // Press + immediate release. Apps tolerate back-to-back btn-down/
            // btn-up; the kernel batches the SYN_REPORTs and Android's
            // InputDispatcher delivers them in order. No artificial delay
            // needed (and adding one would just hurt latency).
            service.injectMouseButton(btnCode, true)
            service.injectMouseButton(btnCode, false)
            true
        } catch (t: Throwable) {
            Log.w(TAG, "Shizuku injectMouseButton threw — falling back", t)
            false
        }
    }

    /**
     * Hold or release a button — for future drag-and-drop / hold-to-click
     * gestures. Caller is responsible for pairing every `true` with a
     * matching `false`.
     */
    fun trySetButton(btnCode: Int, pressed: Boolean): Boolean {
        if (!shizukuConnection.isReadyFlow.value) return false
        val service = shizukuConnection.service.value ?: return false
        return try {
            service.injectMouseButton(btnCode, pressed)
            true
        } catch (t: Throwable) {
            Log.w(TAG, "Shizuku injectMouseButton threw", t)
            false
        }
    }

    /**
     * Scroll-wheel notch counts. `dy > 0` = scroll up (content moves down,
     * standard convention). `dx` is horizontal scroll, supported but rare.
     */
    fun tryScroll(dx: Int, dy: Int): Boolean {
        if (!shizukuConnection.isReadyFlow.value) return false
        val service = shizukuConnection.service.value ?: return false
        return try {
            service.injectMouseScroll(dx, dy)
            true
        } catch (t: Throwable) {
            Log.w(TAG, "Shizuku injectMouseScroll threw", t)
            false
        }
    }

    companion object {
        private const val TAG = "ShizukuMouseInjector"
    }
}
