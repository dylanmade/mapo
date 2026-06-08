package com.mapo.service.overlay.element

import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.graphics.Point
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.Display
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.ComposeView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.mapo.data.model.OverlayElement
import com.mapo.data.model.OverlayGesture
import com.mapo.service.overlay.OverlayLifecycleOwner
import com.mapo.service.overlay.keyboard.KeyboardOverlayService
import com.mapo.ui.screen.overlay.OverlayElementButton
import com.mapo.ui.theme.MapoTheme
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.roundToInt

/**
 * Renders the rebuilt overlay as **one `WindowManager` window per button** (see
 * `OVERLAY_REBUILD_PLAN.md`, Brick B). This is the conventional mobile game-overlay
 * pattern that replaces the single-window + `@hide` touchable-region hack: each window
 * is sized exactly to its element's bounds, so the whole window is touchable and *every*
 * gap between buttons is passthrough because no window exists there.
 *
 * **Flag matrix (per window, identical to the keyboard overlay's load-bearing set).**
 * `TYPE_APPLICATION_OVERLAY` + `FLAG_NOT_FOCUSABLE` (key/gamepad events route past us to
 * the foreground game — see feedback_no_flag_not_focusable_on_activity) + `FLAG_NOT_TOUCH_MODAL`
 * + `FLAG_LAYOUT_IN_SCREEN` + `FLAG_LAYOUT_NO_LIMITS` (position freely in screen coords).
 *
 * Element rects are normalized display fractions; we multiply by live display size at
 * attach/update time. Orientation-change re-layout is a follow-up (re-render on config
 * change); MVP positions against the current display bounds.
 *
 * **FGS.** Reuses [KeyboardOverlayService] to hold process priority while any window is
 * up. NOTE: the keyboard overlay reuses the same service; if both overlay systems are
 * ever mounted at once, the last one to detach stops the shared FGS for both. They don't
 * coexist in the MVP; a ref-counted FGS owner is the follow-up if they ever do.
 */
@Singleton
class OverlayElementWindowManager @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    private val mainHandler = Handler(Looper.getMainLooper())
    private val attached = mutableMapOf<Long, AttachedElement>()

    fun canShow(): Boolean = Settings.canDrawOverlays(context)

    fun activeCount(): Int = attached.size

    /**
     * Reconcile the live windows to [elements]: detach removed ids, attach new ones,
     * and update position / content for ids that persist. [onGesture] fires on the main
     * thread with the latest element + which gesture (tap / double-tap / hold) occurred.
     */
    fun render(
        elements: List<OverlayElement>,
        displayId: Int = Display.DEFAULT_DISPLAY,
        onGesture: (OverlayElement, OverlayGesture) -> Unit,
    ) {
        runOnMain {
            if (!canShow()) {
                Log.w(TAG, "render skipped: SYSTEM_ALERT_WINDOW not granted")
                return@runOnMain
            }
            val displayContext = displayContextOrFallback(displayId)
            val windowManager = displayContext.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            val size = displaySizePx(windowManager)

            val desiredIds = elements.map { it.id }.toSet()
            // Detach removed.
            attached.keys.filter { it !in desiredIds }.forEach { detachInternal(it) }

            val wasEmpty = attached.isEmpty()
            elements.forEach { element ->
                val existing = attached[element.id]
                if (existing == null) {
                    attach(element, displayContext, windowManager, size, displayId, onGesture)
                } else {
                    // Update content (label / target) in place; reposition if the rect moved.
                    existing.state.value = element
                    val params = layoutParamsFor(element, size)
                    if (params.x != existing.params.x || params.y != existing.params.y ||
                        params.width != existing.params.width || params.height != existing.params.height
                    ) {
                        existing.params.apply {
                            x = params.x; y = params.y; width = params.width; height = params.height
                        }
                        runCatching { windowManager.updateViewLayout(existing.view, existing.params) }
                            .onFailure { Log.w(TAG, "updateViewLayout(${element.id}) failed", it) }
                    }
                }
            }
            if (wasEmpty && attached.isNotEmpty()) startService()
            if (attached.isEmpty()) stopService()
            Log.i(TAG, "render → ${attached.size} window(s) on display $displayId")
        }
    }

    fun detachAll() {
        runOnMain { attached.keys.toList().forEach { detachInternal(it) } }
    }

    // ── internals ─────────────────────────────────────────────────────────────

    private fun attach(
        element: OverlayElement,
        displayContext: Context,
        windowManager: WindowManager,
        size: Point,
        displayId: Int,
        onGesture: (OverlayElement, OverlayGesture) -> Unit,
    ) {
        val owner = OverlayLifecycleOwner()
        val state: MutableState<OverlayElement> = mutableStateOf(element)
        val composeView = ComposeView(displayContext).apply {
            setViewTreeLifecycleOwner(owner)
            setViewTreeViewModelStoreOwner(owner)
            setViewTreeSavedStateRegistryOwner(owner)
            defaultFocusHighlightEnabled = false
            setContent {
                MapoTheme {
                    OverlayElementButton(
                        element = state.value,
                        onTap = { onGesture(state.value, OverlayGesture.TAP) },
                        onDoubleTap = { onGesture(state.value, OverlayGesture.DOUBLE_TAP) },
                        onHold = { onGesture(state.value, OverlayGesture.HOLD) },
                    )
                }
            }
        }
        owner.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
        owner.handleLifecycleEvent(Lifecycle.Event.ON_START)
        owner.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)

        val params = layoutParamsFor(element, size)
        try {
            windowManager.addView(composeView, params)
        } catch (e: Exception) {
            Log.e(TAG, "addView(${element.id}) failed", e)
            owner.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
            return
        }
        attached[element.id] = AttachedElement(composeView, owner, windowManager, params, state, displayId)
    }

    private fun detachInternal(id: Long) {
        val entry = attached.remove(id) ?: return
        runCatching { entry.windowManager.removeViewImmediate(entry.view) }
            .onFailure { Log.w(TAG, "removeView($id) failed (already gone?)", it) }
        entry.owner.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE)
        entry.owner.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
        entry.owner.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
    }

    private fun layoutParamsFor(element: OverlayElement, size: Point): WindowManager.LayoutParams {
        val flags = WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
        return WindowManager.LayoutParams(
            (element.width * size.x).roundToInt().coerceAtLeast(1),
            (element.height * size.y).roundToInt().coerceAtLeast(1),
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            flags,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = (element.x * size.x).roundToInt()
            y = (element.y * size.y).roundToInt()
        }
    }

    private fun displaySizePx(windowManager: WindowManager): Point {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val bounds = windowManager.maximumWindowMetrics.bounds
            Point(bounds.width(), bounds.height())
        } else {
            val p = Point()
            @Suppress("DEPRECATION")
            windowManager.defaultDisplay.getRealSize(p)
            p
        }
    }

    private fun displayContextOrFallback(displayId: Int): Context {
        if (displayId == Display.DEFAULT_DISPLAY) return context
        val displayManager = context.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
        val display = displayManager.getDisplay(displayId) ?: run {
            Log.w(TAG, "display $displayId not found; falling back to default")
            return context
        }
        return context.createDisplayContext(display)
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

    private data class AttachedElement(
        val view: View,
        val owner: OverlayLifecycleOwner,
        val windowManager: WindowManager,
        val params: WindowManager.LayoutParams,
        val state: MutableState<OverlayElement>,
        val displayId: Int,
    )

    companion object {
        private const val TAG = "OverlayElementMgr"
    }
}
