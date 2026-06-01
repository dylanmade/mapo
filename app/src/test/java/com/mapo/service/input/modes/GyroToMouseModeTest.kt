package com.mapo.service.input.modes

import com.mapo.data.model.steam.InputSource
import com.mapo.service.input.AnalogEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Brick D.3 — focused unit tests for [GyroToMouseMode.evaluate]. Verifies the
 * mode reads its settings JSON, calls [MouseEmitter.setStickVelocity] with the
 * GYRO source, and skips events whose source isn't GYRO. The
 * [GyroToMouseSettings] math itself is covered separately in
 * [GyroToMouseSettingsTest]; here we test the *integration* between mode and
 * emitter.
 *
 * Robolectric required for the JSON-tolerant settings parse path (see
 * [GyroToMouseSettingsTest] for context on why).
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class GyroToMouseModeTest {

    private class RecordingEmitter : MouseEmitter {
        val velocityCalls = mutableListOf<Triple<InputSource, Float, Float>>()
        var absoluteCalls = 0
        var clearAbsoluteCalls = 0
        var clearAllCalls = 0
        override fun setStickVelocity(source: InputSource, vxPxPerSec: Float, vyPxPerSec: Float) {
            velocityCalls += Triple(source, vxPxPerSec, vyPxPerSec)
        }
        override fun setStickAbsoluteTarget(source: InputSource, xFrac: Float, yFrac: Float) {
            absoluteCalls++
        }
        override fun clearStickAbsoluteTarget(source: InputSource) {
            clearAbsoluteCalls++
        }
        override fun clearAllVelocities() {
            clearAllCalls++
        }
    }

    private val mouse = RecordingEmitter()
    private val digitalEmit: (String, Boolean) -> Unit = { _, _ -> error("digital emit not expected for gyro mode") }

    private fun ctx(settingsJson: String = GyroToMouseMode.defaultSettingsJson()) = ModeContext(
        source = InputSource.GYRO,
        settingsJson = settingsJson,
        priorLatched = emptyMap(),
        activeLayerIds = emptyList(),
    )

    private fun gyroReading(yawRadPerSec: Float, pitchRadPerSec: Float) = AnalogEvent(
        source = InputSource.GYRO,
        x = yawRadPerSec,
        y = pitchRadPerSec,
        timestampMs = 0L,
    )

    // ── Happy path ──────────────────────────────────────────────────────────

    @Test
    fun gyroReading_atDefaultSensitivity_drivesCursorOnYawAndPitch() {
        GyroToMouseMode.evaluate(gyroReading(1.0f, 0.5f), ctx(), digitalEmit, mouse)
        assertEquals(1, mouse.velocityCalls.size)
        val (source, vx, vy) = mouse.velocityCalls.single()
        assertEquals(InputSource.GYRO, source)
        // Built-in -1 sign correction on both axes — see toVelocity KDoc.
        assertEquals(-GyroToMouseSettings.DEFAULT_SENSITIVITY_X, vx, EPSILON)
        assertEquals(-0.5f * GyroToMouseSettings.DEFAULT_SENSITIVITY_Y, vy, EPSILON)
    }

    @Test
    fun gyroReading_belowDeadzone_setsZeroVelocity() {
        // The mode still calls setStickVelocity (with zeros) so the emitter's
        // integration loop sees the GYRO slot zero out and exits cleanly when
        // the user releases the device. Not skipping the call is load-bearing
        // for the loop's exit condition.
        GyroToMouseMode.evaluate(gyroReading(0.01f, 0.01f), ctx(), digitalEmit, mouse)
        val (source, vx, vy) = mouse.velocityCalls.single()
        assertEquals(InputSource.GYRO, source)
        assertEquals(0f, vx, EPSILON)
        assertEquals(0f, vy, EPSILON)
    }

    @Test
    fun gyroReading_pureYaw_leavesPitchAtZero() {
        GyroToMouseMode.evaluate(gyroReading(0.8f, 0f), ctx(), digitalEmit, mouse)
        val (_, vx, vy) = mouse.velocityCalls.single()
        assertNotEquals(0f, vx)
        assertEquals(0f, vy, EPSILON)
    }

    // ── Non-GYRO source filter ──────────────────────────────────────────────

    @Test
    fun stickReading_isIgnored() {
        // Defensive — the mode is gyro-source-only in the picker, but a stick's
        // -1..+1 normalized values fed into rad/sec math would produce absurd
        // cursor velocities. Source filter short-circuits.
        val stickReading = AnalogEvent(
            source = InputSource.LEFT_JOYSTICK,
            x = 1.0f,
            y = 1.0f,
            timestampMs = 0L,
        )
        GyroToMouseMode.evaluate(stickReading, ctx(), digitalEmit, mouse)
        assertEquals(0, mouse.velocityCalls.size)
    }

    // ── Settings-aware behavior ─────────────────────────────────────────────

    @Test
    fun customSensitivity_appliesPerAxis() {
        val ctx = ctx("""{"sensitivity_x":1000,"sensitivity_y":250,"deadzone":0.05}""")
        GyroToMouseMode.evaluate(gyroReading(1.0f, 1.0f), ctx, digitalEmit, mouse)
        val (_, vx, vy) = mouse.velocityCalls.single()
        // Built-in -1 sign correction on both axes — see toVelocity KDoc.
        assertEquals(-1000f, vx, EPSILON)
        assertEquals(-250f, vy, EPSILON)
    }

    @Test
    fun invertY_flipsPitchSign() {
        // With the built-in correction, invert_y=true flips the *corrected*
        // sign — so default-orientation pitch produces vy < 0 (corrected),
        // and invert_y=true produces vy > 0.
        val ctx = ctx("""{"invert_y":true}""")
        GyroToMouseMode.evaluate(gyroReading(0f, 1.0f), ctx, digitalEmit, mouse)
        val (_, _, vy) = mouse.velocityCalls.single()
        assertTrue("expected positive vy when invert_y=true (cancels built-in -1), got $vy", vy > 0f)
    }

    // ── Contract: digital + absolute paths are untouched ────────────────────

    @Test
    fun gyroMode_doesNotTouchAbsoluteOrClearPaths() {
        // GyroToMouseMode is a continuous-velocity mode only. Absolute-target
        // and clear-all-velocity paths belong to Mouse Region and flushAnalog
        // respectively — they should never be invoked from here.
        GyroToMouseMode.evaluate(gyroReading(0.5f, 0.5f), ctx(), digitalEmit, mouse)
        assertEquals(0, mouse.absoluteCalls)
        assertEquals(0, mouse.clearAbsoluteCalls)
        assertEquals(0, mouse.clearAllCalls)
    }

    @Test
    fun validInputs_isEmpty() {
        // No sub-inputs on gyro modes; chord-gating happens via activator layers.
        assertEquals(emptySet<String>(), GyroToMouseMode.validInputs())
    }

    companion object {
        private const val EPSILON = 0.0001f
    }
}
