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
            Log.d(TAG, "press XInputButton(${output.button})")
            dispatcher.injectKeyDown(output.button)
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
                Log.d(TAG, "release XInputButton(${output.button})")
                dispatcher.injectKeyUp(output.button)
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

    /** Drop all held stick-output state + zero the slots. Called on mode/config change. */
    fun resetStickOutputs() {
        synchronized(stickLock) { stickHeld.clear() }
        gamepad.clearOutputSticks()
    }

    companion object {
        private const val TAG = "OutputEmitter"
    }
}
