package com.mapo.service.input

import android.os.SystemClock
import android.view.MotionEvent
import com.mapo.data.model.steam.InputSource
import kotlin.math.abs
import kotlin.math.max

/**
 * Normalized analog reading for one [InputSource] at a moment in time. Brick 6.2 owns
 * the data shape; analog modes from 6.3 onward consume it (Trigger reads [x] only, the
 * joystick / mouse modes read [x] + [y]).
 *
 * Coordinate conventions:
 *  - **Sticks / HAT**: [x] in [-1, 1], +x = right; [y] in [-1, 1], +y = down (matches
 *    raw Android axis sign for AXIS_Y / AXIS_RZ — flipped only when a downstream mode
 *    wants screen-up = +1).
 *  - **Triggers**: [x] in [0, 1], 0 = released, 1 = fully pulled. [y] is always 0.
 *
 * Pre-deadzone values are deliberate — the probe brick (6.2) logs every reading so we
 * can see total axis traffic. Modes that want clean signal apply [withDeadzone] (or
 * the per-mode deadzone settings landing in 6.4+) downstream.
 */
data class AnalogEvent(
    val source: InputSource,
    val x: Float,
    val y: Float,
    val timestampMs: Long,
    /**
     * Third axis component, defaulted to 0 and only meaningful for
     * [InputSource.GYRO] readings. The gyro sensor reports angular velocity
     * on three axes (roll, pitch, yaw); 2D-stick callers only need x/y and
     * leave `z` at its default. Directional Swipe is the one mode (so far)
     * that needs the yaw component to fire left/right edges from
     * twisting-the-device-around-the-vertical-axis motion. Everything else
     * ignores it.
     *
     * Defaulted to keep the existing 4-arg positional constructor compatible
     * across the codebase + tests; named-argument call sites can override it.
     */
    val z: Float = 0f,
    /**
     * Absolute device orientation (radians) at the moment of this reading,
     * sourced from the rotation-vector sensor. Only populated for
     * [InputSource.GYRO]; everywhere else defaults to 0.
     *
     * Tilt-based modes (Gyro to Joystick Deflection) subtract a captured
     * reference orientation from these to compute "tilt from rest" and map
     * the result to stick deflection. Rate-based gyro modes (Mouse / Camera)
     * ignore these and read `x` / `y` (rate) instead.
     *
     * Defaulted to keep tests using the 4-arg positional constructor working
     * across the codebase.
     */
    val tiltRollRad: Float = 0f,
    val tiltPitchRad: Float = 0f,
) {
    /**
     * Zero-out values whose magnitude is below [deadzone]. For sticks, both axes are
     * gated by the joint magnitude (`sqrt(x² + y²) < deadzone` → both → 0); for
     * triggers, only [x] matters.
     */
    fun withDeadzone(deadzone: Float): AnalogEvent = when {
        // Stick / HAT: gate on joint magnitude so a tiny y while x is large doesn't
        // get zeroed independently (which would feel snappy on diagonal pushes).
        y != 0f -> {
            val mag = kotlin.math.sqrt(x * x + y * y)
            if (mag < deadzone) copy(x = 0f, y = 0f) else this
        }
        // Trigger: single-axis gate.
        else -> if (abs(x) < deadzone) copy(x = 0f) else this
    }
}

/**
 * Extracts [AnalogEvent]s from raw Android [MotionEvent]s — the boundary between the
 * platform input API and the evaluator. Stateless on purpose: caller manages anything
 * temporal (rate-limiting, edge detection vs. continuous-stream interpretation).
 *
 * Axis → source mapping handles Android gamepad conventions including the alt-axis
 * fallbacks some controllers use (`AXIS_BRAKE` / `AXIS_GAS` for triggers).
 */
object MotionEventNormalizer {

    /**
     * Pull every analog source's value out of [event]. Returns one [AnalogEvent] per
     * source that has any axis backing on this device — values inside the deadzone
     * are NOT filtered here (intentional, for diagnostics). Apply
     * [AnalogEvent.withDeadzone] downstream when clean signal is what's wanted.
     *
     * Source coverage:
     *  - LEFT_JOYSTICK  ← `AXIS_X` / `AXIS_Y`
     *  - RIGHT_JOYSTICK ← `AXIS_Z` / `AXIS_RZ` (Android right-stick quirk)
     *  - DPAD           ← `AXIS_HAT_X` / `AXIS_HAT_Y` (dpad as analog hat — some pads)
     *  - LEFT_TRIGGER   ← max(`AXIS_LTRIGGER`, `AXIS_BRAKE`)
     *  - RIGHT_TRIGGER  ← max(`AXIS_RTRIGGER`, `AXIS_GAS`)
     */
    fun extract(event: MotionEvent, now: Long = SystemClock.uptimeMillis()): List<AnalogEvent> {
        // Use historical+current sample on the last sample only. For a probe-brick this
        // simpler path is fine; if 6.3+ needs sub-event resolution it can iterate over
        // historical samples via event.historySize.
        val out = ArrayList<AnalogEvent>(5)
        out += AnalogEvent(
            source = InputSource.LEFT_JOYSTICK,
            x = event.getAxisValue(MotionEvent.AXIS_X),
            y = event.getAxisValue(MotionEvent.AXIS_Y),
            timestampMs = now,
        )
        out += AnalogEvent(
            source = InputSource.RIGHT_JOYSTICK,
            x = event.getAxisValue(MotionEvent.AXIS_Z),
            y = event.getAxisValue(MotionEvent.AXIS_RZ),
            timestampMs = now,
        )
        out += AnalogEvent(
            source = InputSource.DPAD,
            x = event.getAxisValue(MotionEvent.AXIS_HAT_X),
            y = event.getAxisValue(MotionEvent.AXIS_HAT_Y),
            timestampMs = now,
        )
        out += AnalogEvent(
            source = InputSource.LEFT_TRIGGER,
            x = max(
                event.getAxisValue(MotionEvent.AXIS_LTRIGGER),
                event.getAxisValue(MotionEvent.AXIS_BRAKE),
            ),
            y = 0f,
            timestampMs = now,
        )
        out += AnalogEvent(
            source = InputSource.RIGHT_TRIGGER,
            x = max(
                event.getAxisValue(MotionEvent.AXIS_RTRIGGER),
                event.getAxisValue(MotionEvent.AXIS_GAS),
            ),
            y = 0f,
            timestampMs = now,
        )
        return out
    }
}
