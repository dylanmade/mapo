package com.mappo.service.input.modes

import com.mappo.data.model.steam.InputSource
import com.mappo.service.input.AnalogEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Brick D.5 — focused unit tests for [DirectionalSwipeMode.evaluate]. Targets
 * the rate-threshold latch logic + hysteresis on each axis. The mode itself
 * is stateless (relies on `ctx.priorLatched` rather than singleton state) so
 * each test composes its scenario by constructing the appropriate prior
 * map directly — no `@Before` reset needed.
 *
 * Robolectric required because [DirectionalSwipeSettings.parse] uses
 * `org.json.JSONObject` (Android-platform).
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class DirectionalSwipeModeTest {

    private val emits = mutableListOf<Pair<String, Boolean>>()
    private val emit: (String, Boolean) -> Unit = { sub, isDown -> emits += sub to isDown }

    private fun ctx(
        priorUp: Boolean = false,
        priorDown: Boolean = false,
        priorLeft: Boolean = false,
        priorRight: Boolean = false,
        settingsJson: String = DirectionalSwipeMode.defaultSettingsJson(),
    ) = ModeContext(
        source = InputSource.GYRO,
        settingsJson = settingsJson,
        priorLatched = mapOf(
            "dpad_up" to priorUp,
            "dpad_down" to priorDown,
            "dpad_left" to priorLeft,
            "dpad_right" to priorRight,
        ),
        activeLayerIds = emptyList(),
    )

    /** Construct a gyro reading at a specific (pitch rate, yaw rate) — roll axis unused. */
    private fun gyro(pitch: Float, yaw: Float = 0f) = AnalogEvent(
        source = InputSource.GYRO,
        x = 0f,
        y = pitch,
        timestampMs = 0L,
        z = yaw,
    )

    @org.junit.Before
    fun resetSwipeState() {
        // The mode is now stateful (smoothing + scroll momentum); clear the
        // singleton so prior tests don't bleed in.
        DirectionalSwipeMode.resetState()
    }

    // ── Pitch axis (forward/back → up/down) ─────────────────────────────────

    @Test
    fun pitchForward_aboveThreshold_emitsDpadUpDown() {
        // pitch < -threshold (2.0) → dpad_up DOWN.
        DirectionalSwipeMode.evaluate(gyro(pitch = -3.0f), ctx(), emit, MouseEmitter.NOOP)
        assertEquals(listOf("dpad_up" to true), emits)
    }

    @Test
    fun pitchBack_aboveThreshold_emitsDpadDownDown() {
        DirectionalSwipeMode.evaluate(gyro(pitch = 3.0f), ctx(), emit, MouseEmitter.NOOP)
        assertEquals(listOf("dpad_down" to true), emits)
    }

    @Test
    fun pitchBelowThreshold_emitsNothing() {
        // Slow tilt — magnitude 0.5 < threshold 2.0.
        DirectionalSwipeMode.evaluate(gyro(pitch = -0.5f), ctx(), emit, MouseEmitter.NOOP)
        assertTrue("Expected no emit for slow tilt, got $emits", emits.isEmpty())
    }

    @Test
    fun pitchHysteresis_heldAboveReleaseFloor_doesNotRelease() {
        // Was up-latched. Rate drops from -3.0 to -0.8 — still below
        // -release_floor (-0.5), so latch holds, no release emit.
        DirectionalSwipeMode.evaluate(gyro(pitch = -0.8f), ctx(priorUp = true), emit, MouseEmitter.NOOP)
        assertTrue("Expected hold, got $emits", emits.isEmpty())
    }

    @Test
    fun pitchHysteresis_droppingBelowReleaseFloor_releases() {
        // Was up-latched. Rate drops to -0.1 — between -release_floor and 0.
        // Latch releases.
        DirectionalSwipeMode.evaluate(gyro(pitch = -0.1f), ctx(priorUp = true), emit, MouseEmitter.NOOP)
        assertEquals(listOf("dpad_up" to false), emits)
    }

    @Test
    fun pitchRecoilDoesNotFireOppositeDirection() {
        // Was up-latched. User decelerates and the device recoils slightly past
        // neutral (pitch = +0.3). +0.3 is below the threshold (2.0), so dpad_down
        // does NOT fire. Up releases. Critical hysteresis property — a single
        // flick should not produce two opposite emissions.
        DirectionalSwipeMode.evaluate(gyro(pitch = 0.3f), ctx(priorUp = true), emit, MouseEmitter.NOOP)
        assertEquals(listOf("dpad_up" to false), emits)
    }

    // ── Yaw axis (left/right → left/right) ──────────────────────────────────

    @Test
    fun yawLeft_aboveThreshold_emitsDpadLeftDown() {
        // Positive yaw (CCW around screen-normal = yaw left) → dpad_left.
        DirectionalSwipeMode.evaluate(gyro(pitch = 0f, yaw = 3.0f), ctx(), emit, MouseEmitter.NOOP)
        assertEquals(listOf("dpad_left" to true), emits)
    }

    @Test
    fun yawRight_aboveThreshold_emitsDpadRightDown() {
        DirectionalSwipeMode.evaluate(gyro(pitch = 0f, yaw = -3.0f), ctx(), emit, MouseEmitter.NOOP)
        assertEquals(listOf("dpad_right" to true), emits)
    }

    @Test
    fun yawBelowThreshold_emitsNothing() {
        DirectionalSwipeMode.evaluate(gyro(pitch = 0f, yaw = 1.0f), ctx(), emit, MouseEmitter.NOOP)
        assertTrue("Expected no emit for slow yaw, got $emits", emits.isEmpty())
    }

    @Test
    fun yawAndPitch_simultaneous_emitsBoth() {
        // Combined motion — fires both axes' directions.
        DirectionalSwipeMode.evaluate(gyro(pitch = -3.0f, yaw = 3.0f), ctx(), emit, MouseEmitter.NOOP)
        assertTrue("Expected dpad_up DOWN, got $emits", "dpad_up" to true in emits)
        assertTrue("Expected dpad_left DOWN, got $emits", "dpad_left" to true in emits)
        assertEquals(2, emits.size)
    }

    // ── Source filter ───────────────────────────────────────────────────────

    @Test
    fun nonGyroSource_isIgnored() {
        val stickReading = AnalogEvent(
            source = InputSource.LEFT_JOYSTICK,
            x = 1.0f, y = -3.0f, timestampMs = 0L,
        )
        DirectionalSwipeMode.evaluate(
            stickReading,
            ctx().copy(source = InputSource.LEFT_JOYSTICK),
            emit, MouseEmitter.NOOP,
        )
        assertTrue("Expected no emit for non-gyro source, got $emits", emits.isEmpty())
    }

    // ── Settings parse ──────────────────────────────────────────────────────

    @Test
    fun higherSensitivity_lowersThreshold_firesAtSmallerRates() {
        // Sensitivity 400% → threshold = 2.0 × 100/400 = 0.5, so a 1.0 rate fires
        // (it wouldn't at the default 100% / 2.0 threshold).
        val custom = """{"sensitivity":400}"""
        DirectionalSwipeMode.evaluate(gyro(pitch = -1.0f), ctx(settingsJson = custom), emit, MouseEmitter.NOOP)
        assertEquals(listOf("dpad_up" to true), emits)
    }

    @Test
    fun missingKeys_fallBackToDefaults() {
        // Empty JSON → use defaults. -3.0 rate still fires up.
        DirectionalSwipeMode.evaluate(gyro(pitch = -3.0f), ctx(settingsJson = "{}"), emit, MouseEmitter.NOOP)
        assertEquals(listOf("dpad_up" to true), emits)
    }

    @Test
    fun malformedJson_fallsBackToDefaults() {
        DirectionalSwipeMode.evaluate(
            gyro(pitch = -3.0f),
            ctx(settingsJson = "{not valid"),
            emit, MouseEmitter.NOOP,
        )
        assertEquals(listOf("dpad_up" to true), emits)
    }

    @Test
    fun sensitivity_scalesThresholdAndFloor() {
        // Threshold = BASE(2.0) × 100/sensitivity; release floor = threshold × 0.25.
        val parsed = DirectionalSwipeSettings.parse("""{"sensitivity":200}""")
        assertEquals(1.0f, parsed.rateThreshold, 1e-4f)
        assertEquals(0.25f, parsed.releaseFloor, 1e-4f)
    }

    @Test
    fun validInputs_areFourCardinalDirections() {
        assertEquals(
            setOf("dpad_up", "dpad_down", "dpad_left", "dpad_right"),
            DirectionalSwipeMode.validInputs(),
        )
    }

    @Test
    fun defaultSettings_matchSteamFlavoredDefaults() {
        val parsed = DirectionalSwipeSettings.parse(DirectionalSwipeMode.defaultSettingsJson())
        assertEquals(2.0f, parsed.rateThreshold, 1e-4f)
        assertEquals(0.5f, parsed.releaseFloor, 1e-4f)
    }
}
