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
 * Maintains a **per-source** contribution table for each of the virtual
 * gamepad's analog axes. On every mutator the entire merged state is
 * re-summed and pushed through the Shizuku UserService — physical and
 * mode-driven contributions to the same axis combine additively rather
 * than overwriting each other.
 *
 * Example: a physical `RIGHT_JOYSTICK` in JoystickMove mode and a `GYRO`
 * source in Joystick Camera mode both write the right stick. With
 * per-source storage they sum — the player can aim with the physical
 * stick AND nudge the camera with gyro at the same time. The pre-refactor
 * single-slot cache stomped one with the other (last-write-wins) which
 * felt like the inputs were fighting each other.
 *
 * Sum behavior:
 *  - Sticks: per-source (x, y) floats summed; clamped to [-1, +1] per axis
 *    BEFORE int16 mapping.
 *  - Triggers: per-source floats summed; clamped to [0, +1] before
 *    mapping to 0..255.
 *  - Hat: per-source signed integer contributions summed; each axis
 *    clamped to {-1, 0, +1}.
 *  - Buttons: NOT per-source (a single button is owned by a single source).
 *    Direct passthrough.
 *
 * Thread safety: mutators are called from `InputEvaluator.dispatchReadings`
 * (binder thread + main scope coroutines). All cache reads/writes go
 * through `synchronized(lock)`.
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

    // Per-source contribution tables. Each map's key is the contributing
    // [InputSource]; value is the source's most recent (x, y) or scalar.
    // push() walks each map, sums the contributions, clamps, and writes
    // the merged value through the UserService.
    //
    // Stored as primitive arrays / Floats so cached contributions don't
    // allocate per event. The arrays are mutated in place under lock; an
    // entry being absent from a map means "this source isn't contributing
    // anything to this axis right now" (zero contribution; doesn't appear
    // in the sum).
    private val leftStickBySource = mutableMapOf<InputSource, FloatArray>()
    private val rightStickBySource = mutableMapOf<InputSource, FloatArray>()
    private val leftTriggerBySource = mutableMapOf<InputSource, Float>()
    private val rightTriggerBySource = mutableMapOf<InputSource, Float>()
    private val hatBySource = mutableMapOf<InputSource, IntArray>()

    override fun setLeftStick(source: InputSource, x: Float, y: Float) {
        synchronized(lock) {
            val slot = leftStickBySource.getOrPut(source) { FloatArray(2) }
            slot[0] = x; slot[1] = y
        }
        if (Log.isLoggable(TAG_AXES, Log.VERBOSE)) {
            Log.v(TAG_AXES, "setLeftStick src=$source x=${"%.3f".format(x)} y=${"%.3f".format(y)}")
        }
        push()
    }

    override fun setRightStick(source: InputSource, x: Float, y: Float) {
        synchronized(lock) {
            val slot = rightStickBySource.getOrPut(source) { FloatArray(2) }
            slot[0] = x; slot[1] = y
        }
        if (Log.isLoggable(TAG_AXES, Log.VERBOSE)) {
            Log.v(TAG_AXES, "setRightStick src=$source x=${"%.3f".format(x)} y=${"%.3f".format(y)}")
        }
        push()
    }

    /** Trigger value in 0.0..1.0 — per-source summed and clamped to 0..1 before push. */
    override fun setLeftTrigger(source: InputSource, v: Float) {
        synchronized(lock) { leftTriggerBySource[source] = v }
        if (Log.isLoggable(TAG_AXES, Log.VERBOSE)) {
            Log.v(TAG_AXES, "setLeftTrigger src=$source v=${"%.3f".format(v)}")
        }
        push()
    }

    override fun setRightTrigger(source: InputSource, v: Float) {
        synchronized(lock) { rightTriggerBySource[source] = v }
        if (Log.isLoggable(TAG_AXES, Log.VERBOSE)) {
            Log.v(TAG_AXES, "setRightTrigger src=$source v=${"%.3f".format(v)}")
        }
        push()
    }

    /** Dpad hat — pass -1, 0, or 1 per axis; per-source summed and clamped. */
    override fun setDpadHat(source: InputSource, x: Int, y: Int) {
        synchronized(lock) {
            val slot = hatBySource.getOrPut(source) { IntArray(2) }
            slot[0] = x.coerceIn(-1, 1); slot[1] = y.coerceIn(-1, 1)
        }
        if (Log.isLoggable(TAG_AXES, Log.VERBOSE)) {
            Log.v(TAG_AXES, "setDpadHat src=$source x=$x y=$y")
        }
        push()
    }

    /**
     * Remove [source]'s contribution from every axis it could be driving.
     * Sources are pre-classified by what they typically drive
     * (LEFT_JOYSTICK → leftStick, GYRO → either stick depending on mode,
     * etc.); doing the full sweep is cheap (a handful of map removals) and
     * correct regardless of which axes the source was actively contributing
     * to.
     */
    override fun clearSource(source: InputSource) {
        var changed = false
        synchronized(lock) {
            if (leftStickBySource.remove(source) != null) changed = true
            if (rightStickBySource.remove(source) != null) changed = true
            if (leftTriggerBySource.remove(source) != null) changed = true
            if (rightTriggerBySource.remove(source) != null) changed = true
            if (hatBySource.remove(source) != null) changed = true
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

        // Snapshot + sum under lock so per-source updates from concurrent
        // mutators don't tear the merged result.
        val merged = synchronized(lock) {
            var lx = 0f; var ly = 0f
            for (v in leftStickBySource.values) { lx += v[0]; ly += v[1] }
            var rx = 0f; var ry = 0f
            for (v in rightStickBySource.values) { rx += v[0]; ry += v[1] }
            var lt = 0f
            for (v in leftTriggerBySource.values) lt += v
            var rt = 0f
            for (v in rightTriggerBySource.values) rt += v
            var hx = 0; var hy = 0
            for (v in hatBySource.values) { hx += v[0]; hy += v[1] }
            MergedAxes(
                leftX = floatToInt16(lx),
                leftY = floatToInt16(ly),
                rightX = floatToInt16(rx),
                rightY = floatToInt16(ry),
                leftTrigger = floatToTrigger(lt),
                rightTrigger = floatToTrigger(rt),
                hatX = hx.coerceIn(-1, 1),
                hatY = hy.coerceIn(-1, 1),
            )
        }
        try {
            service.setGamepadAxes(
                merged.leftX, merged.leftY,
                merged.rightX, merged.rightY,
                merged.leftTrigger, merged.rightTrigger,
                merged.hatX, merged.hatY,
            )
        } catch (t: Throwable) {
            Log.w(TAG, "setGamepadAxes threw", t)
        }
    }

    /** Snapshot of the summed-and-clamped axis values pushed to the kernel. */
    private data class MergedAxes(
        val leftX: Int, val leftY: Int,
        val rightX: Int, val rightY: Int,
        val leftTrigger: Int, val rightTrigger: Int,
        val hatX: Int, val hatY: Int,
    )

    /** Map -1.0..+1.0 to -32768..+32767 with rounding. Clamps before mapping. */
    private fun floatToInt16(v: Float): Int =
        (v.coerceIn(-1f, 1f) * 32767f).roundToInt().coerceIn(-32768, 32767)

    /** Map 0.0..1.0 to 0..255 with rounding. Clamps before mapping. */
    private fun floatToTrigger(v: Float): Int =
        (v.coerceIn(0f, 1f) * 255f).roundToInt().coerceIn(0, 255)

    companion object {
        private const val TAG = "ShizukuGamepadInjector"
        // Distinct tag for per-event verbose axis logging so it can be
        // filtered independently from the bind/error stream. Matches the
        // "Motion" tag-suffix pattern used by [InputEvaluator] for the
        // same reason. Enable with:
        //   adb shell setprop log.tag.ShizukuGamepadInjector.Axes VERBOSE
        private const val TAG_AXES = "ShizukuGamepadInjector.Axes"
    }
}
