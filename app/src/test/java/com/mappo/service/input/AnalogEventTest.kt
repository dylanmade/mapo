package com.mappo.service.input

import android.view.InputDevice
import android.view.MotionEvent
import com.mappo.data.model.steam.InputSource
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Brick 6.2: verifies the axis-to-[AnalogEvent] mapping that the motion-capture probe
 * and (later) the analog modes depend on. MotionEvent construction goes through
 * Robolectric — the SDK stub doesn't carry working `getAxisValue` plumbing.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class AnalogEventTest {

    @Test
    fun extract_returnsAllFiveAnalogSources() {
        // Even with everything centered (no axis set), extract returns one row per
        // analog source so the probe sees the full surface and downstream modes can
        // distinguish "this device doesn't have a right stick" from "right stick is
        // at zero." All five rows arrive at `0.00, 0.00`.
        val event = motionEvent { }

        val events = MotionEventNormalizer.extract(event, now = 1_000L)

        assertEquals(
            setOf(
                InputSource.LEFT_JOYSTICK,
                InputSource.RIGHT_JOYSTICK,
                InputSource.DPAD,
                InputSource.LEFT_TRIGGER,
                InputSource.RIGHT_TRIGGER,
            ),
            events.map { it.source }.toSet(),
        )
        for (e in events) {
            assertEquals(0f, e.x, 0.0001f)
            assertEquals(0f, e.y, 0.0001f)
            assertEquals(1_000L, e.timestampMs)
        }
    }

    @Test
    fun extract_leftStick_pullsAxisXY() {
        val event = motionEvent {
            setAxisValue(MotionEvent.AXIS_X, 0.50f)
            setAxisValue(MotionEvent.AXIS_Y, -0.75f)
        }

        val left = MotionEventNormalizer.extract(event).first { it.source == InputSource.LEFT_JOYSTICK }
        assertEquals(0.50f, left.x, 0.0001f)
        assertEquals(-0.75f, left.y, 0.0001f)
    }

    @Test
    fun extract_rightStick_pullsAxisZAndRZ() {
        // Android's right-stick quirk: Z + RZ rather than the expected X2/Y2.
        val event = motionEvent {
            setAxisValue(MotionEvent.AXIS_Z, 0.30f)
            setAxisValue(MotionEvent.AXIS_RZ, 0.20f)
        }

        val right = MotionEventNormalizer.extract(event).first { it.source == InputSource.RIGHT_JOYSTICK }
        assertEquals(0.30f, right.x, 0.0001f)
        assertEquals(0.20f, right.y, 0.0001f)
    }

    @Test
    fun extract_dpadHat_pullsHatXY() {
        val event = motionEvent {
            setAxisValue(MotionEvent.AXIS_HAT_X, 1f)
            setAxisValue(MotionEvent.AXIS_HAT_Y, 0f)
        }

        val dpad = MotionEventNormalizer.extract(event).first { it.source == InputSource.DPAD }
        assertEquals(1f, dpad.x, 0.0001f)
        assertEquals(0f, dpad.y, 0.0001f)
    }

    @Test
    fun extract_triggers_preferPrimaryAxisOverAlt() {
        // Most pads expose AXIS_LTRIGGER/RTRIGGER; some use AXIS_BRAKE/GAS. We take
        // the max so either pipeline produces a usable value.
        val event = motionEvent {
            setAxisValue(MotionEvent.AXIS_LTRIGGER, 0.40f)
            setAxisValue(MotionEvent.AXIS_BRAKE, 0.10f)
            setAxisValue(MotionEvent.AXIS_RTRIGGER, 0.10f)
            setAxisValue(MotionEvent.AXIS_GAS, 0.80f)
        }

        val left = MotionEventNormalizer.extract(event).first { it.source == InputSource.LEFT_TRIGGER }
        val right = MotionEventNormalizer.extract(event).first { it.source == InputSource.RIGHT_TRIGGER }
        assertEquals(0.40f, left.x, 0.0001f)
        assertEquals(0f, left.y, 0.0001f)
        assertEquals(0.80f, right.x, 0.0001f)
        assertEquals(0f, right.y, 0.0001f)
    }

    @Test
    fun withDeadzone_zerosStickValuesBelowJointMagnitude() {
        // Stick / HAT use a JOINT-magnitude deadzone (not per-axis) so diagonals
        // don't snap independently.
        val below = AnalogEvent(InputSource.LEFT_JOYSTICK, x = 0.03f, y = 0.03f, timestampMs = 0L)
            .withDeadzone(0.10f)
        assertEquals(0f, below.x, 0.0001f)
        assertEquals(0f, below.y, 0.0001f)

        // Joint magnitude ≈ 0.14 here, above the 0.10 deadzone — both axes pass through.
        val above = AnalogEvent(InputSource.LEFT_JOYSTICK, x = 0.10f, y = 0.10f, timestampMs = 0L)
            .withDeadzone(0.10f)
        assertEquals(0.10f, above.x, 0.0001f)
        assertEquals(0.10f, above.y, 0.0001f)
    }

    @Test
    fun withDeadzone_triggerIsSingleAxisGate() {
        val below = AnalogEvent(InputSource.LEFT_TRIGGER, x = 0.05f, y = 0f, timestampMs = 0L)
            .withDeadzone(0.10f)
        assertEquals(0f, below.x, 0.0001f)

        val above = AnalogEvent(InputSource.LEFT_TRIGGER, x = 0.15f, y = 0f, timestampMs = 0L)
            .withDeadzone(0.10f)
        assertEquals(0.15f, above.x, 0.0001f)
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /**
     * Build a single-pointer joystick MotionEvent with axis values set via [setup].
     * Robolectric provides a real MotionEvent implementation so getAxisValue works
     * as expected at test time.
     */
    private fun motionEvent(setup: MotionEvent.PointerCoords.() -> Unit): MotionEvent {
        val coords = MotionEvent.PointerCoords().apply(setup)
        val props = MotionEvent.PointerProperties().apply { id = 0 }
        return MotionEvent.obtain(
            /* downTime */ 0L,
            /* eventTime */ 0L,
            /* action */ MotionEvent.ACTION_MOVE,
            /* pointerCount */ 1,
            /* pointerProperties */ arrayOf(props),
            /* pointerCoords */ arrayOf(coords),
            /* metaState */ 0,
            /* buttonState */ 0,
            /* xPrecision */ 1f,
            /* yPrecision */ 1f,
            /* deviceId */ 0,
            /* edgeFlags */ 0,
            /* source */ InputDevice.SOURCE_JOYSTICK,
            /* flags */ 0,
        )
    }
}
