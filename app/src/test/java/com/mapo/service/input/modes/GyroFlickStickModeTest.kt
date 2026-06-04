package com.mapo.service.input.modes

import com.mapo.data.model.steam.BindingMode
import com.mapo.data.model.steam.InputSource
import com.mapo.service.input.AnalogEvent
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Behavior tests for [GyroFlickStickMode]. Mapo extension — no Steam
 * equivalent. Covers:
 *  - source filter (gyro only)
 *  - drift suppression (yaw rate below deadzone emits nothing)
 *  - continuous yaw → mouse_dx velocity with sign flip
 *  - flick burst fires once per gesture (re-arm on deadzone-cross)
 *  - settings parsing
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class GyroFlickStickModeTest {

    private val mouse = mockk<MouseEmitter>(relaxed = true)
    private val gamepad = mockk<GamepadEmitter>(relaxed = true)
    private val digitalEmit: (String, Boolean) -> Unit = { _, _ -> }

    @After
    fun reset() {
        GyroFlickStickMode.resetState()
    }

    private fun ctx(settingsJson: String = "") = ModeContext(
        source = InputSource.GYRO,
        settingsJson = settingsJson,
        priorLatched = emptyMap(),
        activeLayerIds = emptyList(),
        gamepad = gamepad,
    )

    private fun gyroReading(yawRadPerSec: Float, timestampMs: Long = 0L) = AnalogEvent(
        source = InputSource.GYRO,
        x = 0f,
        y = 0f,
        timestampMs = timestampMs,
        z = yawRadPerSec,
    )

    // ── Metadata ─────────────────────────────────────────────────────────────

    @Test
    fun mode_isGyroFlickStick() {
        assertEquals(BindingMode.GYRO_FLICK_STICK, GyroFlickStickMode.mode)
    }

    @Test
    fun validInputs_areEmpty() {
        assertEquals(emptySet<String>(), GyroFlickStickMode.validInputs())
    }

    @Test
    fun nonGyroSource_isNoOp() {
        GyroFlickStickMode.evaluate(
            reading = AnalogEvent(
                source = InputSource.RIGHT_JOYSTICK,
                x = 1f,
                y = 0f,
                timestampMs = 0L,
                z = 5f,  // would trigger a flick if source matched
            ),
            ctx = ctx().copy(source = InputSource.RIGHT_JOYSTICK),
            digitalEmit = digitalEmit,
            mouse = mouse,
        )
        verify(exactly = 0) { mouse.setStickVelocity(any(), any(), any()) }
        verify(exactly = 0) { mouse.scheduleSmoothDelta(any(), any(), any()) }
    }

    // ── Deadzone / drift suppression ─────────────────────────────────────────

    @Test
    fun belowDeadzone_zeroesVelocityAndReArms() {
        // Below the 0.05 rad/sec default deadzone — must zero the source's
        // velocity slot. No flick burst.
        GyroFlickStickMode.evaluate(
            reading = gyroReading(yawRadPerSec = 0.02f),
            ctx = ctx(),
            digitalEmit = digitalEmit,
            mouse = mouse,
        )
        verify(exactly = 1) { mouse.setStickVelocity(InputSource.GYRO, 0f, 0f) }
        verify(exactly = 0) { mouse.scheduleSmoothDelta(any(), any(), any()) }
    }

    // ── Continuous yaw → mouse_dx ────────────────────────────────────────────

    @Test
    fun slowYawAboveDeadzone_emitsProportionalVelocity_noFlick() {
        // Yaw rate above deadzone but below flick threshold → continuous
        // velocity only, no flick burst.
        val vxSlot = slot<Float>()
        every {
            mouse.setStickVelocity(InputSource.GYRO, capture(vxSlot), 0f)
        } returns Unit
        GyroFlickStickMode.evaluate(
            reading = gyroReading(yawRadPerSec = 0.5f),  // slow turn, < 2.0 threshold
            ctx = ctx(),
            digitalEmit = digitalEmit,
            mouse = mouse,
        )
        verify(exactly = 0) { mouse.scheduleSmoothDelta(any(), any(), any()) }
        // Sign-flipped: +z (CCW = look-left intent) → −dx (mouse_dx convention).
        assertTrue("positive yaw → negative dx velocity", vxSlot.captured < 0f)
        val expected = -0.5f * GyroFlickStickSettings.DEFAULT_VELOCITY_PX_PER_RAD
        assertEquals(expected, vxSlot.captured, 1f)
    }

    @Test
    fun negativeYawAboveDeadzone_emitsPositiveDxVelocity() {
        val vxSlot = slot<Float>()
        every {
            mouse.setStickVelocity(InputSource.GYRO, capture(vxSlot), 0f)
        } returns Unit
        GyroFlickStickMode.evaluate(
            reading = gyroReading(yawRadPerSec = -0.5f),
            ctx = ctx(),
            digitalEmit = digitalEmit,
            mouse = mouse,
        )
        assertTrue("negative yaw → positive dx velocity", vxSlot.captured > 0f)
    }

    // ── Flick burst ──────────────────────────────────────────────────────────

    @Test
    fun rapidYaw_firesFlickBurstOnce() {
        val burstDxSlot = slot<Float>()
        val burstDurSlot = slot<Long>()
        every {
            mouse.scheduleSmoothDelta(capture(burstDxSlot), 0f, capture(burstDurSlot))
        } returns Unit
        // First rapid yaw event — armed → fires.
        GyroFlickStickMode.evaluate(
            reading = gyroReading(yawRadPerSec = 5f),
            ctx = ctx(),
            digitalEmit = digitalEmit,
            mouse = mouse,
        )
        verify(exactly = 1) { mouse.scheduleSmoothDelta(any(), 0f, any()) }
        // Burst sign: +z → −dx (look-left).
        assertTrue("rightward (negative-z) yaw burst is positive; left is negative", burstDxSlot.captured < 0f)
        val expectedAngle = -GyroFlickStickSettings.DEFAULT_FLICK_ANGLE_RAD *
            GyroFlickStickSettings.DEFAULT_VELOCITY_PX_PER_RAD
        assertEquals(expectedAngle, burstDxSlot.captured, 1f)
        assertEquals(GyroFlickStickSettings.DEFAULT_FLICK_TIME_MS.toLong(), burstDurSlot.captured)
    }

    @Test
    fun sustainedRapidYaw_firesOnlyOneFlick_thenContinuousOnly() {
        // First rapid yaw → flick fires + velocity. Sustained rapid yaw on
        // subsequent events → continuous velocity only (no re-fire).
        GyroFlickStickMode.evaluate(
            reading = gyroReading(yawRadPerSec = 5f, timestampMs = 0L),
            ctx = ctx(),
            digitalEmit = digitalEmit,
            mouse = mouse,
        )
        GyroFlickStickMode.evaluate(
            reading = gyroReading(yawRadPerSec = 5f, timestampMs = 10L),
            ctx = ctx(),
            digitalEmit = digitalEmit,
            mouse = mouse,
        )
        GyroFlickStickMode.evaluate(
            reading = gyroReading(yawRadPerSec = 5f, timestampMs = 20L),
            ctx = ctx(),
            digitalEmit = digitalEmit,
            mouse = mouse,
        )
        verify(exactly = 1) { mouse.scheduleSmoothDelta(any(), any(), any()) }
        verify(exactly = 3) { mouse.setStickVelocity(InputSource.GYRO, any(), 0f) }
    }

    @Test
    fun returnToDeadzone_thenRapidYawAgain_firesSecondFlick() {
        // First flick → ride into deadzone (re-arm) → second flick must fire.
        GyroFlickStickMode.evaluate(
            reading = gyroReading(yawRadPerSec = 5f, timestampMs = 0L),
            ctx = ctx(),
            digitalEmit = digitalEmit,
            mouse = mouse,
        )
        // Drop below deadzone to re-arm.
        GyroFlickStickMode.evaluate(
            reading = gyroReading(yawRadPerSec = 0.01f, timestampMs = 100L),
            ctx = ctx(),
            digitalEmit = digitalEmit,
            mouse = mouse,
        )
        // Second rapid yaw → fires.
        GyroFlickStickMode.evaluate(
            reading = gyroReading(yawRadPerSec = 5f, timestampMs = 200L),
            ctx = ctx(),
            digitalEmit = digitalEmit,
            mouse = mouse,
        )
        verify(exactly = 2) { mouse.scheduleSmoothDelta(any(), any(), any()) }
    }

    @Test
    fun belowThresholdButAboveDeadzone_doesNotFireFlick_evenWhenArmed() {
        // Yaw rate is above the drift deadzone but below the flick
        // threshold — should NOT fire a burst.
        GyroFlickStickMode.evaluate(
            reading = gyroReading(yawRadPerSec = 1.0f),  // < 2.0 default threshold
            ctx = ctx(),
            digitalEmit = digitalEmit,
            mouse = mouse,
        )
        verify(exactly = 0) { mouse.scheduleSmoothDelta(any(), any(), any()) }
    }

    @Test
    fun resetState_reArmsSourceForNextFlick() {
        GyroFlickStickMode.evaluate(
            reading = gyroReading(yawRadPerSec = 5f, timestampMs = 0L),
            ctx = ctx(),
            digitalEmit = digitalEmit,
            mouse = mouse,
        )
        // Mid-gesture: state still says "disarmed." resetState clears
        // that.
        GyroFlickStickMode.resetState()
        // Next rapid yaw at the SAME magnitude must fire again.
        GyroFlickStickMode.evaluate(
            reading = gyroReading(yawRadPerSec = 5f, timestampMs = 10L),
            ctx = ctx(),
            digitalEmit = digitalEmit,
            mouse = mouse,
        )
        verify(exactly = 2) { mouse.scheduleSmoothDelta(any(), any(), any()) }
    }

    // ── Settings parsing ─────────────────────────────────────────────────────

    @Test
    fun settings_parse_emptyJson_returnsDefaults() {
        assertEquals(GyroFlickStickSettings.DEFAULTS, GyroFlickStickSettings.parse(""))
    }

    @Test
    fun settings_parse_malformedJson_returnsDefaults() {
        assertEquals(GyroFlickStickSettings.DEFAULTS, GyroFlickStickSettings.parse("{not json"))
    }

    @Test
    fun settings_parse_partialOverridesPreserveDefaults() {
        val s = GyroFlickStickSettings.parse("""{"flick_threshold":3.0}""")
        assertEquals(3.0f, s.flickThreshold, 1e-6f)
        assertEquals(GyroFlickStickSettings.DEFAULT_VELOCITY_PX_PER_RAD, s.velocityPxPerRad, 1e-6f)
        assertEquals(GyroFlickStickSettings.DEFAULT_DEADZONE, s.deadzone, 1e-6f)
    }

    @Test
    fun settings_parse_clampsNegativeValuesAtBoundaries() {
        val s = GyroFlickStickSettings.parse(
            """{"deadzone":-1,"velocity":-5,"flick_threshold":-2,"flick_time":-100}"""
        )
        assertTrue("deadzone clamps to ≥ 0", s.deadzone >= 0f)
        assertTrue("velocity clamps to ≥ 1", s.velocityPxPerRad >= 1f)
        assertTrue("flick_threshold clamps to ≥ 0", s.flickThreshold >= 0f)
        assertTrue("flick_time clamps to ≥ 1", s.flickTimeMs >= 1)
    }

    @Test
    fun defaultJson_roundTripsThroughParse() {
        assertEquals(
            GyroFlickStickSettings.DEFAULTS,
            GyroFlickStickSettings.parse(GyroFlickStickSettings.DEFAULT_JSON),
        )
    }
}
