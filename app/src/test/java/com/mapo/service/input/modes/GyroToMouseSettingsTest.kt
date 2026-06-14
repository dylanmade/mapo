package com.mapo.service.input.modes

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Pure-math tests for [GyroToMouseSettings.toVelocity] (Brick D.3). Mirrors
 * [MouseOutputSettingsTest]'s shape; key differences from the stick-to-mouse
 * settings:
 *  - Inputs are rad/sec, not normalized -1..+1.
 *  - Deadzone is per-axis (not radial) — drift on one axis must not let the
 *    other axis through.
 *  - No response curve — gyro mapping is linear by Steam convention.
 *  - Two inversion flags (one per axis), not just invert_y.
 *
 * Robolectric is required because `org.json.JSONObject` is an Android-platform
 * class that stubs to no-op in plain JVM unit tests — silently returning
 * defaults for any JSON, which would mask real parsing bugs.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class GyroToMouseSettingsTest {

    private val defaults = GyroToMouseSettings.DEFAULTS

    // ── Deadzone ─────────────────────────────────────────────────────────────

    @Test
    fun deadzone_atRest_returnsZeroVelocity() {
        val (vx, vy) = defaults.toVelocity(0f, 0f)
        assertEquals(0f, vx, EPSILON)
        assertEquals(0f, vy, EPSILON)
    }

    @Test
    fun deadzone_belowThresholdOnBothAxes_returnsZero() {
        // Default deadzone is 0.05 rad/sec. (0.03, -0.04) is below on both.
        val (vx, vy) = defaults.toVelocity(0.03f, -0.04f)
        assertEquals(0f, vx, EPSILON)
        assertEquals(0f, vy, EPSILON)
    }

    @Test
    fun deadzone_perAxis_notRadial() {
        // (0.04, 0.04) has magnitude ~0.057 — would PASS a radial 0.05 threshold.
        // Per-axis check rejects each independently — both should zero out.
        // This is the "drift on Y throws away large motion on X" pitfall the
        // per-axis design specifically prevents.
        val (vx, vy) = defaults.toVelocity(0.04f, 0.04f)
        assertEquals(0f, vx, EPSILON)
        assertEquals(0f, vy, EPSILON)
    }

    @Test
    fun deadzone_oneAxisAbove_otherZeroed() {
        // Big yaw, drift-level pitch — yaw should produce velocity; pitch zero.
        val (vx, vy) = defaults.toVelocity(1.0f, 0.02f)
        assertTrue("expected non-zero vx for yaw=1.0, got $vx", vx != 0f)
        assertEquals(0f, vy, EPSILON)
    }

    @Test
    fun deadzone_atExactThreshold_passesThrough() {
        // Strict `<` in the gate means rate equal to the threshold is NOT
        // zeroed. Documents the boundary so a future "<=" change is a
        // deliberate choice. Practically, the gyro never lands exactly on
        // the threshold value at runtime — this just pins the contract.
        // Note: built-in axis negation (Mapo sign correction on AYN Thor —
        // see [GyroToMouseSettings.toVelocity] KDoc) means the resulting
        // velocity is `-deadzone * sensitivity`, not `+`.
        val (vx, _) = defaults.toVelocity(GyroToMouseSettings.DEFAULT_DEADZONE, 0f)
        assertEquals(
            -GyroToMouseSettings.DEFAULT_DEADZONE * GyroToMouseSettings.DEFAULT_SENSITIVITY_X,
            vx,
            EPSILON,
        )
    }

    // ── Sensitivity scaling ──────────────────────────────────────────────────

    @Test
    fun sensitivity_linearOnYaw() {
        // 1 rad/sec yaw × 400 px/rad × built-in -1 sign correction → -400 px/sec.
        val (vx, vy) = defaults.toVelocity(1.0f, 0f)
        assertEquals(-GyroToMouseSettings.DEFAULT_SENSITIVITY_X, vx, EPSILON)
        assertEquals(0f, vy, EPSILON)
    }

    @Test
    fun sensitivity_linearOnPitch() {
        val (vx, vy) = defaults.toVelocity(0f, 1.0f)
        assertEquals(0f, vx, EPSILON)
        assertEquals(-GyroToMouseSettings.DEFAULT_SENSITIVITY_Y, vy, EPSILON)
    }

    @Test
    fun sensitivity_scalesLinearlyWithRate() {
        // Doubling rad/sec doubles px/sec magnitude (linear, no curve). Sign
        // is preserved by the linearity check — both samples share the same
        // built-in -1 axis correction.
        val low = defaults.toVelocity(0.5f, 0f).first
        val high = defaults.toVelocity(1.0f, 0f).first
        assertEquals(2f * low, high, EPSILON)
    }

    @Test
    fun sensitivity_perAxis_canDiffer() {
        val asymmetric = GyroToMouseSettings(
            sensitivityX = 800f,
            sensitivityY = 200f,
            deadzone = 0.05f,
            invertX = false,
            invertY = false,
        )
        // Built-in -1 sign correction on both axes — see toVelocity KDoc.
        val (vx, vy) = asymmetric.toVelocity(1.0f, 1.0f)
        assertEquals(-800f, vx, EPSILON)
        assertEquals(-200f, vy, EPSILON)
    }

    // ── Sign / inversion ─────────────────────────────────────────────────────

    @Test
    fun negativeYaw_producesPositiveVx() {
        // Built-in -1 axis correction inverts the natural rate sign. A
        // negative gyro yaw rate (rolling left in the Thor's convention)
        // produces a positive cursor delta → moves cursor right, matching
        // player intent. Was `_producesNegativeVx` before the sign fix.
        val (vx, _) = defaults.toVelocity(-1.0f, 0f)
        assertTrue("expected positive vx for negative yaw, got $vx", vx > 0f)
    }

    @Test
    fun invertX_flipsYawSignOnly() {
        val plain = defaults.toVelocity(0.5f, 0.5f)
        val flipped = defaults.copy(invertX = true).toVelocity(0.5f, 0.5f)
        assertEquals(-plain.first, flipped.first, EPSILON)
        assertEquals(plain.second, flipped.second, EPSILON)
    }

    @Test
    fun invertY_flipsPitchSignOnly() {
        val plain = defaults.toVelocity(0.5f, 0.5f)
        val flipped = defaults.copy(invertY = true).toVelocity(0.5f, 0.5f)
        assertEquals(plain.first, flipped.first, EPSILON)
        assertEquals(-plain.second, flipped.second, EPSILON)
    }

    @Test
    fun invertBoth_flipsBothAxes() {
        val plain = defaults.toVelocity(0.5f, 0.5f)
        val flipped = defaults.copy(invertX = true, invertY = true).toVelocity(0.5f, 0.5f)
        assertEquals(-plain.first, flipped.first, EPSILON)
        assertEquals(-plain.second, flipped.second, EPSILON)
    }

    // ── JSON tolerance ───────────────────────────────────────────────────────

    @Test
    fun parse_blankJson_returnsDefaults() {
        assertEquals(defaults, GyroToMouseSettings.parse(""))
    }

    @Test
    fun parse_malformedJson_returnsDefaults() {
        assertEquals(defaults, GyroToMouseSettings.parse("{not valid"))
    }

    @Test
    fun parse_missingKeys_fillsFromDefaults() {
        val parsed = GyroToMouseSettings.parse("""{"sensitivity_x":1000}""")
        assertEquals(1000f, parsed.sensitivityX, EPSILON)
        assertEquals(defaults.sensitivityY, parsed.sensitivityY, EPSILON)
        assertEquals(defaults.deadzone, parsed.deadzone, EPSILON)
        assertEquals(defaults.invertX, parsed.invertX)
        assertEquals(defaults.invertY, parsed.invertY)
    }

    @Test
    fun parse_clampsOutOfRangeValues() {
        val parsed = GyroToMouseSettings.parse(
            """{"sensitivity_x":-100,"sensitivity_y":-50,"deadzone":99}"""
        )
        assertTrue("sensitivity_x=${parsed.sensitivityX}", parsed.sensitivityX >= 0f)
        assertTrue("sensitivity_y=${parsed.sensitivityY}", parsed.sensitivityY >= 0f)
        assertTrue("deadzone=${parsed.deadzone}", parsed.deadzone <= 5f)
    }

    @Test
    fun parse_respectsInvertFlags() {
        val parsed = GyroToMouseSettings.parse(
            """{"invert_x":true,"invert_y":true}"""
        )
        assertEquals(true, parsed.invertX)
        assertEquals(true, parsed.invertY)
    }

    @Test
    fun parse_fullJson_roundTripsAllFields() {
        val parsed = GyroToMouseSettings.parse(
            """{"sensitivity_x":600,"sensitivity_y":350,"deadzone":0.1,"invert_x":true,"invert_y":false}"""
        )
        assertEquals(600f, parsed.sensitivityX, EPSILON)
        assertEquals(350f, parsed.sensitivityY, EPSILON)
        assertEquals(0.1f, parsed.deadzone, EPSILON)
        assertEquals(true, parsed.invertX)
        assertEquals(false, parsed.invertY)
    }

    companion object {
        private const val EPSILON = 0.0001f
    }
}
