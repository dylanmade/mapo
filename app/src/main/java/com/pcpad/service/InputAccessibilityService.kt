package com.pcpad.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.annotation.SuppressLint
import android.os.SystemClock
import android.util.Log
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
import com.pcpad.data.model.DeviceButton
import com.pcpad.data.model.RemapTarget

class InputAccessibilityService : AccessibilityService() {

    companion object {
        @Volatile var instance: InputAccessibilityService? = null
            private set

        // Active profile's remap mappings — updated by MainViewModel.
        @Volatile var currentMappings: Map<DeviceButton, RemapTarget> = emptyMap()

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

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        // Set programmatically — XML config is cached by Android and may not reflect
        // changes until the service is manually toggled off/on in Settings.
        val info = serviceInfo
        info.flags = info.flags or AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS
        setServiceInfo(info)
        Log.i(TAG, "Service connected, flags=0x${serviceInfo.flags.toString(16)} capabilities=0x${serviceInfo.capabilities.toString(16)}")
    }

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        instance = null
        return super.onUnbind(intent)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit
    override fun onInterrupt() = Unit

    // ── Physical button interception (remap) ──────────────────────────────────

    override fun onKeyEvent(event: KeyEvent): Boolean {
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
        try {
            val imClass = Class.forName("android.hardware.input.InputManager")
            val im = imClass.getDeclaredMethod("getInstance").invoke(null)
            imClass.getDeclaredMethod(
                "injectInputEvent",
                android.view.InputEvent::class.java,
                Int::class.javaPrimitiveType
            ).invoke(im, event, 0) // 0 = INJECT_INPUT_EVENT_MODE_ASYNC
        } catch (e: Exception) {
            Log.e(TAG, "injectRawKeyEvent failed: keyCode=${event.keyCode} action=${event.action}", e)
        }
    }

}
