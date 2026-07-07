package com.mappo.service.input.modes

import com.mappo.service.input.HapticIntensity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Pure-math tests for [GyroToMouseSettings.toVelocity]. The mode follows the
 * spec's "Gyro to Mouse" menu: conversion style folds device roll/pitch/yaw
 * rates into screen 2D, then speed deadzone / precision / acceleration /
 * Dots-Per-360 × sensitivity / mixer / rotate shape the px/sec output.
 * Inputs are rad/sec (device frame: x=roll, y=pitch, z=yaw) + tilt angles.
 *
 * Robolectric is required because `org.json.JSONObject` stubs to no-op in plain
 * JVM unit tests — silently returning defaults for any JSON.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class GyroToMouseSettingsTest {

    private val defaults = GyroToMouseSettings.DEFAULTS

    // No deadzone/precision so the magnitude maps cleanly for scaling assertions.
    private val clean = defaults.copy(speedDeadzoneDegPerSec = 0f, precisionSpeedDegPerSec = 0f)

    // px per rad at the default Dots-Per-360 × sensitivity, accel OFF (×1).
    private val scale =
        GyroToMouseSettings.DEFAULT_DOTS_PER_360 / (2f * Math.PI.toFloat()) *
            GyroToMouseSettings.DEFAULT_GYRO_SENSITIVITY

    // ── Conversion styles ────────────────────────────────────────────────────

    @Test
    fun defaultConversionStyle_isYawRoll() {
        assertEquals(GyroConversionStyle.YAW_ROLL, defaults.conversionStyle)
    }

    @Test
    fun rollStyle_horizontalFromRoll() {
        val s = clean.copy(conversionStyle = GyroConversionStyle.ROLL)
        val (vx, vy) = s.toVelocity(rollRate = 1f, pitchRate = 0f, yawRate = 0f, tiltRoll = 0f, tiltPitch = 0f)
        assertEquals(-scale, vx, EPSILON)
        assertEquals(0f, vy, EPSILON)
    }

    @Test
    fun yawStyle_horizontalFromYaw() {
        val s = clean.copy(conversionStyle = GyroConversionStyle.YAW)
        val (vx, vy) = s.toVelocity(rollRate = 0f, pitchRate = 0f, yawRate = 1f, tiltRoll = 0f, tiltPitch = 0f)
        assertEquals(-scale, vx, EPSILON)
        assertEquals(0f, vy, EPSILON)
    }

    @Test
    fun yawRollStyle_combinesYawAndRoll() {
        // h = yaw + roll × rollContribution(1) = 2 → vx = -2×scale.
        val (vx, _) = clean.toVelocity(rollRate = 1f, pitchRate = 0f, yawRate = 1f, tiltRoll = 0f, tiltPitch = 0f)
        assertEquals(-2f * scale, vx, EPSILON)
    }

    @Test
    fun rollContribution_scalesRollIntoHorizontal() {
        val s = clean.copy(rollContribution = 0.5f)
        // h = yaw(0) + roll(1) × 0.5 = 0.5.
        val (vx, _) = s.toVelocity(rollRate = 1f, pitchRate = 0f, yawRate = 0f, tiltRoll = 0f, tiltPitch = 0f)
        assertEquals(-0.5f * scale, vx, EPSILON)
    }

    @Test
    fun pitch_drivesVertical() {
        val s = clean.copy(conversionStyle = GyroConversionStyle.ROLL)
        val (vx, vy) = s.toVelocity(rollRate = 0f, pitchRate = 1f, yawRate = 0f, tiltRoll = 0f, tiltPitch = 0f)
        assertEquals(0f, vx, EPSILON)
        assertEquals(-scale, vy, EPSILON)
    }

    // ── Sensitivity scaling ──────────────────────────────────────────────────

    @Test
    fun sensitivityMultiplier_scalesOutput() {
        val s = clean.copy(conversionStyle = GyroConversionStyle.ROLL)
        val plain = s.toVelocity(1f, 0f, 0f, 0f, 0f).first
        val doubled = s.copy(gyroSensitivity = s.gyroSensitivity * 2f).toVelocity(1f, 0f, 0f, 0f, 0f).first
        assertEquals(2f * plain, doubled, EPSILON)
    }

    @Test
    fun dotsPer360_scalesOutput() {
        val s = clean.copy(conversionStyle = GyroConversionStyle.ROLL)
        val plain = s.toVelocity(1f, 0f, 0f, 0f, 0f).first
        val halved = s.copy(dotsPer360 = s.dotsPer360 / 2f).toVelocity(1f, 0f, 0f, 0f, 0f).first
        assertEquals(plain / 2f, halved, EPSILON)
    }

    // ── Speed deadzone / precision ───────────────────────────────────────────

    @Test
    fun belowSpeedDeadzone_returnsZero() {
        // Default deadzone 0.36 deg/s ≈ 0.0063 rad/s; stay below it (radial).
        val below = GyroToMouseSettings.DEFAULT_SPEED_DEADZONE_DEG * (Math.PI.toFloat() / 180f) * 0.5f
        val (vx, vy) = defaults.copy(conversionStyle = GyroConversionStyle.ROLL)
            .toVelocity(rollRate = below, pitchRate = 0f, yawRate = 0f, tiltRoll = 0f, tiltPitch = 0f)
        assertEquals(0f, vx, EPSILON)
        assertEquals(0f, vy, EPSILON)
    }

    @Test
    fun speedDeadzone_subtractsFromMagnitude() {
        // Subtract model: magnitude = speed - dz (recovered at high speed).
        val dz = 0.5f * (Math.PI.toFloat() / 180f) // 0.5 deg/s in rad/s
        val s = defaults.copy(
            conversionStyle = GyroConversionStyle.ROLL,
            speedDeadzoneDegPerSec = 0.5f,
            precisionSpeedDegPerSec = 0f,
        )
        val speed = 1.0f
        val (vx, _) = s.toVelocity(rollRate = speed, pitchRate = 0f, yawRate = 0f, tiltRoll = 0f, tiltPitch = 0f)
        assertEquals(-(speed - dz) * scale, vx, 0.5f)
    }

    // ── Invert / mixer / rotate ──────────────────────────────────────────────

    @Test
    fun invertX_flipsHorizontalOnly() {
        val s = clean.copy(conversionStyle = GyroConversionStyle.ROLL)
        val plain = s.toVelocity(1f, 1f, 0f, 0f, 0f)
        val flipped = s.copy(invertX = true).toVelocity(1f, 1f, 0f, 0f, 0f)
        assertEquals(-plain.first, flipped.first, EPSILON)
        assertEquals(plain.second, flipped.second, EPSILON)
    }

    @Test
    fun invertY_flipsVerticalOnly() {
        val s = clean.copy(conversionStyle = GyroConversionStyle.ROLL)
        val plain = s.toVelocity(1f, 1f, 0f, 0f, 0f)
        val flipped = s.copy(invertY = true).toVelocity(1f, 1f, 0f, 0f, 0f)
        assertEquals(plain.first, flipped.first, EPSILON)
        assertEquals(-plain.second, flipped.second, EPSILON)
    }

    @Test
    fun outputMixer_positiveReducesHorizontal() {
        val s = clean.copy(conversionStyle = GyroConversionStyle.ROLL, outputMixer = 1f)
        val (vx, vy) = s.toVelocity(1f, 1f, 0f, 0f, 0f)
        assertEquals(0f, vx, EPSILON)
        assertTrue("vertical preserved", vy != 0f)
    }

    @Test
    fun outputMixer_negativeReducesVertical() {
        val s = clean.copy(conversionStyle = GyroConversionStyle.ROLL, outputMixer = -1f)
        val (vx, vy) = s.toVelocity(1f, 1f, 0f, 0f, 0f)
        assertTrue("horizontal preserved", vx != 0f)
        assertEquals(0f, vy, EPSILON)
    }

    @Test
    fun rotateOutput_90deg_swapsAxes() {
        val s = clean.copy(conversionStyle = GyroConversionStyle.ROLL)
        val plain = s.toVelocity(1f, 0f, 0f, 0f, 0f)
        val rotated = s.copy(rotateOutputDeg = 90f).toVelocity(1f, 0f, 0f, 0f, 0f)
        assertEquals(0f, rotated.first, 0.01f)
        assertEquals(plain.first, rotated.second, 0.01f)
    }

    // ── Acceleration ─────────────────────────────────────────────────────────

    @Test
    fun acceleration_boostsOutputAtSpeed() {
        val off = clean.copy(conversionStyle = GyroConversionStyle.ROLL, accel = GyroAccel.OFF)
        val on = off.copy(accel = GyroAccel.LINEAR)
        // At a high rotation speed the accel multiplier > 1, so |vx| is larger.
        val offVx = off.toVelocity(5f, 0f, 0f, 0f, 0f).first
        val onVx = on.toVelocity(5f, 0f, 0f, 0f, 0f).first
        assertTrue("accel should boost output: off=$offVx on=$onVx", kotlin.math.abs(onVx) > kotlin.math.abs(offVx))
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
        val parsed = GyroToMouseSettings.parse("""{"dots_per_360":10000}""")
        assertEquals(10000f, parsed.dotsPer360, EPSILON)
        assertEquals(defaults.gyroSensitivity, parsed.gyroSensitivity, EPSILON)
        assertEquals(defaults.conversionStyle, parsed.conversionStyle)
    }

    @Test
    fun parse_clampsOutOfRangeValues() {
        val parsed = GyroToMouseSettings.parse(
            """{"dots_per_360":99999,"gyro_sensitivity":-5,"gyro_speed_deadzone":999}"""
        )
        assertTrue(parsed.dotsPer360 <= 32000f)
        assertTrue(parsed.gyroSensitivity >= 0f)
        assertTrue(parsed.speedDeadzoneDegPerSec <= 90f)
    }

    @Test
    fun parse_roundTripsEnumsAndButtons() {
        val parsed = GyroToMouseSettings.parse(
            """{"conversion_style":"world_space","acceleration":"aggressive","trigger_dampening":"both_soft",""" +
                """"rotational_haptics":"high","gyro_enable_mode":"toggle","gyro_buttons":["button_a","button_b"],""" +
                """"output_mixer":-50,"enable_momentum":true,"movement_threshold":12}"""
        )
        assertEquals(GyroConversionStyle.WORLD_SPACE, parsed.conversionStyle)
        assertEquals(GyroAccel.AGGRESSIVE, parsed.accel)
        assertEquals(GyroTriggerDampen.BOTH_SOFT, parsed.triggerDampen)
        assertEquals(HapticIntensity.HIGH, parsed.rotationalHaptics)
        assertEquals(GyroEnableMode.TOGGLE, parsed.enableMode)
        assertEquals(listOf("button_a", "button_b"), parsed.gyroButtons)
        assertEquals(-0.5f, parsed.outputMixer, EPSILON)
        assertTrue(parsed.enableMomentum)
        assertEquals(12f, parsed.movementThresholdPx, EPSILON)
    }

    companion object {
        private const val EPSILON = 0.0001f
    }
}
