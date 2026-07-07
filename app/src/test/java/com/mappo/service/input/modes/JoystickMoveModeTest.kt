package com.mappo.service.input.modes

import com.mappo.data.model.steam.InputSource
import com.mappo.service.input.AnalogEvent
import com.mappo.service.input.HapticIntensity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Evaluate-level tests for [JoystickMoveMode] — specifically the outer-ring
 * command (Chebyshev radius vs. the wide-corner Euclidean band) and the
 * outer-ring haptic detent. The analog shaping math is covered in
 * [StickToAxisSettingsTest]; the shared output knobs in JoystickOutputSettingsTest.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class JoystickMoveModeTest {

    private class RecordingGamepadEmitter : GamepadEmitter {
        override fun setLeftStick(source: InputSource, x: Float, y: Float) {}
        override fun setRightStick(source: InputSource, x: Float, y: Float) {}
        override fun setLeftTrigger(source: InputSource, v: Float) {}
        override fun setRightTrigger(source: InputSource, v: Float) {}
        override fun setDpadHat(source: InputSource, x: Int, y: Int) {}
        override fun clearSource(source: InputSource) {}
        override fun setButton(btnCode: Int, pressed: Boolean): Boolean = true
        override fun setLeftStickOutput(x: Float, y: Float) {}
        override fun setRightStickOutput(x: Float, y: Float) {}
        override fun setLeftTriggerOutput(v: Float) {}
        override fun setRightTriggerOutput(v: Float) {}
        override fun setHatOutput(x: Int, y: Int) {}
        override fun clearOutputSticks() {}
    }

    private val gamepad = RecordingGamepadEmitter()
    private val edges = mutableListOf<Pair<String, Boolean>>()
    private val buzzes = mutableListOf<HapticIntensity>()

    private fun ctx(settingsJson: String, priorOuterRing: Boolean = false) = ModeContext(
        source = InputSource.LEFT_JOYSTICK,
        settingsJson = settingsJson,
        priorLatched = if (priorOuterRing) mapOf("outer_ring" to true) else emptyMap(),
        activeLayerIds = emptyList(),
        gamepad = gamepad,
        haptic = { buzzes += it },
    )

    private fun reading(x: Float, y: Float) = AnalogEvent(
        source = InputSource.LEFT_JOYSTICK, x = x, y = y, timestampMs = 0L,
    )

    private fun evaluate(x: Float, y: Float, json: String, priorOuterRing: Boolean = false) {
        JoystickMoveMode.evaluate(
            reading(x, y),
            ctx(json, priorOuterRing),
            digitalEmit = { sub, down -> edges += sub to down },
            mouse = MouseEmitter.NOOP,
        )
    }

    @Test
    fun outerRing_diagonalBelowEdge_doesNotFire_chebyshevNotEuclidean() {
        // command_radius default 25000/32767 ≈ 0.763. A 0.6/0.6 diagonal has
        // Euclidean magnitude ≈ 0.85 (would trip the old clamped-magnitude ring)
        // but Chebyshev = 0.6 < 0.763 → must NOT fire.
        evaluate(0.6f, 0.6f, "{}")
        assertTrue("outer_ring should not fire below the Chebyshev radius", edges.isEmpty())
        assertTrue(buzzes.isEmpty())
    }

    @Test
    fun outerRing_axisReachesEdge_fires() {
        // One axis past the default radius → fires.
        evaluate(0.9f, 0.1f, "{}")
        assertEquals(listOf("outer_ring" to true), edges)
    }

    @Test
    fun outerRing_maxRadius_requiresFullAxisDeflection() {
        val maxRadius = """{"command_radius":32767}"""
        // 95% on the dominant axis is still short of the edge at max radius.
        evaluate(0.95f, 0f, maxRadius)
        assertTrue("max radius should not fire before the axis edge", edges.isEmpty())
        // Full deflection fires.
        edges.clear()
        evaluate(1.0f, 0f, maxRadius)
        assertEquals(listOf("outer_ring" to true), edges)
    }

    @Test
    fun outerRing_releaseEdge_firesUpWhenPullingBackIn() {
        // Was latched; now back inside the radius → release edge.
        evaluate(0.2f, 0.0f, "{}", priorOuterRing = true)
        assertEquals(listOf("outer_ring" to false), edges)
    }

    @Test
    fun haptic_buzzesEveryTravelStep_asStickMoves() {
        // First event only initializes the travel baseline — no buzz yet.
        val json = """{"haptic_intensity":"high"}"""
        JoystickMoveMode.resetState()
        evaluate(0f, 0f, json)
        assertTrue("first event just baselines, no buzz", buzzes.isEmpty())
        // Travel ~0.10 (< 0.12 step) → still no buzz.
        evaluate(0.10f, 0f, json)
        assertTrue(buzzes.isEmpty())
        // Cross a full step of travel → one buzz at the configured intensity.
        evaluate(0.30f, 0f, json)
        assertEquals(listOf(HapticIntensity.HIGH), buzzes)
    }

    @Test
    fun haptic_atRest_doesNotBuzz() {
        val json = """{"haptic_intensity":"medium"}"""
        JoystickMoveMode.resetState()
        evaluate(0.5f, 0.5f, json) // baseline
        evaluate(0.5f, 0.5f, json) // no movement
        evaluate(0.5f, 0.5f, json)
        assertTrue("holding still produces no travel and no buzz", buzzes.isEmpty())
    }

    @Test
    fun haptic_off_neverBuzzes() {
        val json = """{"haptic_intensity":"off"}"""
        JoystickMoveMode.resetState()
        evaluate(0f, 0f, json)
        evaluate(1.0f, 0f, json) // big travel
        assertTrue("no buzz when haptic intensity is off", buzzes.isEmpty())
    }
}
