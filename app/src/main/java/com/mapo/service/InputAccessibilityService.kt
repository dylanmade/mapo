package com.mapo.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.accessibilityservice.GestureDescription
import android.annotation.SuppressLint
import android.graphics.Path
import android.os.SystemClock
import android.util.Log
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.accessibility.AccessibilityEvent
import com.mapo.data.model.DeviceButton
import com.mapo.data.model.RemapTarget

class InputAccessibilityService : AccessibilityService() {

    companion object {
        @Volatile var instance: InputAccessibilityService? = null
            private set

        // Active profile's remap mappings — updated by MainViewModel.
        @Volatile var currentMappings: Map<DeviceButton, RemapTarget> = emptyMap()

        // Remapping on/off toggle — updated by MainViewModel.
        @Volatile var remapEnabled: Boolean = false

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

    private var cursorX = 540f
    private var cursorY = 960f


    // ── Lifecycle ─────────────────────────────────────────────────────────────

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        val info = serviceInfo
        info.flags = info.flags or AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS
        setServiceInfo(info)
        val (w, h) = primaryDisplaySize()
        cursorX = w / 2f
        cursorY = h / 2f
        Log.i(TAG, "Service connected — display=${w}x${h} cursor=($cursorX,$cursorY)")
    }

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        instance = null
        return super.onUnbind(intent)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit
    override fun onInterrupt() = Unit

    // ── Physical button interception (remap) ──────────────────────────────────

    override fun onKeyEvent(event: KeyEvent): Boolean {
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
            is RemapTarget.Mouse   -> { /* TODO: dispatchGesture */ }
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

    // ── Mouse gesture injection (called from ViewModel for trackpad) ──────────

    private var isDragging = false
    private var hasMoved = false
    private var currentStroke: GestureDescription.StrokeDescription? = null
    private var gestureActive = false
    private var lastDispatchedX = 540f
    private var lastDispatchedY = 960f

    fun startMouseDrag() {
        isDragging = true
        hasMoved = false
        currentStroke = null
        gestureActive = false
        lastDispatchedX = cursorX
        lastDispatchedY = cursorY
        Log.d(TAG, "startMouseDrag at ($cursorX,$cursorY)")
    }

    fun injectMouseMove(dx: Float, dy: Float) {
        hasMoved = true
        cursorX += dx
        cursorY += dy
        if (!gestureActive) dispatchDragSegment(willContinue = true)
    }

    fun endMouseDrag() {
        isDragging = false
        Log.d(TAG, "endMouseDrag hasMoved=$hasMoved gestureActive=$gestureActive currentStroke=${currentStroke != null}")
        if (!hasMoved) return
        if (!gestureActive && currentStroke != null) dispatchDragSegment(willContinue = false)
    }

    private fun dispatchDragSegment(willContinue: Boolean) {
        val fromX = lastDispatchedX; val fromY = lastDispatchedY
        val toX = cursorX;           val toY = cursorY
        lastDispatchedX = toX; lastDispatchedY = toY
        val path = Path().apply {
            moveTo(fromX, fromY)
            if (fromX != toX || fromY != toY) lineTo(toX, toY)
        }
        val stroke = if (currentStroke == null) {
            Log.d(TAG, "dispatchGesture NEW ($fromX,$fromY)->($toX,$toY) willContinue=$willContinue")
            GestureDescription.StrokeDescription(path, 0L, 50L, willContinue)
        } else {
            Log.d(TAG, "dispatchGesture CONT ($fromX,$fromY)->($toX,$toY) willContinue=$willContinue")
            currentStroke!!.continueStroke(path, 0L, 50L, willContinue)
        }
        currentStroke = if (willContinue) stroke else null
        gestureActive = true
        dispatchGesture(GestureDescription.Builder().addStroke(stroke).build(),
            object : GestureResultCallback() {
                override fun onCompleted(g: GestureDescription) {
                    Log.d(TAG, "gesture onCompleted isDragging=$isDragging cursorMoved=${cursorX != lastDispatchedX || cursorY != lastDispatchedY}")
                    gestureActive = false
                    when {
                        isDragging && (cursorX != lastDispatchedX || cursorY != lastDispatchedY) ->
                            dispatchDragSegment(willContinue = true)
                        !isDragging && currentStroke != null ->
                            dispatchDragSegment(willContinue = false)
                    }
                }
                override fun onCancelled(g: GestureDescription) {
                    Log.w(TAG, "gesture onCancelled — touch sequence broken")
                    gestureActive = false
                    currentStroke = null
                    isDragging = false
                }
            }, null)
    }

    fun injectMouseTap() {
        Log.d(TAG, "injectMouseTap at ($cursorX,$cursorY)")
        val path = Path().apply { moveTo(cursorX, cursorY) }
        val stroke = GestureDescription.StrokeDescription(path, 0L, 50L)
        dispatchGesture(GestureDescription.Builder().addStroke(stroke).build(), null, null)
    }

    fun injectMouseRightClick() {
        Log.d(TAG, "injectMouseRightClick at ($cursorX,$cursorY)")
        val path = Path().apply { moveTo(cursorX, cursorY) }
        val stroke = GestureDescription.StrokeDescription(path, 0L, 800L)
        dispatchGesture(GestureDescription.Builder().addStroke(stroke).build(), null, null)
    }

    fun injectMouseScroll(dx: Float, dy: Float) {
        Log.d(TAG, "injectMouseScroll dx=$dx dy=$dy")
        val now = SystemClock.uptimeMillis()
        val props = arrayOf(MotionEvent.PointerProperties().apply {
            id = 0; toolType = MotionEvent.TOOL_TYPE_MOUSE
        })
        val coords = arrayOf(MotionEvent.PointerCoords().apply {
            x = cursorX; y = cursorY
            setAxisValue(MotionEvent.AXIS_VSCROLL, dy)
            setAxisValue(MotionEvent.AXIS_HSCROLL, dx)
        })
        val event = MotionEvent.obtain(
            now, now, MotionEvent.ACTION_SCROLL,
            1, props, coords,
            0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_MOUSE, 0
        )
        injectInputEvent(event)
        event.recycle()
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
