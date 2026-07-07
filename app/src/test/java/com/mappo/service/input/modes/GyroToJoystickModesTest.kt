package com.mappo.service.input.modes

import com.mappo.data.model.steam.InputSource
import com.mappo.service.input.AnalogEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Focused unit tests for [GyroToJoystickCameraMode] and
 * [GyroToJoystickDeflectionMode]. Verifies each mode reads its settings JSON,
 * routes to the correct gamepad stick (right for camera, left for deflection),
 * and respects the source filter. The output math lives in
 * [GyroJoystickCameraSettings] / [GyroJoystickDeflectionSettings]; here we test
 * the integration between mode and emitter.
 *
 * Robolectric required for the JSON-tolerant settings parse path (same reason
 * as the other settings tests — `org.json.JSONObject` is Android-platform).
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class GyroToJoystickModesTest {

    private class RecordingGamepadEmitter : GamepadEmitter {
        // Tracks source on every set call so we can assert correct routing
        // under the per-source additive cache.
        val leftStickCalls = mutableListOf<Triple<InputSource, Float, Float>>()
        val rightStickCalls = mutableListOf<Triple<InputSource, Float, Float>>()
        var leftTriggerCalls = 0
        var rightTriggerCalls = 0
        var dpadHatCalls = 0
        var clearSourceCalls = 0
        var buttonCalls = 0
        override fun setLeftStick(source: InputSource, x: Float, y: Float) {
            leftStickCalls += Triple(source, x, y)
        }
        override fun setRightStick(source: InputSource, x: Float, y: Float) {
            rightStickCalls += Triple(source, x, y)
        }
        override fun setLeftTrigger(source: InputSource, v: Float) { leftTriggerCalls++ }
        override fun setRightTrigger(source: InputSource, v: Float) { rightTriggerCalls++ }
        override fun setDpadHat(source: InputSource, x: Int, y: Int) { dpadHatCalls++ }
        override fun clearSource(source: InputSource) { clearSourceCalls++ }
        override fun setButton(btnCode: Int, pressed: Boolean): Boolean { buttonCalls++; return true }
        override fun setLeftStickOutput(x: Float, y: Float) {}
        override fun setRightStickOutput(x: Float, y: Float) {}
        override fun setLeftTriggerOutput(v: Float) {}
        override fun setRightTriggerOutput(v: Float) {}
        override fun setHatOutput(x: Int, y: Int) {}
        override fun clearOutputSticks() {}
    }

    private val gamepad = RecordingGamepadEmitter()
    private val mouse = MouseEmitter.NOOP
    private val digitalEmit: (String, Boolean) -> Unit = { _, _ ->
        error("digital emit not expected for gyro→stick mode")
    }

    private fun ctx(settingsJson: String) = ModeContext(
        source = InputSource.GYRO,
        settingsJson = settingsJson,
        priorLatched = emptyMap(),
        activeLayerIds = emptyList(),
        gamepad = gamepad,
    )

    private fun gyroReading(roll: Float, pitch: Float, timestampMs: Long = 0L) = AnalogEvent(
        source = InputSource.GYRO,
        x = roll,
        y = pitch,
        timestampMs = timestampMs,
    )

    /**
     * Convenience constructor for tilt-based events. Camera/Mouse use rate
     * fields (x, y); Deflection reads `tiltRollRad` / `tiltPitchRad`.
     */
    private fun tiltReading(rollRad: Float, pitchRad: Float, timestampMs: Long = 0L) = AnalogEvent(
        source = InputSource.GYRO,
        x = 0f,
        y = 0f,
        timestampMs = timestampMs,
        tiltRollRad = rollRad,
        tiltPitchRad = pitchRad,
    )

    @org.junit.Before
    fun resetDeflectionState() {
        // Deflection captures a reference orientation on first event;
        // each test starts from a clean slate so the prior test's
        // reference doesn't bleed in.
        GyroToJoystickDeflectionMode.resetState()
    }

    // ── GyroToJoystickCameraMode → right stick ──────────────────────────────

    @Test
    fun camera_atDefaults_drivesRightStickInCorrectDirection() {
        // Speed→output model: roll 1.0 + pitch 0.5 (default Yaw+Roll, no yaw) maps
        // to a fast turn → near-saturated right-stick output. Built-in -1 sign
        // correction makes both axes negative; horizontal (roll) dominates.
        GyroToJoystickCameraMode.evaluate(
            gyroReading(1.0f, 0.5f),
            ctx(GyroToJoystickCameraMode.defaultSettingsJson()),
            digitalEmit,
            mouse,
        )
        assertEquals(1, gamepad.rightStickCalls.size)
        val (src, ax, ay) = gamepad.rightStickCalls.single()
        assertEquals(InputSource.GYRO, src)
        assertTrue("expected ax<0, got $ax", ax < 0f)
        assertTrue("expected ay<0, got $ay", ay < 0f)
        assertTrue("horizontal should dominate, got ax=$ax ay=$ay", kotlin.math.abs(ax) > kotlin.math.abs(ay))
    }

    @Test
    fun camera_doesNotTouchLeftStick() {
        GyroToJoystickCameraMode.evaluate(
            gyroReading(1.0f, 1.0f),
            ctx(GyroToJoystickCameraMode.defaultSettingsJson()),
            digitalEmit,
            mouse,
        )
        assertEquals(0, gamepad.leftStickCalls.size)
        assertEquals(1, gamepad.rightStickCalls.size)
    }

    @Test
    fun camera_belowDeadzone_zeroesStick() {
        // Still calls setRightStick (with 0,0) so the emitter slot is
        // updated — important if the prior reading had non-zero velocity,
        // so the game doesn't see lingering deflection after the user
        // stops rotating.
        GyroToJoystickCameraMode.evaluate(
            gyroReading(0.01f, 0.01f),
            ctx(GyroToJoystickCameraMode.defaultSettingsJson()),
            digitalEmit,
            mouse,
        )
        val (_, ax, ay) = gamepad.rightStickCalls.single()
        assertEquals(0f, ax, EPSILON)
        assertEquals(0f, ay, EPSILON)
    }

    @Test
    fun camera_aboveSaturation_outputsMaxMagnitude() {
        // A very fast diagonal flick saturates output at maxOutput (100%). With
        // the circular response a 45° flick splits the unit magnitude across both
        // axes (~0.707 each), not 1.0 per axis. Built-in -1 sign correction flips
        // the signs: roll +5 → ax<0, pitch -5 → ay>0.
        GyroToJoystickCameraMode.evaluate(
            gyroReading(5.0f, -5.0f),
            ctx(GyroToJoystickCameraMode.defaultSettingsJson()),
            digitalEmit,
            mouse,
        )
        val (_, ax, ay) = gamepad.rightStickCalls.single()
        assertTrue("expected ax<0, got $ax", ax < 0f)
        assertTrue("expected ay>0, got $ay", ay > 0f)
        val mag = kotlin.math.sqrt(ax * ax + ay * ay)
        assertEquals("saturated magnitude ≈ 1.0", 1.0f, mag, 0.02f)
    }

    // ── GyroToJoystickDeflectionMode → left stick (tilt-based) ──────────────

    @Test
    fun deflection_firstEvent_capturesReferenceAndEmitsZero() {
        // No prior reference. First event captures the user's natural
        // holding angle as neutral and emits zero stick deflection. The
        // game sees a clean (0, 0) on the left stick's GYRO contribution
        // until the user actually tilts away from the captured reference.
        val ctx = ctx(GyroToJoystickDeflectionMode.defaultSettingsJson())
        GyroToJoystickDeflectionMode.evaluate(
            tiltReading(rollRad = 0.5f, pitchRad = -0.3f),
            ctx, digitalEmit, mouse,
        )
        val (src, ax, ay) = gamepad.leftStickCalls.single()
        assertEquals(InputSource.GYRO, src)
        assertEquals(0f, ax, EPSILON)
        assertEquals(0f, ay, EPSILON)
        val ref = GyroToJoystickDeflectionMode.referenceFor(InputSource.GYRO)
        assertEquals(0.5f, ref!!.first, EPSILON)
        assertEquals(-0.3f, ref.second, EPSILON)
    }

    @Test
    fun deflection_tiltFromReference_drivesLeftStickProportionally() {
        // Reference captured at (roll=0.5, pitch=-0.3). User tilts by +0.1 roll
        // and a larger pitch delta. Angle→output model (min 2° / max 45°): roll
        // drives +ax, the sign-corrected pitch delta drives -ay, and the bigger
        // pitch delta dominates the magnitude.
        val ctx = ctx(GyroToJoystickDeflectionMode.defaultSettingsJson())
        GyroToJoystickDeflectionMode.evaluate(
            tiltReading(rollRad = 0.5f, pitchRad = -0.3f), ctx, digitalEmit, mouse,
        ) // seed reference
        GyroToJoystickDeflectionMode.evaluate(
            tiltReading(rollRad = 0.6f, pitchRad = -0.1f), ctx, digitalEmit, mouse,
        )
        val (_, ax, ay) = gamepad.leftStickCalls.last()
        assertTrue("expected ax>0, got $ax", ax > 0f)
        assertTrue("expected ay<0, got $ay", ay < 0f)
        assertTrue("pitch delta should dominate, got ax=$ax ay=$ay", kotlin.math.abs(ay) > kotlin.math.abs(ax))
    }

    @Test
    fun deflection_atReference_emitsZero() {
        // User holding exactly at the captured neutral orientation —
        // delta is (0, 0), stick fully neutral. Critical property for "no
        // phantom drift" when the device is steady.
        val ctx = ctx(GyroToJoystickDeflectionMode.defaultSettingsJson())
        GyroToJoystickDeflectionMode.evaluate(
            tiltReading(rollRad = 1.2f, pitchRad = -0.7f), ctx, digitalEmit, mouse,
        )
        GyroToJoystickDeflectionMode.evaluate(
            tiltReading(rollRad = 1.2f, pitchRad = -0.7f), ctx, digitalEmit, mouse,
        )
        val (_, ax, ay) = gamepad.leftStickCalls.last()
        assertEquals(0f, ax, EPSILON)
        assertEquals(0f, ay, EPSILON)
    }

    @Test
    fun deflection_largeTilt_saturatesAtUnitMagnitude() {
        // A large tilt past the max deflection angle (45°) saturates output. With
        // the circular response a 45° diagonal splits the unit magnitude across
        // both axes (~0.707 each), so the magnitude — not each axis — hits 1.0.
        // Roll +1.0 → +ax; sign-corrected pitch (-1.0 vs ref 0) → +ay.
        val ctx = ctx(GyroToJoystickDeflectionMode.defaultSettingsJson())
        GyroToJoystickDeflectionMode.evaluate(
            tiltReading(rollRad = 0f, pitchRad = 0f), ctx, digitalEmit, mouse,
        )
        GyroToJoystickDeflectionMode.evaluate(
            tiltReading(rollRad = 1.0f, pitchRad = -1.0f), ctx, digitalEmit, mouse,
        )
        val (_, ax, ay) = gamepad.leftStickCalls.last()
        assertTrue("expected ax>0, got $ax", ax > 0f)
        assertTrue("expected ay>0, got $ay", ay > 0f)
        val mag = kotlin.math.sqrt(ax * ax + ay * ay)
        assertEquals("saturated magnitude ≈ 1.0", 1.0f, mag, 0.02f)
    }

    @Test
    fun deflection_resetState_drops_reference_for_recapture() {
        val ctx = ctx(GyroToJoystickDeflectionMode.defaultSettingsJson())
        GyroToJoystickDeflectionMode.evaluate(
            tiltReading(rollRad = 0.5f, pitchRad = -0.3f), ctx, digitalEmit, mouse,
        )
        assertEquals(0.5f, GyroToJoystickDeflectionMode.referenceFor(InputSource.GYRO)!!.first, EPSILON)
        GyroToJoystickDeflectionMode.resetState()
        org.junit.Assert.assertNull(GyroToJoystickDeflectionMode.referenceFor(InputSource.GYRO))
        // Next event captures a fresh reference at the new value.
        GyroToJoystickDeflectionMode.evaluate(
            tiltReading(rollRad = 0.9f, pitchRad = 0.1f), ctx, digitalEmit, mouse,
        )
        assertEquals(0.9f, GyroToJoystickDeflectionMode.referenceFor(InputSource.GYRO)!!.first, EPSILON)
    }

    @Test
    fun deflection_doesNotTouchRightStick() {
        val ctx = ctx(GyroToJoystickDeflectionMode.defaultSettingsJson())
        GyroToJoystickDeflectionMode.evaluate(
            tiltReading(rollRad = 0f, pitchRad = 0f), ctx, digitalEmit, mouse,
        )
        GyroToJoystickDeflectionMode.evaluate(
            tiltReading(rollRad = 0.2f, pitchRad = 0.2f), ctx, digitalEmit, mouse,
        )
        assertEquals(0, gamepad.rightStickCalls.size)
        assertEquals(2, gamepad.leftStickCalls.size)
    }

    // ── Source filter ───────────────────────────────────────────────────────

    @Test
    fun nonGyroSource_isIgnored_camera() {
        val stickReading = AnalogEvent(
            source = InputSource.LEFT_JOYSTICK,
            x = 1.0f, y = 1.0f, timestampMs = 0L,
        )
        GyroToJoystickCameraMode.evaluate(
            stickReading,
            ctx(GyroToJoystickCameraMode.defaultSettingsJson()).copy(source = InputSource.LEFT_JOYSTICK),
            digitalEmit,
            mouse,
        )
        assertEquals(0, gamepad.rightStickCalls.size)
        assertEquals(0, gamepad.leftStickCalls.size)
    }

    @Test
    fun nonGyroSource_isIgnored_deflection() {
        val stickReading = AnalogEvent(
            source = InputSource.RIGHT_JOYSTICK,
            x = 1.0f, y = 1.0f, timestampMs = 0L,
        )
        GyroToJoystickDeflectionMode.evaluate(
            stickReading,
            ctx(GyroToJoystickDeflectionMode.defaultSettingsJson()).copy(source = InputSource.RIGHT_JOYSTICK),
            digitalEmit,
            mouse,
        )
        assertEquals(0, gamepad.rightStickCalls.size)
        assertEquals(0, gamepad.leftStickCalls.size)
    }

    // ── Settings-aware behavior ─────────────────────────────────────────────

    @Test
    fun camera_sendToLeft_drivesLeftStick() {
        // send_to_joystick=left routes the camera output to the left stick.
        GyroToJoystickCameraMode.evaluate(
            gyroReading(1.0f, 0f),
            ctx("""{"send_to_joystick":"left"}"""),
            digitalEmit,
            mouse,
        )
        assertEquals(1, gamepad.leftStickCalls.size)
        assertEquals(0, gamepad.rightStickCalls.size)
        val (_, ax, _) = gamepad.leftStickCalls.single()
        assertTrue("expected non-zero horizontal output, got $ax", ax != 0f)
    }

    @Test
    fun camera_invertY_flipsPitchSignOnly() {
        // toAxis with invert_y=true returns ay = -0.5 × 0.8 = -0.4. Camera's
        // built-in -1 sign correction then negates again → final ay = +0.4.
        // invert_y cancels the built-in correction on the y axis.
        GyroToJoystickCameraMode.evaluate(
            gyroReading(0f, 0.5f),
            ctx("""{"invert_y":true}"""),
            digitalEmit,
            mouse,
        )
        val (_, _, ay) = gamepad.rightStickCalls.single()
        assertTrue("expected positive ay when invert_y=true (cancels built-in -1), got $ay", ay > 0f)
    }

    // ── Contract: doesn't touch unrelated emitter paths ─────────────────────

    @Test
    fun gyro_doesNotTouchTriggersDpadOrButtons() {
        GyroToJoystickCameraMode.evaluate(
            gyroReading(0.5f, 0.5f),
            ctx(GyroToJoystickCameraMode.defaultSettingsJson()),
            digitalEmit,
            mouse,
        )
        GyroToJoystickDeflectionMode.evaluate(
            gyroReading(0.5f, 0.5f),
            ctx(GyroToJoystickDeflectionMode.defaultSettingsJson()),
            digitalEmit,
            mouse,
        )
        assertEquals(0, gamepad.leftTriggerCalls)
        assertEquals(0, gamepad.rightTriggerCalls)
        assertEquals(0, gamepad.dpadHatCalls)
        assertEquals(0, gamepad.buttonCalls)
        assertEquals(0, gamepad.clearSourceCalls)
    }

    @Test
    fun validInputs_areEmpty() {
        // No sub-inputs on gyro→stick modes; chord-gating via activator layers.
        assertEquals(emptySet<String>(), GyroToJoystickCameraMode.validInputs())
        assertEquals(emptySet<String>(), GyroToJoystickDeflectionMode.validInputs())
    }

    companion object {
        private const val EPSILON = 0.0001f
    }
}
