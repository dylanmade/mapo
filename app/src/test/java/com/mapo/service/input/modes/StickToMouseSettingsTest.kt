package com.mapo.service.input.modes

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.math.abs

/**
 * Pure-math tests for the [StickToMouseSettings.toVelocity] curve shared by
 * [MouseJoystickMode] and [JoystickCameraMode]. Verifies the contract pieces
 * tuners are most likely to break: deadzone gating, sensitivity scaling, curve
 * exponent direction, axis sign, and JSON tolerance.
 *
 * Robolectric is needed for the JSON-parsing tests because
 * `org.json.JSONObject` is part of the Android platform and stubs to no-op in
 * a plain JVM unit test (silently returning defaults regardless of input,
 * which masks real malformed-JSON bugs).
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class StickToMouseSettingsTest {

    private val mouseDefaults = StickToMouseSettings.MOUSE_JOYSTICK_DEFAULTS
    private val cameraDefaults = StickToMouseSettings.JOYSTICK_CAMERA_DEFAULTS

    // ── Deadzone ─────────────────────────────────────────────────────────────

    @Test
    fun deadzone_atRest_returnsZeroVelocity() {
        val (vx, vy) = mouseDefaults.toVelocity(0f, 0f)
        assertEquals(0f, vx, EPSILON)
        assertEquals(0f, vy, EPSILON)
    }

    @Test
    fun deadzone_insideRadius_returnsZeroVelocity() {
        // Mouse Joystick default deadzone is 0.10.
        val (vx, vy) = mouseDefaults.toVelocity(0.05f, 0.05f)
        assertEquals(0f, vx, EPSILON)
        assertEquals(0f, vy, EPSILON)
    }

    @Test
    fun deadzone_radial_notAxial() {
        // (0.09, 0.09) has magnitude ~0.127 — outside the radial 0.10 deadzone.
        // Axial check (x > 0.10 AND y > 0.10) would have rejected this; radial
        // shouldn't.
        val (vx, vy) = mouseDefaults.toVelocity(0.09f, 0.09f)
        assertTrue("expected non-zero velocity past radial deadzone but got ($vx, $vy)",
            vx > 0f && vy > 0f)
    }

    // ── Sensitivity scaling ──────────────────────────────────────────────────

    @Test
    fun sensitivity_fullDeflection_outputsCloseToSensitivity() {
        // At magnitude 1.0, rescaled = 1.0, curve(1.0) = 1.0 → outputMag =
        // sensitivity. Direction (1, 0) gives full sensitivity on x.
        val settings = StickToMouseSettings(
            deadzone = 0.1f, sensitivity = 1000f, exponent = 1.6f, invertY = false,
        )
        val (vx, vy) = settings.toVelocity(1f, 0f)
        assertEquals(1000f, vx, 0.5f)
        assertEquals(0f, vy, EPSILON)
    }

    @Test
    fun sensitivity_scaling_isLinearAtFullDeflection() {
        // Doubling sensitivity should double velocity at full deflection,
        // independent of curve.
        val low = StickToMouseSettings(0.1f, 500f, 1.6f, false).toVelocity(1f, 0f).first
        val high = StickToMouseSettings(0.1f, 1000f, 1.6f, false).toVelocity(1f, 0f).first
        assertEquals(2f * low, high, 1f)
    }

    // ── Curve exponent ───────────────────────────────────────────────────────

    @Test
    fun exponent_aboveOne_attenuatesNearCenter() {
        // Half-deflection on the same axis with exponent=2 should produce
        // strictly LESS than half the full-deflection velocity (the curve
        // compresses near center for precision).
        val settings = StickToMouseSettings(0.0f, 1000f, 2.0f, false)
        val halfVx = settings.toVelocity(0.5f, 0f).first
        val fullVx = settings.toVelocity(1.0f, 0f).first
        assertTrue("expected exponent>1 to attenuate near center: half=$halfVx full=$fullVx",
            halfVx < fullVx / 2f)
    }

    @Test
    fun exponent_belowOne_amplifiesNearCenter() {
        // exponent=0.5 (sqrt) makes near-center motion *more* responsive.
        val settings = StickToMouseSettings(0.0f, 1000f, 0.5f, false)
        val halfVx = settings.toVelocity(0.5f, 0f).first
        val fullVx = settings.toVelocity(1.0f, 0f).first
        assertTrue("expected exponent<1 to amplify near center: half=$halfVx full=$fullVx",
            halfVx > fullVx / 2f)
    }

    // ── Axis sign / invert_y ─────────────────────────────────────────────────

    @Test
    fun negativeDeflection_preservesSign() {
        val (vx, vy) = mouseDefaults.toVelocity(-1f, 0f)
        assertTrue("expected negative vx for negative x deflection, got $vx", vx < 0f)
        assertEquals(0f, vy, EPSILON)
    }

    @Test
    fun invertY_flipsYSignOnly() {
        val plain = StickToMouseSettings(0.1f, 1000f, 1.6f, invertY = false).toVelocity(0.5f, 0.5f)
        val flipped = StickToMouseSettings(0.1f, 1000f, 1.6f, invertY = true).toVelocity(0.5f, 0.5f)
        assertEquals(plain.first, flipped.first, EPSILON)
        assertEquals(-plain.second, flipped.second, EPSILON)
    }

    // ── Defaults differ between modes ────────────────────────────────────────

    @Test
    fun mouseAndCameraDefaults_differOnAllShapeAxes() {
        assertNotEquals(mouseDefaults.deadzone, cameraDefaults.deadzone)
        assertNotEquals(mouseDefaults.sensitivity, cameraDefaults.sensitivity)
        assertNotEquals(mouseDefaults.exponent, cameraDefaults.exponent)
    }

    // ── JSON tolerance ───────────────────────────────────────────────────────

    @Test
    fun parse_blankJson_returnsDefaults() {
        assertEquals(mouseDefaults, StickToMouseSettings.parse("", mouseDefaults))
    }

    @Test
    fun parse_malformedJson_returnsDefaults() {
        assertEquals(
            mouseDefaults,
            StickToMouseSettings.parse("{ this is not json", mouseDefaults),
        )
    }

    @Test
    fun parse_missingKeys_fillsFromDefaults() {
        val parsed = StickToMouseSettings.parse(
            """{"sensitivity":2000}""",
            mouseDefaults,
        )
        // Sensitivity overridden, everything else from defaults.
        assertEquals(2000f, parsed.sensitivity, EPSILON)
        assertEquals(mouseDefaults.deadzone, parsed.deadzone, EPSILON)
        assertEquals(mouseDefaults.exponent, parsed.exponent, EPSILON)
        assertEquals(mouseDefaults.invertY, parsed.invertY)
    }

    @Test
    fun parse_clampsOutOfRangeValues() {
        val parsed = StickToMouseSettings.parse(
            """{"deadzone":2.0,"exponent":-5,"sensitivity":-100}""",
            mouseDefaults,
        )
        // deadzone clamped to <=0.95, exponent clamped to >=0.1, sensitivity >=0.
        assertTrue("deadzone=${parsed.deadzone}", parsed.deadzone <= 0.95f)
        assertTrue("exponent=${parsed.exponent}", parsed.exponent >= 0.1f)
        assertTrue("sensitivity=${parsed.sensitivity}", parsed.sensitivity >= 0f)
    }

    companion object {
        private const val EPSILON = 0.0001f
    }
}
