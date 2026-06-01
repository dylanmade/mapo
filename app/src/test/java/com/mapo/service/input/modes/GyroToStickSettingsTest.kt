package com.mapo.service.input.modes

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Pure-math tests for the [GyroToStickSettings.toAxis] curve shared by
 * [GyroToJoystickCameraMode] and [GyroToJoystickDeflectionMode]. Mirrors
 * [GyroToMouseSettingsTest]'s shape; the key differences from gyro→mouse:
 *  - Output is normalized stick deflection [-1, +1], not pixels/sec.
 *  - Saturation clamp is exposed — gyro rates past the saturation point
 *    produce identical max-deflection output.
 *  - Caller-supplied defaults parameter lets the two consumer modes share
 *    one parser with different tuning presets.
 *
 * Robolectric required because `org.json.JSONObject` is an Android-platform
 * class that stubs to no-op in plain JVM unit tests (silently returning
 * defaults for any JSON, which masks real parsing bugs).
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class GyroToStickSettingsTest {

    private val cameraDefaults = GyroToStickSettings.CAMERA_DEFAULTS
    private val deflectionDefaults = GyroToStickSettings.DEFLECTION_DEFAULTS

    // ── Deadzone ─────────────────────────────────────────────────────────────

    @Test
    fun deadzone_atRest_returnsZero() {
        val (ax, ay) = cameraDefaults.toAxis(0f, 0f)
        assertEquals(0f, ax, EPSILON)
        assertEquals(0f, ay, EPSILON)
    }

    @Test
    fun deadzone_belowThreshold_returnsZero() {
        // Default deadzone = 0.05 rad/sec.
        val (ax, ay) = cameraDefaults.toAxis(0.03f, -0.04f)
        assertEquals(0f, ax, EPSILON)
        assertEquals(0f, ay, EPSILON)
    }

    @Test
    fun deadzone_perAxis_oneAxisAbove_otherZeroed() {
        val (ax, ay) = cameraDefaults.toAxis(1.0f, 0.02f)
        assertTrue("expected non-zero ax for roll=1.0, got $ax", ax != 0f)
        assertEquals(0f, ay, EPSILON)
    }

    // ── Sensitivity scaling ──────────────────────────────────────────────────

    @Test
    fun cameraSensitivity_atOneRadPerSec_givesEightTenthsDeflection() {
        // Camera default sensitivity 0.8. 1 rad/sec → 0.8 deflection.
        val (ax, ay) = cameraDefaults.toAxis(1.0f, 1.0f)
        assertEquals(0.8f, ax, EPSILON)
        assertEquals(0.8f, ay, EPSILON)
    }

    @Test
    fun deflectionSensitivity_atOneRadOfTilt_saturates() {
        // Deflection switched to tilt-based 2026-06-01 (user feedback: should
        // behave like a physical analog stick, not rate). Sensitivity units
        // shifted from "deflection per rad/sec" to "deflection per radian of
        // tilt-from-rest". 1 rad of tilt × 5.0 = 5.0 → clamped to 1.0.
        // The deflection mode integrates orientation outside of toAxis;
        // this is the math after the delta-from-reference is computed.
        val (ax, ay) = deflectionDefaults.toAxis(1.0f, 1.0f)
        assertEquals(1.0f, ax, EPSILON)
        assertEquals(1.0f, ay, EPSILON)
    }

    @Test
    fun deflectionSensitivity_atSmallTilt_givesProportionalDeflection() {
        // ~12° of tilt (0.2 rad) × 5.0 = 1.0 saturation. Smaller tilts give
        // proportional partial deflections — 0.05 rad (2.9°) × 5.0 = 0.25.
        val (ax, _) = deflectionDefaults.toAxis(0.05f, 0f)
        assertEquals(0.25f, ax, EPSILON)
    }

    @Test
    fun sensitivity_scalesLinearlyWithRate_belowSaturation() {
        // Below saturation, doubling rad/sec doubles deflection.
        val low = cameraDefaults.toAxis(0.5f, 0f).first
        val high = cameraDefaults.toAxis(1.0f, 0f).first
        assertEquals(2f * low, high, EPSILON)
    }

    // ── Saturation clamping ─────────────────────────────────────────────────

    @Test
    fun saturation_clampsAtPlusOne() {
        // 2 rad/sec × 0.8 sensitivity = 1.6 → clamped to 1.0.
        val (ax, _) = cameraDefaults.toAxis(2.0f, 0f)
        assertEquals(1.0f, ax, EPSILON)
    }

    @Test
    fun saturation_clampsAtMinusOne() {
        val (ax, _) = cameraDefaults.toAxis(-2.0f, 0f)
        assertEquals(-1.0f, ax, EPSILON)
    }

    // ── Sign / inversion ─────────────────────────────────────────────────────

    @Test
    fun negativeRollRate_givesNegativeAx() {
        val (ax, _) = cameraDefaults.toAxis(-0.5f, 0f)
        assertTrue("expected negative ax for negative roll, got $ax", ax < 0f)
    }

    @Test
    fun invertX_flipsRollSignOnly() {
        val plain = cameraDefaults.toAxis(0.5f, 0.5f)
        val flipped = cameraDefaults.copy(invertX = true).toAxis(0.5f, 0.5f)
        assertEquals(-plain.first, flipped.first, EPSILON)
        assertEquals(plain.second, flipped.second, EPSILON)
    }

    @Test
    fun invertY_flipsPitchSignOnly() {
        val plain = cameraDefaults.toAxis(0.5f, 0.5f)
        val flipped = cameraDefaults.copy(invertY = true).toAxis(0.5f, 0.5f)
        assertEquals(plain.first, flipped.first, EPSILON)
        assertEquals(-plain.second, flipped.second, EPSILON)
    }

    // ── Preset defaults differ ──────────────────────────────────────────────

    @Test
    fun cameraAndDeflectionDefaults_differOnSensitivity() {
        // Different primitives (rate vs. angle), different sensitivity
        // units. Comparing numeric magnitudes isn't apples-to-apples; we
        // just pin that the two are distinct.
        assertNotEquals(cameraDefaults.sensitivityX, deflectionDefaults.sensitivityX)
        assertNotEquals(cameraDefaults.sensitivityY, deflectionDefaults.sensitivityY)
    }

    @Test
    fun cameraAndDeflectionDefaults_shareDeadzone() {
        // Both modes consume the same gyro stream — the noise floor is the
        // same regardless of which output the mode drives.
        assertEquals(cameraDefaults.deadzone, deflectionDefaults.deadzone, EPSILON)
    }

    // ── JSON tolerance ───────────────────────────────────────────────────────

    @Test
    fun parse_blankJson_returnsCallerDefaults() {
        assertEquals(cameraDefaults, GyroToStickSettings.parse("", cameraDefaults))
        assertEquals(deflectionDefaults, GyroToStickSettings.parse("", deflectionDefaults))
    }

    @Test
    fun parse_malformedJson_returnsCallerDefaults() {
        assertEquals(cameraDefaults, GyroToStickSettings.parse("{not valid", cameraDefaults))
    }

    @Test
    fun parse_missingKeys_fillsFromDefaults() {
        val parsed = GyroToStickSettings.parse("""{"sensitivity_x":1.5}""", cameraDefaults)
        assertEquals(1.5f, parsed.sensitivityX, EPSILON)
        assertEquals(cameraDefaults.sensitivityY, parsed.sensitivityY, EPSILON)
        assertEquals(cameraDefaults.deadzone, parsed.deadzone, EPSILON)
    }

    @Test
    fun parse_clampsOutOfRangeValues() {
        val parsed = GyroToStickSettings.parse(
            """{"sensitivity_x":-100,"sensitivity_y":-50,"deadzone":99}""",
            cameraDefaults,
        )
        assertTrue("sensitivity_x=${parsed.sensitivityX}", parsed.sensitivityX >= 0f)
        assertTrue("sensitivity_y=${parsed.sensitivityY}", parsed.sensitivityY >= 0f)
        assertTrue("deadzone=${parsed.deadzone}", parsed.deadzone <= 5f)
    }

    @Test
    fun parse_fullJson_roundTripsAllFields() {
        val parsed = GyroToStickSettings.parse(
            """{"sensitivity_x":1.2,"sensitivity_y":0.6,"deadzone":0.1,"invert_x":true,"invert_y":false}""",
            cameraDefaults,
        )
        assertEquals(1.2f, parsed.sensitivityX, EPSILON)
        assertEquals(0.6f, parsed.sensitivityY, EPSILON)
        assertEquals(0.1f, parsed.deadzone, EPSILON)
        assertEquals(true, parsed.invertX)
        assertEquals(false, parsed.invertY)
    }

    companion object {
        private const val EPSILON = 0.0001f
    }
}
