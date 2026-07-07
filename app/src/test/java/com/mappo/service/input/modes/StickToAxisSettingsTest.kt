package com.mappo.service.input.modes

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Unit coverage for the analog-specific shaping in [StickToAxisSettings] —
 * deadzone (source / shape / threshold), response curve, and the source-aware
 * Output Joystick + outer-ring fields. The scale/invert/rotate/axis-limit knobs
 * live in [JoystickOutputSettings] (covered by JoystickOutputSettingsTest) and
 * are applied after this stage, so they're not re-tested here.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class StickToAxisSettingsTest {

    private fun assertClose(expected: Float, actual: Float, eps: Float = 1e-3f) {
        assertTrue("expected $expected but was $actual", kotlin.math.abs(expected - actual) <= eps)
    }

    @Test
    fun parse_blankAndEmpty_returnDefaults() {
        assertEquals(StickToAxisSettings.DEFAULTS, StickToAxisSettings.parse(""))
        assertEquals(StickToAxisSettings.DEFAULTS, StickToAxisSettings.parse("{}"))
    }

    @Test
    fun defaults_outputJoystickNull_forSourceAwareResolution() {
        assertEquals(null, StickToAxisSettings.DEFAULTS.outputJoystick)
        // Present key parses through; absent stays null (so the mode can default
        // to the matching stick).
        assertEquals("left", StickToAxisSettings.parse("""{"output_joystick":"left"}""").outputJoystick)
    }

    @Test
    fun deviceDefaultDeadzone_centerIsZero_fullIsFull() {
        val s = StickToAxisSettings.DEFAULTS // device_default → 0.10 inner
        // Inside the 10% inner deadzone → no output.
        val (cx, cy) = s.toShaped(0.05f, 0f)
        assertClose(0f, cx); assertClose(0f, cy)
        // Full right → full output (linear curve, outer = 1.0).
        val (fx, _) = s.toShaped(1f, 0f)
        assertClose(1f, fx)
    }

    @Test
    fun noDeadzone_passesSmallInputThrough() {
        val s = StickToAxisSettings.parse("""{"deadzone_source":"none"}""")
        // With no deadzone, a small deflection survives (linear curve).
        val (cx, _) = s.toShaped(0.05f, 0f)
        assertClose(0.05f, cx)
    }

    @Test
    fun customDeadzone_rampsBetweenInnerAndOuter() {
        // inner 20%, outer 80% → at 50% deflection, rescaled = (0.5-0.2)/(0.8-0.2) = 0.5.
        val s = StickToAxisSettings.parse(
            """{"deadzone_source":"custom","deadzone_inner":20,"deadzone_outer":80,"deadzone_shape":"circle"}""",
        )
        val (cx, _) = s.toShaped(0.5f, 0f)
        assertClose(0.5f, cx)
        // At/above the outer handle → full output.
        val (fx, _) = s.toShaped(0.8f, 0f)
        assertClose(1f, fx)
    }

    @Test
    fun aggressiveCurve_liftsMidRange_relaxedLowersIt() {
        val base = """"deadzone_source":"none","response_axis_style":"circular""""
        val aggressive = StickToAxisSettings.parse("""{$base,"stick_response_curve":"aggressive"}""")
        val relaxed = StickToAxisSettings.parse("""{$base,"stick_response_curve":"relaxed"}""")
        val mid = 0.5f
        val a = aggressive.toShaped(mid, 0f).first   // exp 0.6 → 0.5^0.6 ≈ 0.66
        val r = relaxed.toShaped(mid, 0f).first      // exp 1.5 → 0.5^1.5 ≈ 0.354
        assertTrue("aggressive should lift mid-range above linear", a > mid)
        assertTrue("relaxed should lower mid-range below linear", r < mid)
    }

    @Test
    fun customCurve_default200_isLinear() {
        val s = StickToAxisSettings.parse(
            """{"deadzone_source":"none","stick_response_curve":"custom","custom_response_curve":200}""",
        )
        assertClose(0.5f, s.toShaped(0.5f, 0f).first) // exponent 200/200 = 1.0
    }

    @Test
    fun outerRing_radiusAndCommandInvert_parse() {
        val s = StickToAxisSettings.parse("""{"command_radius":16384,"command_invert":true}""")
        assertClose(0.5f, s.commandRadius) // 16384/32767 ≈ 0.5
        assertEquals(true, s.commandInvert)
    }
}
