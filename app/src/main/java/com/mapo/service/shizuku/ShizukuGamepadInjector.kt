package com.mapo.service.shizuku

import android.util.Log
import com.mapo.data.model.steam.InputSource
import com.mapo.service.input.modes.GamepadEmitter
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.roundToInt

/**
 * **Brick C — virtual XInput gamepad facade.**
 *
 * Maintains a cached snapshot of the virtual gamepad's full analog state
 * (two sticks + two triggers + dpad hat) and exposes per-source mutators
 * that re-push the merged state through the Shizuku UserService whenever
 * any contributing source changes its contribution.
 *
 * Why centralized state lives here: the virtual gamepad is a *single*
 * kernel device with all axes; per-source modes update only one slice of
 * it (LJ touches leftX/leftY, LT touches leftTrigger, etc.). Without a
 * cache, each per-source write would have to know every other source's
 * current value to push the full axis set. The cache solves that — each
 * source mutator updates its own slot, then we re-serialize the full
 * state to the service.
 *
 * Thread safety: mutators are called from `InputEvaluator.dispatchReadings`
 * (binder thread + main scope coroutines). All state reads/writes go
 * through `synchronized(lock)` because the per-axis ints in a Kotlin
 * data class aren't atomic.
 *
 * **Coordinate convention** (verified against `reference_thor_axis_convention.md`):
 *  - Sticks: input range -1.0..+1.0 (per `AnalogEvent`'s contract);
 *    `+y = down` (push stick up → negative y). Mapped to int16 range.
 *  - Triggers: input range 0.0..1.0; mapped to 0..255.
 *  - Dpad: int -1/0/+1 per axis (caller decides 4-way vs 8-way upstream).
 */
@Singleton
class ShizukuGamepadInjector @Inject constructor(
    private val shizukuConnection: ShizukuConnection,
) : GamepadEmitter {

    private val lock = Any()

    /** Cached state pushed to the UserService each time any slice updates. */
    private data class GamepadState(
        var leftX: Int = 0, var leftY: Int = 0,
        var rightX: Int = 0, var rightY: Int = 0,
        var leftTrigger: Int = 0, var rightTrigger: Int = 0,
        var hatX: Int = 0, var hatY: Int = 0,
    )

    private val state = GamepadState()

    /**
     * Set the left stick's contribution. `(x, y)` are normalized -1.0..+1.0
     * with `+y = down`. Pushed to the UserService along with the rest of
     * the cached gamepad state. No-op when Shizuku isn't ready (caller
     * should expect this and surface degradation upstream if needed).
     */
    override fun setLeftStick(x: Float, y: Float) {
        synchronized(lock) {
            state.leftX = floatToInt16(x)
            state.leftY = floatToInt16(y)
        }
        push()
    }

    override fun setRightStick(x: Float, y: Float) {
        synchronized(lock) {
            state.rightX = floatToInt16(x)
            state.rightY = floatToInt16(y)
        }
        push()
    }

    /** Trigger value in 0.0..1.0 mapped to 0..255. */
    override fun setLeftTrigger(v: Float) {
        synchronized(lock) { state.leftTrigger = floatToTrigger(v) }
        push()
    }

    override fun setRightTrigger(v: Float) {
        synchronized(lock) { state.rightTrigger = floatToTrigger(v) }
        push()
    }

    /** Dpad hat — pass -1, 0, or 1 per axis. */
    override fun setDpadHat(x: Int, y: Int) {
        synchronized(lock) {
            state.hatX = x.coerceIn(-1, 1)
            state.hatY = y.coerceIn(-1, 1)
        }
        push()
    }

    /**
     * Reset a source's contribution to zero. Called when an analog source
     * transitions out of a gamepad-emitting mode (e.g. user picks Joystick
     * Mouse on the LJ — the LJ's leftX/leftY slot must zero out so a
     * residual deflection doesn't leak into the virtual gamepad).
     */
    override fun clearSource(source: InputSource) {
        var changed = false
        synchronized(lock) {
            when (source) {
                InputSource.LEFT_JOYSTICK -> {
                    if (state.leftX != 0 || state.leftY != 0) {
                        state.leftX = 0; state.leftY = 0; changed = true
                    }
                }
                InputSource.RIGHT_JOYSTICK -> {
                    if (state.rightX != 0 || state.rightY != 0) {
                        state.rightX = 0; state.rightY = 0; changed = true
                    }
                }
                InputSource.LEFT_TRIGGER -> {
                    if (state.leftTrigger != 0) { state.leftTrigger = 0; changed = true }
                }
                InputSource.RIGHT_TRIGGER -> {
                    if (state.rightTrigger != 0) { state.rightTrigger = 0; changed = true }
                }
                InputSource.DPAD -> {
                    if (state.hatX != 0 || state.hatY != 0) {
                        state.hatX = 0; state.hatY = 0; changed = true
                    }
                }
                else -> Unit
            }
        }
        if (changed) push()
    }

    /** Press or release a gamepad button. `btnCode` is a `UinputGamepad.Buttons.*` int. */
    override fun setButton(btnCode: Int, pressed: Boolean) {
        if (!shizukuConnection.isReadyFlow.value) return
        val service = shizukuConnection.service.value ?: return
        try {
            service.setGamepadButton(btnCode, pressed)
        } catch (t: Throwable) {
            Log.w(TAG, "setGamepadButton threw btnCode=0x${btnCode.toString(16)} pressed=$pressed", t)
        }
    }

    private fun push() {
        if (!shizukuConnection.isReadyFlow.value) return
        val service = shizukuConnection.service.value ?: return
        val snapshot = synchronized(lock) { state.copy() }
        try {
            service.setGamepadAxes(
                snapshot.leftX, snapshot.leftY,
                snapshot.rightX, snapshot.rightY,
                snapshot.leftTrigger, snapshot.rightTrigger,
                snapshot.hatX, snapshot.hatY,
            )
        } catch (t: Throwable) {
            Log.w(TAG, "setGamepadAxes threw", t)
        }
    }

    /** Map -1.0..+1.0 to -32768..+32767 with rounding. */
    private fun floatToInt16(v: Float): Int =
        (v.coerceIn(-1f, 1f) * 32767f).roundToInt().coerceIn(-32768, 32767)

    /** Map 0.0..1.0 to 0..255 with rounding. */
    private fun floatToTrigger(v: Float): Int =
        (v.coerceIn(0f, 1f) * 255f).roundToInt().coerceIn(0, 255)

    companion object { private const val TAG = "ShizukuGamepadInjector" }
}
