package com.mapo.service.input.modes

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Tests for the mouse-output stage of Joystick Mouse ([MouseOutputSettings]) —
 * sensitivity %, per-axis scale, output-axis limit, and rotation. The input here
 * is the already-shaped (deadzone + curve) stick vector from
 * [StickToAxisSettings.toShaped]; that shaping is covered by StickToAxisSettingsTest.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class MouseOutputSettingsTest {

    private fun assertClose(expected: Float, actual: Float, eps: Float = 0.5f) {
        assertTrue("expected $expected but was $actual", kotlin.math.abs(expected - actual) <= eps)
    }

    @Test
    fun parse_blankAndEmpty_returnDefaults() {
        assertEquals(MouseOutputSettings.DEFAULTS, MouseOutputSettings.parse(""))
        assertEquals(MouseOutputSettings.DEFAULTS, MouseOutputSettings.parse("{}"))
    }

    @Test
    fun defaultSensitivity_275pct_isAbout800PxPerSec() {
        // Full-deflection shaped input (1, 0) → vx ≈ sensitivity px/sec.
        val (vx, vy) = MouseOutputSettings.DEFAULTS.toVelocity(1f, 0f)
        assertClose(800f, vx, 2f)
        assertEquals(0f, vy, 1e-3f)
    }

    @Test
    fun mouseSensitivity_scalesLinearly() {
        val at100 = MouseOutputSettings.parse("""{"mouse_sensitivity":100}""").toVelocity(1f, 0f).first
        val at200 = MouseOutputSettings.parse("""{"mouse_sensitivity":200}""").toVelocity(1f, 0f).first
        assertClose(2f * at100, at200, 2f)
    }

    @Test
    fun horizontalScale_zeroes_xButNotY() {
        val s = MouseOutputSettings.parse("""{"mouse_sensitivity":100,"horizontal_scale":0}""")
        val (vx, vy) = s.toVelocity(1f, 1f)
        assertEquals(0f, vx, 1e-3f)
        assertTrue("vertical should still move", vy > 0f)
    }

    @Test
    fun outputAxis_horizontal_dropsVertical() {
        val s = MouseOutputSettings.parse("""{"output_axis":"horizontal"}""")
        val (_, vy) = s.toVelocity(1f, 1f)
        assertEquals(0f, vy, 1e-3f)
    }

    @Test
    fun rotate90_forwardBecomesRight() {
        // Forward = up = (0, -1) in +Y-down → rotated 90° → right = (+x, 0).
        val s = MouseOutputSettings.parse("""{"mouse_sensitivity":100,"rotate_output":90}""")
        val (vx, vy) = s.toVelocity(0f, -1f)
        assertTrue("forward should become rightward velocity", vx > 0f)
        assertClose(0f, vy, 1f)
    }
}
