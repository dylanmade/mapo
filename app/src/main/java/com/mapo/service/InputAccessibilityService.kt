package com.mapo.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.accessibilityservice.GestureDescription
import android.annotation.SuppressLint
import android.graphics.Path
import android.os.SystemClock
import android.util.Log
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
import com.mapo.data.model.DeviceButton
import com.mapo.data.model.RemapTarget
import com.mapo.service.foreground.ForegroundAppMonitor
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class InputAccessibilityService : AccessibilityService() {

    @Inject lateinit var foregroundAppMonitor: ForegroundAppMonitor

    companion object {
        @Volatile var instance: InputAccessibilityService? = null
            private set

        // Active profile's remap mappings — updated by MainViewModel.
        @Volatile var currentMappings: Map<DeviceButton, RemapTarget> = emptyMap()

        // Remapping on/off toggle — updated by MainViewModel.
        @Volatile var remapEnabled: Boolean = false

        // True while a focusable Mapo overlay is on screen (e.g. the create-profile
        // prompt). When set, gamepad A becomes ENTER and B becomes BACK so the user
        // can drive the overlay from a controller, regardless of remap state.
        @Volatile var overlayFocused: Boolean = false

        // Physical gamepad keycodes → DeviceButton enum
        private val GAMEPAD_KEYCODE_MAP: Map<Int, DeviceButton> = mapOf(
            KeyEvent.KEYCODE_BUTTON_A      to DeviceButton.BUTTON_A,
            KeyEvent.KEYCODE_BUTTON_B      to DeviceButton.BUTTON_B,
            KeyEvent.KEYCODE_BUTTON_X      to DeviceButton.BUTTON_X,
            KeyEvent.KEYCODE_BUTTON_Y      to DeviceButton.BUTTON_Y,
            KeyEvent.KEYCODE_BUTTON_L1     to DeviceButton.BUTTON_L1,
            KeyEvent.KEYCODE_BUTTON_R1     to DeviceButton.BUTTON_R1,
            KeyEvent.KEYCODE_BUTTON_L2     to DeviceButton.AXIS_L2,
            KeyEvent.KEYCODE_BUTTON_R2     to DeviceButton.AXIS_R2,
            KeyEvent.KEYCODE_BUTTON_THUMBL to DeviceButton.BUTTON_THUMBL,
            KeyEvent.KEYCODE_BUTTON_THUMBR to DeviceButton.BUTTON_THUMBR,
            KeyEvent.KEYCODE_DPAD_UP       to DeviceButton.DPAD_UP,
            KeyEvent.KEYCODE_DPAD_DOWN     to DeviceButton.DPAD_DOWN,
            KeyEvent.KEYCODE_DPAD_LEFT     to DeviceButton.DPAD_LEFT,
            KeyEvent.KEYCODE_DPAD_RIGHT    to DeviceButton.DPAD_RIGHT,
            KeyEvent.KEYCODE_BUTTON_START  to DeviceButton.BUTTON_START,
            KeyEvent.KEYCODE_BUTTON_SELECT to DeviceButton.BUTTON_SELECT,
        )

        // Reverse map — DeviceButton → keycode, for gamepad→gamepad remapping
        private val DEVICE_BUTTON_TO_KEYCODE: Map<DeviceButton, Int> =
            GAMEPAD_KEYCODE_MAP.entries.associate { (k, v) -> v to k }

        // Maps our button code strings to Android KeyEvent keycodes.
        private val CODE_ALIASES = mapOf("BACKSPACE" to KeyEvent.KEYCODE_DEL)
        private val MOUSE_CODES = setOf(
            "MOUSE_LEFT", "MOUSE_MIDDLE", "MOUSE_RIGHT",
            "SCROLL_UP", "SCROLL_DOWN", "MOUSE_BACK", "MOUSE_FORWARD"
        )

        fun resolveKeyCode(code: String): Int? {
            CODE_ALIASES[code]?.let { return it }
            val kc = KeyEvent.keyCodeFromString("KEYCODE_$code")
            return if (kc != KeyEvent.KEYCODE_UNKNOWN) kc else null
        }

        private const val TAG = "InputAccessibilityService"
    }

    // ── Cursor tracking ───────────────────────────────────────────────────────

    // Virtual gesture anchor — NOT the visible system cursor position.
    // Gesture paths must stay within the safe zone to avoid system gesture zones
    // (back gesture on left/right edges, home gesture at bottom, notifications at top).
    private var cursorX = 540f
    private var cursorY = 960f
    private var safeL = 100f
    private var safeT = 80f
    private var safeR = 980f
    private var safeB = 1670f


    // ── Lifecycle ─────────────────────────────────────────────────────────────

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        val info = serviceInfo
        info.flags = info.flags or AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS
        setServiceInfo(info)
        val (w, h) = primaryDisplaySize()
        // Keep gesture paths well away from system gesture zones.
        // Left/right: ~100px avoids the back-gesture swipe zone.
        // Top: ~80px avoids notification pull-down.
        // Bottom: ~250px avoids home/recents swipe zone.
        safeL = 100f;       safeT = 80f
        safeR = w - 100f;   safeB = h - 250f
        cursorX = w / 2f
        cursorY = h / 2f
        Log.i(TAG, "Service connected — display=${w}x${h} safeZone=[$safeL,$safeT,$safeR,$safeB]")
    }

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        instance = null
        return super.onUnbind(intent)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null || event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val pkg = event.packageName?.toString() ?: return
        Log.d(TAG, "window state changed → pkg=$pkg className=${event.className}")
        foregroundAppMonitor.reportForegroundPackage(pkg)
    }

    override fun onInterrupt() = Unit

    // ── Physical button interception (remap) ──────────────────────────────────

    override fun onKeyEvent(event: KeyEvent): Boolean {
        if (overlayFocused) {
            // Translate gamepad A/B into ENTER/BACK so DPAD-navigated overlay buttons
            // can be activated. DPAD events pass through unchanged so Compose's focus
            // traversal handles left/right/up/down naturally.
            val isDown = event.action == KeyEvent.ACTION_DOWN
            return when (event.keyCode) {
                KeyEvent.KEYCODE_BUTTON_A -> {
                    if (isDown) injectKeyDown(KeyEvent.KEYCODE_ENTER) else injectKeyUp(KeyEvent.KEYCODE_ENTER)
                    true
                }
                KeyEvent.KEYCODE_BUTTON_B -> {
                    if (isDown) injectKeyDown(KeyEvent.KEYCODE_BACK) else injectKeyUp(KeyEvent.KEYCODE_BACK)
                    true
                }
                else -> false
            }
        }
        if (!remapEnabled) return false
        Log.d(TAG, "onKeyEvent: keyCode=${event.keyCode} (${KeyEvent.keyCodeToString(event.keyCode)}) action=${event.action} mappings=${currentMappings.size}")

        val deviceButton = GAMEPAD_KEYCODE_MAP[event.keyCode] ?: return false
        val target = currentMappings[deviceButton]

        // No mapping or explicitly Unbound → pass through to game
        if (target == null || target is RemapTarget.Unbound) return false

        Log.d(TAG, "Remapping $deviceButton → $target (isDown=${event.action == KeyEvent.ACTION_DOWN})")
        val isDown = event.action == KeyEvent.ACTION_DOWN
        dispatchRemapTarget(target, isDown)
        return true // consume the physical event
    }

    private fun dispatchRemapTarget(target: RemapTarget, isDown: Boolean) {
        when (target) {
            is RemapTarget.Keyboard -> {
                val kc = resolveKeyCode(target.code) ?: run {
                    Log.w(TAG, "Remap: unknown keyboard code ${target.code}")
                    return
                }
                if (isDown) injectKeyDown(kc) else injectKeyUp(kc)
            }
            is RemapTarget.Gamepad -> {
                val btn = DeviceButton.entries.firstOrNull { it.name == target.button } ?: return
                val kc = DEVICE_BUTTON_TO_KEYCODE[btn] ?: return
                if (isDown) injectKeyDown(kc) else injectKeyUp(kc)
            }
            is RemapTarget.Mouse -> {
                // Mouse targets are inherently click-on-press (no separate down/up at the
                // accessibility-gesture layer). Fire on the down edge and ignore the up.
                if (isDown) dispatchTargetAsClick(target)
            }
            is RemapTarget.Unbound -> { /* unreachable */ }
        }
    }

    // ── Virtual keyboard injection (called from ViewModel) ────────────────────

    /** Inject a full DOWN+UP cycle for the given button code string. */
    fun injectKey(code: String): Boolean {
        if (code in MOUSE_CODES) return false
        val keyCode = resolveKeyCode(code) ?: run {
            Log.w(TAG, "Unknown key code: $code")
            return false
        }
        injectKeyDown(keyCode)
        injectKeyUp(keyCode)
        return true
    }

    /**
     * Dispatch a RemapTarget as a one-shot click — full down/up cycle for keyboard and gamepad
     * targets, the appropriate single gesture for mouse targets. Used by trackpad gesture
     * remapping and any other path that has a RemapTarget and wants to fire it as a tap.
     */
    fun dispatchTargetAsClick(target: RemapTarget) {
        Log.d(TAG, "dispatchTargetAsClick target=$target")
        when (target) {
            is RemapTarget.Unbound -> { /* no-op */ }
            is RemapTarget.Keyboard -> {
                val kc = resolveKeyCode(target.code) ?: run {
                    Log.w(TAG, "dispatchTargetAsClick: unknown keyboard code ${target.code}")
                    return
                }
                injectKeyDown(kc); injectKeyUp(kc)
            }
            is RemapTarget.Gamepad -> {
                val btn = DeviceButton.entries.firstOrNull { it.name == target.button } ?: return
                val kc = DEVICE_BUTTON_TO_KEYCODE[btn] ?: return
                injectKeyDown(kc); injectKeyUp(kc)
            }
            is RemapTarget.Mouse -> when (target.code) {
                "MOUSE_LEFT"    -> injectMouseTap()
                "MOUSE_RIGHT"   -> injectMouseRightClick()
                "MOUSE_MIDDLE"  -> injectMouseMiddleClick()
                "MOUSE_BACK"    -> injectMouseBackClick()
                "MOUSE_FORWARD" -> injectMouseForwardClick()
                "SCROLL_UP"     -> injectMouseScroll(0f, 1f)
                "SCROLL_DOWN"   -> injectMouseScroll(0f, -1f)
                else -> Log.w(TAG, "dispatchTargetAsClick: unknown mouse code ${target.code}")
            }
        }
    }

    // ── Mouse gesture injection (called from ViewModel for trackpad) ──────────

    private var isDragging = false
    private var currentStroke: GestureDescription.StrokeDescription? = null
    private var segmentActive = false
    private var segEndX = 540f
    private var segEndY = 960f

    fun startMouseDrag() {
        Log.d(TAG, "startMouseDrag — cursor reset to center")
        isDragging = true
        segmentActive = false
        currentStroke = null
        // Reset to safe-zone center on every new finger-touch so the virtual anchor
        // always has maximum headroom before approaching any system gesture zone.
        cursorX = (safeL + safeR) / 2f
        cursorY = (safeT + safeB) / 2f
        segEndX = cursorX
        segEndY = cursorY
    }

    fun injectMouseMove(dx: Float, dy: Float) {
        cursorX = (cursorX + dx).coerceIn(safeL, safeR)
        cursorY = (cursorY + dy).coerceIn(safeT, safeB)
        if (!segmentActive) dispatchMoveSegment(willContinue = true)
    }

    fun endMouseDrag() {
        isDragging = false
        if (!segmentActive && currentStroke != null) dispatchMoveSegment(willContinue = false)
    }

    private fun dispatchMoveSegment(willContinue: Boolean) {
        val fromX = segEndX; val fromY = segEndY
        val toX = cursorX;   val toY = cursorY
        val path = Path().apply {
            moveTo(fromX, fromY)
            if (fromX != toX || fromY != toY) lineTo(toX, toY)
        }
        val stroke = if (currentStroke == null) {
            GestureDescription.StrokeDescription(path, 0L, 1L, willContinue)
        } else {
            currentStroke!!.continueStroke(path, 0L, 1L, willContinue)
        }
        currentStroke = if (willContinue) stroke else null
        val ok = dispatchGesture(
            GestureDescription.Builder().addStroke(stroke).build(),
            object : GestureResultCallback() {
                override fun onCompleted(g: GestureDescription) {
                    segmentActive = false
                    when {
                        isDragging && (cursorX != segEndX || cursorY != segEndY) ->
                            dispatchMoveSegment(willContinue = true)
                        !isDragging && currentStroke != null ->
                            dispatchMoveSegment(willContinue = false)
                    }
                }
                override fun onCancelled(g: GestureDescription) {
                    Log.w(TAG, "gesture cancelled — resetting segment, will retry on next move")
                    segmentActive = false
                    currentStroke = null
                    // Don't touch isDragging: finger is still down. Reset segEnd to current
                    // position so the next move starts a fresh gesture from here rather than
                    // trying to jump across the gap since the last dispatched segment.
                    segEndX = cursorX
                    segEndY = cursorY
                }
            }, null
        )
        if (ok) {
            segmentActive = true
            segEndX = toX
            segEndY = toY
        } else {
            Log.w(TAG, "dispatchGesture returned false — skipping segment")
        }
    }

    fun injectMouseTap() {
        Log.d(TAG, "injectMouseTap at ($cursorX,$cursorY)")
        val path = Path().apply { moveTo(cursorX, cursorY) }
        // 50ms duration: long enough for DOSBox/RetroArch and other apps with stricter
        // touch handlers to register the touch as a real tap, while still well under
        // ViewConfiguration.getTapTimeout() (~100ms) so it doesn't read as a long press.
        val stroke = GestureDescription.StrokeDescription(path, 0L, 50L)
        val ok = dispatchGesture(
            GestureDescription.Builder().addStroke(stroke).build(),
            object : GestureResultCallback() {
                override fun onCompleted(g: GestureDescription) { Log.d(TAG, "injectMouseTap: completed") }
                override fun onCancelled(g: GestureDescription) { Log.w(TAG, "injectMouseTap: cancelled") }
            }, null
        )
        if (!ok) Log.w(TAG, "injectMouseTap: dispatchGesture returned false")
    }

    /**
     * Multi-finger tap gestures. Wine (and many touch-to-mouse layers) natively interpret
     * 2-finger tap as right-click and 3-finger tap as middle-click. Single-finger tap stays
     * as injectMouseTap for left-click.
     *
     * Implemented via dispatchGesture with multiple simultaneous strokes — public API, no
     * reflection, no INJECT_EVENTS permission required. Same technique used by DS Keyboard.
     */
    private fun dispatchMultiFingerTap(fingerCount: Int, label: String) {
        Log.d(TAG, "$label at ($cursorX,$cursorY) — $fingerCount-finger tap")
        val spacing = 30f  // px between fingers
        val builder = GestureDescription.Builder()
        // Lay fingers symmetrically around the cursor anchor.
        val totalSpan = spacing * (fingerCount - 1)
        val startX = cursorX - totalSpan / 2f
        for (i in 0 until fingerCount) {
            val x = startX + i * spacing
            val path = Path().apply { moveTo(x, cursorY) }
            builder.addStroke(GestureDescription.StrokeDescription(path, 0L, 50L))
        }
        val ok = dispatchGesture(
            builder.build(),
            object : GestureResultCallback() {
                override fun onCompleted(g: GestureDescription) { Log.d(TAG, "$label: completed") }
                override fun onCancelled(g: GestureDescription) { Log.w(TAG, "$label: cancelled") }
            }, null
        )
        if (!ok) Log.w(TAG, "$label: dispatchGesture returned false")
    }

    fun injectMouseRightClick()  = dispatchMultiFingerTap(2, "injectMouseRightClick")
    fun injectMouseMiddleClick() = dispatchMultiFingerTap(3, "injectMouseMiddleClick")

    /**
     * Two-finger vertical drag. Wine/touch-to-mouse layers translate this into scroll wheel
     * events. dy > 0 → scroll up (fingers drag down, Android natural scroll convention).
     */
    fun injectMouseScroll(dx: Float, dy: Float) {
        Log.d(TAG, "injectMouseScroll dx=$dx dy=$dy — two-finger drag")
        val spacing = 30f
        val dragDistance = 200f
        // dy>0 means scroll up → fingers move down (positive Y in screen coords).
        val deltaY = if (dy > 0f) dragDistance else -dragDistance
        val deltaX = if (dx != 0f) (if (dx > 0f) dragDistance else -dragDistance) else 0f
        val builder = GestureDescription.Builder()
        listOf(-spacing / 2f, spacing / 2f).forEach { offsetX ->
            val path = Path().apply {
                moveTo(cursorX + offsetX, cursorY)
                lineTo(cursorX + offsetX + deltaX, cursorY + deltaY)
            }
            builder.addStroke(GestureDescription.StrokeDescription(path, 0L, 200L))
        }
        val ok = dispatchGesture(
            builder.build(),
            object : GestureResultCallback() {
                override fun onCompleted(g: GestureDescription) { Log.d(TAG, "injectMouseScroll: completed") }
                override fun onCancelled(g: GestureDescription) { Log.w(TAG, "injectMouseScroll: cancelled") }
            }, null
        )
        if (!ok) Log.w(TAG, "injectMouseScroll: dispatchGesture returned false")
    }

    // No-op placeholders kept so dispatchTargetAsClick's match remains exhaustive and the picker
    // can still expose MOUSE_BACK/MOUSE_FORWARD even though no reliable cross-app injection
    // mechanism exists for them via accessibility gestures.
    fun injectMouseBackClick() {
        Log.w(TAG, "injectMouseBackClick: no reliable cross-app touch gesture exists for this — noop")
    }
    fun injectMouseForwardClick() {
        Log.w(TAG, "injectMouseForwardClick: no reliable cross-app touch gesture exists for this — noop")
    }

    @Suppress("DEPRECATION")
    private fun primaryDisplaySize(): Pair<Float, Float> {
        val dm = getSystemService(DISPLAY_SERVICE) as android.hardware.display.DisplayManager
        val display = dm.getDisplay(android.view.Display.DEFAULT_DISPLAY)
            ?: return Pair(1080f, 1920f).also { Log.w(TAG, "primaryDisplaySize: no default display") }
        val metrics = android.util.DisplayMetrics()
        display.getRealMetrics(metrics)
        Log.d(TAG, "primaryDisplaySize: displayId=${display.displayId} size=${metrics.widthPixels}x${metrics.heightPixels}")
        return Pair(metrics.widthPixels.toFloat(), metrics.heightPixels.toFloat())
    }

    // ── Low-level injection ───────────────────────────────────────────────────

    @SuppressLint("PrivateApi")
    private fun injectKeyDown(keyCode: Int) = injectRawKeyEvent(
        KeyEvent(SystemClock.uptimeMillis(), SystemClock.uptimeMillis(), KeyEvent.ACTION_DOWN, keyCode, 0)
    )

    @SuppressLint("PrivateApi")
    private fun injectKeyUp(keyCode: Int) = injectRawKeyEvent(
        KeyEvent(SystemClock.uptimeMillis(), SystemClock.uptimeMillis(), KeyEvent.ACTION_UP, keyCode, 0)
    )

    @SuppressLint("PrivateApi")
    private fun injectRawKeyEvent(event: KeyEvent) {
        injectInputEvent(event)
    }

    @SuppressLint("PrivateApi")
    private fun injectInputEvent(event: android.view.InputEvent) {
        try {
            val imClass = Class.forName("android.hardware.input.InputManager")
            val im = imClass.getDeclaredMethod("getInstance").invoke(null)
            val result = imClass.getDeclaredMethod(
                "injectInputEvent",
                android.view.InputEvent::class.java,
                Int::class.javaPrimitiveType
            ).invoke(im, event, 0) as? Boolean ?: false // 0 = INJECT_INPUT_EVENT_MODE_ASYNC
            Log.d(TAG, "injectInputEvent result=$result type=${event.javaClass.simpleName} source=0x${event.source.toString(16)}")
        } catch (e: Exception) {
            Log.e(TAG, "injectInputEvent failed: $event", e)
        }
    }
}
