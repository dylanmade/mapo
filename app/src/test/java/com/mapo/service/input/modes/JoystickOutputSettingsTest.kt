package com.mapo.service.input.modes

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class JoystickOutputSettingsTest {

    private fun assertClose(expected: Float, actual: Float, eps: Float = 1e-3f) {
        assertTrue("expected $expected but was $actual", kotlin.math.abs(expected - actual) <= eps)
    }

    @Test
    fun parse_blank_returnsDefaults() {
        assertEquals(JoystickOutputSettings.DEFAULTS, JoystickOutputSettings.parse(""))
        assertEquals(JoystickOutputSettings.DEFAULTS, JoystickOutputSettings.parse("{}"))
    }

    @Test
    fun parse_readsKeysAndScalesPercent() {
        val s = JoystickOutputSettings.parse(
            """{"horizontal_scale":50,"vertical_scale":0,"invert_horizontal":true,
               "output_axis":"horizontal","rotate_output":90,"output_joystick":"left"}""",
        )
        assertClose(0.5f, s.horizontalScale)
        assertClose(0f, s.verticalScale)
        assertEquals(true, s.invertHorizontal)
        assertEquals(JoystickOutputSettings.OutputAxis.HORIZONTAL, s.outputAxis)
        assertClose(90f, s.rotateDegrees)
        assertEquals(JoystickOutputSettings.OutputStick.LEFT, s.outputStick)
    }

    @Test
    fun faceButtonVector_cardinalAndDiagonal() {
        // North only.
        assertEquals(0f to 1f, JoystickOutputSettings.faceButtonVector(setOf("button_y")))
        // East only.
        assertEquals(1f to 0f, JoystickOutputSettings.faceButtonVector(setOf("button_b")))
        // NE diagonal normalized to magnitude 1.
        val (x, y) = JoystickOutputSettings.faceButtonVector(setOf("button_y", "button_b"))
        assertClose(0.7071f, x)
        assertClose(0.7071f, y)
        // Opposing directions cancel.
        assertEquals(0f to 0f, JoystickOutputSettings.faceButtonVector(setOf("button_x", "button_b")))
    }

    @Test
    fun apply_defaultsPassThrough() {
        val (x, y) = JoystickOutputSettings.DEFAULTS.apply(1f, 0f)
        assertClose(1f, x); assertClose(0f, y)
    }

    @Test
    fun apply_scaleAndInvert() {
        // Scale is applied through the Steam-parity concave curve (deflection =
        // scale^0.6), so 50% emits ~0.66 deflection, not a linear 0.5. Vertical
        // scale stays at the default 1.0 here, so the inverted Y is full -1.
        val s = JoystickOutputSettings.DEFAULTS.copy(horizontalScale = 0.5f, invertVertical = true)
        val (x, y) = s.apply(1f, 1f)
        assertClose(0.6598f, x)
        assertClose(-1f, y)
    }

    @Test
    fun apply_scaleCurve_endpointsAreLinear() {
        // The curve must still pass through 0→0 and 1→1 so full and zero scale
        // behave exactly; only the interior is lifted.
        val full = JoystickOutputSettings.DEFAULTS.copy(horizontalScale = 1f).apply(1f, 0f)
        assertClose(1f, full.first)
        val zero = JoystickOutputSettings.DEFAULTS.copy(horizontalScale = 0f).apply(1f, 0f)
        assertClose(0f, zero.first)
    }

    @Test
    fun apply_axisLimitZeroesOtherAxis() {
        val s = JoystickOutputSettings.DEFAULTS.copy(outputAxis = JoystickOutputSettings.OutputAxis.HORIZONTAL)
        val (_, y) = s.apply(1f, 1f)
        assertClose(0f, y)
    }

    @Test
    fun apply_rotate90_forwardBecomesRight() {
        // +Y (forward) rotated 90° → +X (right).
        val s = JoystickOutputSettings.DEFAULTS.copy(rotateDegrees = 90f)
        val (x, y) = s.apply(0f, 1f)
        assertClose(1f, x)
        assertClose(0f, y)
    }
}
