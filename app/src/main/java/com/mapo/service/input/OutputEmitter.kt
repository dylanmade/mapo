package com.mapo.service.input

import android.util.Log
import com.mapo.data.model.RemapTarget
import com.mapo.data.model.steam.BindingOutput
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Translates a typed [BindingOutput] into the underlying [InputDispatcher] call(s).
 *
 * Cadence: invoked once per state-machine edge from [InputEvaluator] — DOWN edge for
 * [emitPress], UP edge for [emitRelease]. Event-driven (not polled), one call per
 * actual input transition.
 *
 * Press semantics by output kind:
 *  - **KeyPress / XInputButton** — emit DOWN edge, return true so the evaluator
 *    persists this binding in its held set and emits the matching UP on release.
 *  - **MouseButton / MouseWheel** — fire-and-done one-shot via the existing accessibility
 *    gesture path. There's no "release" for an accessibility tap, so we return false to
 *    tell the evaluator not to bother tracking these in the held set.
 *  - **Unbound** — no-op, return false.
 *  - **GameAction / ControllerAction** — stub. Log the press and drop; the
 *    binding type exists in the schema but its runtime behavior lands in Phase 4.
 *    Returning false keeps the evaluator from waiting on a release that never comes.
 *
 * Release semantics: only KeyPress / XInputButton have DOWN/UP edges, so [emitRelease]
 * is a no-op for everything else. The evaluator only calls release for bindings whose
 * [emitPress] returned true, so this is a defensive symmetry rather than a hot path.
 */
@Singleton
class OutputEmitter @Inject constructor(
    private val dispatcher: InputDispatcher,
    private val gamepad: com.mapo.service.shizuku.ShizukuGamepadInjector,
) {

    // Held count per (stick, direction) for XInputStick outputs — multiple inputs can
    // drive the same direction, so a count (not a flag) keeps it active until the last
    // releases. Guarded because emit can be called from the evaluator thread and the
    // analog-emulation pulse coroutine.
    private val stickLock = Any()
    private val stickHeld = HashMap<String, Int>()
    // Held count per trigger side ("LEFT"/"RIGHT") for AXIS_L2/R2 outputs — multiple
    // inputs can drive the same trigger, so a count keeps the analog axis at full until
    // the last releases. Shares [stickLock].
    private val triggerHeld = HashMap<String, Int>()

    /**
     * Returns true if [output] has a matching release edge — i.e. the evaluator must hold it.
     *
     * @param sendAsGesture only consulted for mouse-shaped outputs ([BindingOutput.MouseButton] /
     *   [BindingOutput.MouseWheel]); other output kinds ignore it. When true, the click is
     *   emitted as a synthetic touch via `AccessibilityService.dispatchGesture`; when false,
     *   it's a real `BTN_LEFT`/`REL_WHEEL` mouse event via the uinput device.
     */
    fun emitPress(output: BindingOutput, sendAsGesture: Boolean = false): Boolean = when (output) {
        BindingOutput.Unbound -> false
        is BindingOutput.KeyPress -> {
            Log.d(TAG, "press KeyPress(${output.keyCode})")
            dispatcher.injectKeyDown(output.keyCode)
            true
        }
        is BindingOutput.XInputButton -> {
            // Route real gamepad buttons through the virtual gamepad (MVG) so games
            // that read a gamepad InputDevice — GameNative / emulators — see an actual
            // controller button. Injecting them as SOURCE_KEYBOARD KeyEvents (the old
            // path) reached GameNative's *launcher/overlay* as a menu button (e.g.
            // "gamepad B" opened its settings) but never the in-game Wine gamepad layer.
            // Consistent with how XInputStick already drives the MVG. Falls back to key
            // inject when the MVG is unavailable (no Shizuku) so digital remap still does
            // something. DPAD_* and any unmapped token take the fallback (dpad output is
            // a hat axis, not a button — separate path if it's ever needed).
            val triggerSide = TRIGGER_OUTPUT_SIDES[output.button]
            if (triggerSide != null) {
                // AXIS_L2/R2 are the ANALOG triggers (ABS_Z / ABS_RZ) — what games +
                // testers actually read. A digital BTN_TL2/TR2 press does NOT register
                // as the trigger axis, so drive the analog output to full instead.
                Log.d(TAG, "press XInputButton(${output.button}) → MVG analog trigger $triggerSide")
                adjustTrigger(triggerSide, +1)
            } else {
                val btn = GAMEPAD_BUTTON_CODES[output.button]
                if (btn != null && gamepad.setButton(btn, true)) {
                    Log.d(TAG, "press XInputButton(${output.button}) → MVG btn=0x${btn.toString(16)}")
                } else {
                    Log.d(TAG, "press XInputButton(${output.button}) → key inject (MVG unavailable/unmapped)")
                    dispatcher.injectKeyDown(output.button)
                }
            }
            true
        }
        is BindingOutput.XInputStick -> {
            Log.d(TAG, "press XInputStick(${output.stick}/${output.direction})")
            adjustStick(output, +1)
            true
        }
        is BindingOutput.MouseButton -> {
            Log.d(TAG, "press MouseButton(${output.button}) sendAsGesture=$sendAsGesture")
            dispatcher.dispatchTargetAsClick(RemapTarget.Mouse(output.button), sendAsGesture)
            false
        }
        is BindingOutput.MouseWheel -> {
            Log.d(TAG, "press MouseWheel(${output.direction}) sendAsGesture=$sendAsGesture")
            dispatcher.dispatchTargetAsClick(RemapTarget.Mouse(output.direction), sendAsGesture)
            false
        }
        is BindingOutput.GameAction -> {
            Log.d(TAG, "press GameAction(${output.setName}/${output.actionName}) — Phase 4 stub, dropped")
            false
        }
        is BindingOutput.ControllerAction -> {
            Log.d(TAG, "press ControllerAction(${output.verb} ${output.args}) — Phase 4/5 stub, dropped")
            false
        }
    }

    fun emitRelease(output: BindingOutput) {
        when (output) {
            is BindingOutput.KeyPress -> {
                Log.d(TAG, "release KeyPress(${output.keyCode})")
                dispatcher.injectKeyUp(output.keyCode)
            }
            is BindingOutput.XInputButton -> {
                // Mirror emitPress's routing decision (see there).
                val triggerSide = TRIGGER_OUTPUT_SIDES[output.button]
                if (triggerSide != null) {
                    Log.d(TAG, "release XInputButton(${output.button}) → MVG analog trigger $triggerSide")
                    adjustTrigger(triggerSide, -1)
                } else {
                    val btn = GAMEPAD_BUTTON_CODES[output.button]
                    if (btn != null && gamepad.setButton(btn, false)) {
                        Log.d(TAG, "release XInputButton(${output.button}) → MVG btn=0x${btn.toString(16)}")
                    } else {
                        Log.d(TAG, "release XInputButton(${output.button}) → key inject")
                        dispatcher.injectKeyUp(output.button)
                    }
                }
            }
            is BindingOutput.XInputStick -> {
                Log.d(TAG, "release XInputStick(${output.stick}/${output.direction})")
                adjustStick(output, -1)
            }
            else -> Unit
        }
    }

    /** Update the held count for [output]'s direction and re-push that stick's net axis. */
    private fun adjustStick(output: BindingOutput.XInputStick, delta: Int) {
        val key = "${output.stick}|${output.direction}"
        synchronized(stickLock) {
            val n = (stickHeld[key] ?: 0) + delta
            if (n <= 0) stickHeld.remove(key) else stickHeld[key] = n
        }
        pushStick(output.stick)
    }

    /** Compute and emit the net (x, y) for [stick] from its held directions. */
    private fun pushStick(stick: String) {
        val (x, y) = synchronized(stickLock) {
            val up = (stickHeld["$stick|UP"] ?: 0) > 0
            val down = (stickHeld["$stick|DOWN"] ?: 0) > 0
            val left = (stickHeld["$stick|LEFT"] ?: 0) > 0
            val right = (stickHeld["$stick|RIGHT"] ?: 0) > 0
            // +x = right, +y = down (gamepad convention) — so UP is negative.
            val x = (if (right) 1f else 0f) - (if (left) 1f else 0f)
            val y = (if (down) 1f else 0f) - (if (up) 1f else 0f)
            x to y
        }
        if (stick == "LEFT") gamepad.setLeftStickOutput(x, y) else gamepad.setRightStickOutput(x, y)
    }

    /** Update the held count for [side]'s trigger and re-push its net analog value (0 or full). */
    private fun adjustTrigger(side: String, delta: Int) {
        val active = synchronized(stickLock) {
            val n = (triggerHeld[side] ?: 0) + delta
            if (n <= 0) triggerHeld.remove(side) else triggerHeld[side] = n
            (triggerHeld[side] ?: 0) > 0
        }
        val v = if (active) 1f else 0f
        if (side == "LEFT") gamepad.setLeftTriggerOutput(v) else gamepad.setRightTriggerOutput(v)
    }

    /** Drop all held stick/trigger-output state + zero the slots. Called on mode/config change. */
    fun resetStickOutputs() {
        synchronized(stickLock) { stickHeld.clear(); triggerHeld.clear() }
        gamepad.clearOutputSticks()
    }

    companion object {
        private const val TAG = "OutputEmitter"

        /**
         * [BindingOutput.XInputButton] token ([DeviceButton] name) → virtual-gamepad
         * `UinputGamepad.Buttons.*` (Linux `BTN_*`) code. Drives real controller buttons
         * on the MVG instead of injecting SOURCE_KEYBOARD key events. AXIS_L2/R2 map to
         * the trigger DIGITAL buttons (BTN_TL2/TR2). DPAD_* is intentionally absent — the
         * d-pad is a hat axis on the gamepad, so those fall back to key inject.
         */
        private val GAMEPAD_BUTTON_CODES: Map<String, Int> = mapOf(
            "BUTTON_A" to 0x130, "BUTTON_B" to 0x131,
            "BUTTON_X" to 0x133, "BUTTON_Y" to 0x134,
            "BUTTON_L1" to 0x136, "BUTTON_R1" to 0x137,
            "BUTTON_SELECT" to 0x13a, "BUTTON_START" to 0x13b,
            "BUTTON_THUMBL" to 0x13d, "BUTTON_THUMBR" to 0x13e,
        )

        /**
         * [BindingOutput.XInputButton] tokens that are really the ANALOG triggers, not
         * buttons → which MVG trigger side to drive. The triggers live on ABS_Z / ABS_RZ;
         * a digital BTN_TL2/TR2 press is invisible to games/testers reading the trigger
         * axis, so these route to [setLeftTriggerOutput] / [setRightTriggerOutput] instead.
         */
        private val TRIGGER_OUTPUT_SIDES: Map<String, String> = mapOf(
            "AXIS_L2" to "LEFT",
            "AXIS_R2" to "RIGHT",
        )
    }
}
