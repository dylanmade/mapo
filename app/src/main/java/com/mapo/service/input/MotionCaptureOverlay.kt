package com.mapo.service.input

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.graphics.PixelFormat
import android.os.SystemClock
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager

/**
 * **Motion-capture overlay — viable foundation, currently tabled.**
 *
 * Status as of 2026-05-16: motion capture via accessibility overlay **works** on
 * stock Android, but the configuration that captures motion (a focusable
 * `TYPE_ACCESSIBILITY_OVERLAY`) also wins global input focus competition, which
 * breaks system behaviors that key off "the focused window" (IME placement, back
 * gesture, cursor rendering, Mapo's own onKeyEvent routing). Visual properties
 * (1×1 size, transparency, `FLAG_NOT_TOUCHABLE`) don't help — focus is logical,
 * not visual.
 *
 * Two configurations were tested:
 *  1. **Non-focusable** (current state): silent, no motion events, but no side
 *     effects on the system either.
 *  2. **Focusable**: motion events flowed, the foreground game continued
 *     rendering — but with system-wide focus disruption listed above.
 *
 * Tabled for a wider refactor that addresses the side effects alongside the
 * capture path. Until then this class stays attached (silent / harmless) as the
 * call site that future motion-capture work plugs into. The supporting types
 * (`AnalogEvent`, `MotionEventNormalizer`, `InputEvaluator.handleMotion`) all
 * stay in place for the same reason — they're the right data shapes for analog
 * input on stock Android. See `project_motion_capture_via_focusable_overlay.md`.
 *
 * Phase 6 ships digital modes only until this is revisited.
 */
class MotionCaptureOverlay(
    private val service: AccessibilityService,
    private val onMotion: (MotionEvent) -> Unit,
) {

    private var attachedView: View? = null

    fun attach() {
        if (attachedView != null) return

        val wm = service.getSystemService(Context.WINDOW_SERVICE) as? WindowManager
        if (wm == null) {
            Log.w(TAG, "attach: no WindowManager service available — probe inactive")
            return
        }

        val view = ProbeView(service).apply {
            // Visual non-presence: 1×1 transparent surface anchored top-left. Compose
            // doesn't need to render anything here — we exist for the input-event
            // routing only.
            setBackgroundColor(android.graphics.Color.TRANSPARENT)
        }
        view.onMotion = onMotion

        // Layout (non-focusable — known to be silent for motion events). This is the
        // safe baseline; the class is kept attached so that:
        //  - Any future motion-capture mechanism can wire in here.
        //  - The non-focusable overlay does NOT disrupt system focus / IME / gesture
        //    routing the way the focusable variant did (see class doc).
        val params = WindowManager.LayoutParams(
            1, 1,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 0
            y = 0
        }

        try {
            wm.addView(view, params)
            attachedView = view
            Log.i(TAG, "attached — motion-event capture active")
        } catch (e: Exception) {
            // Most likely cause: TYPE_ACCESSIBILITY_OVERLAY rejected by the platform. If we
            // see this in logcat at attach time, the alternative is the same overlay type
            // backed by SYSTEM_ALERT_WINDOW — a follow-up if needed.
            Log.e(TAG, "attach failed", e)
        }
    }

    fun detach() {
        val view = attachedView ?: return
        attachedView = null
        try {
            (service.getSystemService(Context.WINDOW_SERVICE) as? WindowManager)?.removeView(view)
            Log.i(TAG, "detached")
        } catch (e: Exception) {
            Log.w(TAG, "detach failed (already gone?)", e)
        }
    }

    /**
     * Hidden 1×1 view whose only job is to receive `onGenericMotionEvent`. Returns
     * false from the handler so events still propagate through the normal Android
     * input pipeline.
     */
    private class ProbeView(context: Context) : View(context) {
        var onMotion: ((MotionEvent) -> Unit)? = null

        override fun onGenericMotionEvent(event: MotionEvent): Boolean {
            onMotion?.invoke(event)
            return false
        }
    }

    companion object {
        const val TAG = "MotionProbe"

        /**
         * Pretty-print a [MotionEvent]'s normalized analog state for the user-facing
         * probe log. Shared between the service-side overlay path and the
         * activity-side positive control so output formats stay comparable.
         */
        fun describe(source: String, event: MotionEvent): String {
            val readings = MotionEventNormalizer.extract(event, SystemClock.uptimeMillis())
            return buildString {
                append("[$source] action=").append(event.actionMasked)
                append(" device=").append(event.device?.name ?: "?")
                append(" axes=")
                readings.joinTo(this, separator = " ") { ev ->
                    "${ev.source}(${"%.2f".format(ev.x)},${"%.2f".format(ev.y)})"
                }
            }
        }
    }
}
