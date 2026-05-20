package com.mapo.service.input

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.WindowManager
import android.widget.TextView
import com.mapo.service.overlay.keyboard.KeyboardOverlayService
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Debug probe — focused `TYPE_APPLICATION_OVERLAY` for characterizing side effects.
 *
 * The earlier motion-capture experiment used `TYPE_ACCESSIBILITY_OVERLAY` focusable
 * and broke GameNative's cursor (plus IME, app-switcher gesture, …). The keyboard
 * overlay we built later uses `TYPE_APPLICATION_OVERLAY` non-focusable — different
 * window type, but the focusable variant of THAT type was never tested. This probe
 * fills that cell in the matrix.
 *
 * What to look at while the probe is attached:
 *  - Live axis text inside the red square — confirms motion events arrive.
 *  - GameNative cursor visibility — was the dealbreaker last time.
 *  - IME appearance in another app, system app-switcher gesture, back button.
 *
 * Not shipping infrastructure: if the side effects are bounded enough that this
 * window type works as a motion-capture surface, the production design lives
 * elsewhere; if they're not, this file gets deleted.
 */
@Singleton
class MotionProbeAppOverlay @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    private val mainHandler = Handler(Looper.getMainLooper())
    private var attachedView: TextView? = null
    private val _active = MutableStateFlow(false)
    val active: StateFlow<Boolean> = _active.asStateFlow()

    fun canShow(): Boolean = Settings.canDrawOverlays(context)

    fun toggle() {
        runOnMain {
            if (attachedView == null) attach() else detach()
        }
    }

    private fun attach() {
        if (!canShow()) {
            Log.w(TAG, "attach skipped: SYSTEM_ALERT_WINDOW not granted")
            return
        }
        val wm = context.getSystemService(Context.WINDOW_SERVICE) as? WindowManager ?: return

        val view = ProbeView(context).apply {
            setBackgroundColor(Color.argb(220, 220, 30, 30))
            setTextColor(Color.WHITE)
            textSize = 12f
            val pad = (8 * context.resources.displayMetrics.density).toInt()
            setPadding(pad, pad, pad, pad)
            text = "PROBE active\n(awaiting motion)"
        }

        val density = context.resources.displayMetrics.density
        // Focusable on purpose: omitting FLAG_NOT_FOCUSABLE is the entire point.
        // FLAG_NOT_TOUCH_MODAL keeps touches outside the square passing through to
        // whatever's underneath, so the user can still drive GameNative / Mapo's
        // drawer with touch while the probe is mounted.
        val flags = WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
        val params = WindowManager.LayoutParams(
            (240 * density).toInt(),
            (110 * density).toInt(),
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            flags,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.END
            x = (12 * density).toInt()
            y = (12 * density).toInt()
        }

        try {
            wm.addView(view, params)
            attachedView = view
            // Borrow the keyboard overlay's FGS so the probe survives Mapo's
            // backgrounding (Android 12+ kills cached processes within seconds).
            // Side effect: the keyboard FGS notification appears even if the
            // keyboard isn't mounted. Fine for a probe.
            context.startForegroundService(Intent(context, KeyboardOverlayService::class.java))
            _active.value = true
            Log.i(TAG, "attached — focused TYPE_APPLICATION_OVERLAY active")
        } catch (e: Exception) {
            Log.e(TAG, "attach failed", e)
        }
    }

    private fun detach() {
        val view = attachedView ?: return
        attachedView = null
        try {
            (context.getSystemService(Context.WINDOW_SERVICE) as? WindowManager)?.removeView(view)
        } catch (e: Exception) {
            Log.w(TAG, "detach failed (already gone?)", e)
        }
        // Intentionally NOT stopping the FGS here — KeyboardOverlayManager owns its
        // lifecycle. If the keyboard isn't also mounted, the FGS keeps running until
        // the process exits. Cheap; acceptable for a one-shot probe.
        _active.value = false
        Log.i(TAG, "detached")
    }

    private fun runOnMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block() else mainHandler.post(block)
    }

    private class ProbeView(context: Context) : TextView(context) {
        override fun onGenericMotionEvent(event: MotionEvent): Boolean {
            val readings = MotionEventNormalizer.extract(event, SystemClock.uptimeMillis())
            text = buildString {
                append("PROBE active\n")
                readings.joinTo(this, separator = "\n") { ev ->
                    "${ev.source}: ${"%+.2f".format(ev.x)}, ${"%+.2f".format(ev.y)}"
                }
            }
            Log.i(TAG, MotionCaptureOverlay.describe("AppOverlayProbe", event))
            return false
        }
    }

    companion object {
        private const val TAG = "MotionProbeApp"
    }
}
