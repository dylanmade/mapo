package com.mapo.service.input.modes

import android.os.SystemClock
import com.mapo.data.model.steam.InputSource
import com.mapo.service.input.AnalogEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Brick C.4 — focused unit tests for [MouseRegionMode.evaluate]. Targets the
 * stick → region-fraction math and the deadzone → clear-target behavior in
 * isolation, with a recording [MouseEmitter] so we can assert the exact
 * (xFrac, yFrac) targets that flow to the dispatcher.
 *
 * **Robolectric is required** because [MouseRegionSettings.parse] uses
 * `org.json.JSONObject` — under the project's `isReturnDefaultValues=true`
 * unit-test config, the stub returns 0.0 for every key and parsed settings
 * silently collapse to zero.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class MouseRegionModeTest {

    private class RecordingEmitter : MouseEmitter {
        val setCalls = mutableListOf<Triple<InputSource, Float, Float>>()
        val clearCalls = mutableListOf<InputSource>()
        var velocityCalls = 0
        override fun setStickVelocity(source: InputSource, vxPxPerSec: Float, vyPxPerSec: Float) {
            velocityCalls++
        }
        override fun setStickAbsoluteTarget(source: InputSource, xFrac: Float, yFrac: Float) {
            setCalls += Triple(source, xFrac, yFrac)
        }
        override fun clearStickAbsoluteTarget(source: InputSource) {
            clearCalls += source
        }
        override fun clearAllVelocities() {}
        override fun addRelativeDelta(dx: Float, dy: Float) {}
    }

    private val mouse = RecordingEmitter()
    private val digitalEmit: (String, Boolean) -> Unit = { _, _ -> }

    private fun ctx(settingsJson: String = MouseRegionMode.defaultSettingsJson()) = ModeContext(
        source = InputSource.LEFT_JOYSTICK,
        settingsJson = settingsJson,
        priorLatched = emptyMap(),
        activeLayerIds = emptyList(),
    )

    private fun reading(x: Float, y: Float, source: InputSource = InputSource.LEFT_JOYSTICK) =
        AnalogEvent(
            source = source,
            x = x,
            y = y,
            timestampMs = SystemClock.uptimeMillis(),
        )

    // ── Joystick source path ─────────────────────────────────────────────────

    @Test
    fun centerStick_belowDeadzone_clearsTarget() {
        // Inside the 10% radial deadzone the cursor must stay put; the emitter
        // gets a clear so it can end the continuous-cursor session if this was
        // the last active source.
        MouseRegionMode.evaluate(reading(0f, 0f), ctx(), digitalEmit, mouse)
        assertEquals(0, mouse.setCalls.size)
        assertEquals(listOf(InputSource.LEFT_JOYSTICK), mouse.clearCalls)
    }

    @Test
    fun barelyDeflected_stillInsideDeadzone_clearsTarget() {
        // Default deadzone is 0.10 radial; (0.05, 0.05) has magnitude ~0.071.
        MouseRegionMode.evaluate(reading(0.05f, 0.05f), ctx(), digitalEmit, mouse)
        assertEquals(0, mouse.setCalls.size)
        assertEquals(1, mouse.clearCalls.size)
    }

    @Test
    fun fullRight_targetsRightEdgeOfRegion() {
        // Default region: center (0.5, 0.5), half_width 0.5 (full-screen).
        // Full right: x=1, y=0 → target frac = (0.5 + 1*0.5, 0.5 + 0*0.5) = (1.0, 0.5).
        MouseRegionMode.evaluate(reading(1f, 0f), ctx(), digitalEmit, mouse)
        assertEquals(1, mouse.setCalls.size)
        val (src, xFrac, yFrac) = mouse.setCalls.single()
        assertEquals(InputSource.LEFT_JOYSTICK, src)
        assertEquals(1.0f, xFrac, 1e-5f)
        assertEquals(0.5f, yFrac, 1e-5f)
    }

    @Test
    fun fullUp_targetsTopEdgeOfRegion() {
        // +y = down, so up is y = -1. target = (0.5, 0.5 + (-1)*0.5) = (0.5, 0.0).
        MouseRegionMode.evaluate(reading(0f, -1f), ctx(), digitalEmit, mouse)
        val (_, xFrac, yFrac) = mouse.setCalls.single()
        assertEquals(0.5f, xFrac, 1e-5f)
        assertEquals(0.0f, yFrac, 1e-5f)
    }

    @Test
    fun halfRight_targetsHalfwayBetweenCenterAndRightEdge() {
        // (0.5, 0): magnitude 0.5 (well past 0.1 deadzone).
        // target = (0.5 + 0.5*0.5, 0.5) = (0.75, 0.5).
        MouseRegionMode.evaluate(reading(0.5f, 0f), ctx(), digitalEmit, mouse)
        val (_, xFrac, yFrac) = mouse.setCalls.single()
        assertEquals(0.75f, xFrac, 1e-5f)
        assertEquals(0.5f, yFrac, 1e-5f)
    }

    @Test
    fun diagonal_pushUpRight_mapsToUpperRightOfRegion() {
        // (0.7, -0.7) → magnitude ~0.99, well past deadzone.
        // target = (0.5 + 0.7*0.5, 0.5 + (-0.7)*0.5) = (0.85, 0.15).
        MouseRegionMode.evaluate(reading(0.7f, -0.7f), ctx(), digitalEmit, mouse)
        val (_, xFrac, yFrac) = mouse.setCalls.single()
        assertEquals(0.85f, xFrac, 1e-5f)
        assertEquals(0.15f, yFrac, 1e-5f)
    }

    @Test
    fun deadzoneJustCleared_emitsAbsoluteTarget() {
        // (0.11, 0): magnitude 0.11, just past the 0.10 default deadzone.
        // target = (0.5 + 0.11*0.5, 0.5) = (0.555, 0.5).
        MouseRegionMode.evaluate(reading(0.11f, 0f), ctx(), digitalEmit, mouse)
        assertEquals(1, mouse.setCalls.size)
        val (_, xFrac, _) = mouse.setCalls.single()
        assertEquals(0.555f, xFrac, 1e-5f)
    }

    // ── Source filter (joystick vs. everything else) ─────────────────────────

    @Test
    fun rightJoystick_isAlsoMapped() {
        // Mouse Region should drive the cursor regardless of which joystick.
        // Last-source-wins is handled by the emitter; the mode treats LJ + RJ
        // identically.
        MouseRegionMode.evaluate(
            reading(1f, 0f, source = InputSource.RIGHT_JOYSTICK),
            ctx().copy(source = InputSource.RIGHT_JOYSTICK),
            digitalEmit, mouse,
        )
        val (src, xFrac, _) = mouse.setCalls.single()
        assertEquals(InputSource.RIGHT_JOYSTICK, src)
        assertEquals(1.0f, xFrac, 1e-5f)
    }

    @Test
    fun gyroSource_isANoOpForNow() {
        // Gyro pipeline ships in task #254 (post-Brick-C). Until then, gyro
        // events targeted at Mouse Region must not emit anything — neither a
        // target nor a clear, since the emitter's session lifecycle is keyed
        // off the joystick sources today.
        MouseRegionMode.evaluate(
            reading(0.7f, 0.7f, source = InputSource.GYRO),
            ctx().copy(source = InputSource.GYRO),
            digitalEmit, mouse,
        )
        assertTrue(mouse.setCalls.isEmpty())
        assertTrue(mouse.clearCalls.isEmpty())
    }

    @Test
    fun dpadSource_isANoOp() {
        // A DPAD source in MouseRegion mode is an unsupported config (the
        // catalog doesn't expose Mouse Region for the DPAD picker); guarded
        // out so a stale binding doesn't crash the evaluator.
        MouseRegionMode.evaluate(
            reading(1f, 0f, source = InputSource.DPAD),
            ctx().copy(source = InputSource.DPAD),
            digitalEmit, mouse,
        )
        assertTrue(mouse.setCalls.isEmpty())
        assertTrue(mouse.clearCalls.isEmpty())
    }

    // ── Settings parse + invert_y ────────────────────────────────────────────

    @Test
    fun customRegion_topLeftQuadrant_mapsCorrectly() {
        // Region center (0.25, 0.25), half_width/height 0.20 each → covers the
        // upper-left quadrant of screen. Full down-right stick: target =
        // (0.25 + 1*0.20, 0.25 + 1*0.20) = (0.45, 0.45). Still within the
        // upper-left quadrant — the region is "where the cursor can travel."
        val custom = """{"region_center_x":0.25,"region_center_y":0.25,"region_half_width":0.20,"region_half_height":0.20}"""
        MouseRegionMode.evaluate(reading(1f, 1f), ctx(custom), digitalEmit, mouse)
        val (_, xFrac, yFrac) = mouse.setCalls.single()
        assertEquals(0.45f, xFrac, 1e-5f)
        assertEquals(0.45f, yFrac, 1e-5f)
    }

    @Test
    fun invertY_flipsTargetY() {
        // (0, -1) (full up) normally → y target 0.0 (with full-screen default).
        // With invert_y, y becomes 1.0 (bottom of region) instead.
        val flipped = """{"invert_y":true}"""
        MouseRegionMode.evaluate(reading(0f, -1f), ctx(flipped), digitalEmit, mouse)
        val (_, _, yFrac) = mouse.setCalls.single()
        assertEquals(1.0f, yFrac, 1e-5f)
    }

    @Test
    fun outOfRangeRegionSettings_clampWithoutCrashing() {
        // half_width=2.0 would push targets to fractions > 1; we clamp inside
        // the math so the cursor never aims off-screen.
        val outOfRange = """{"region_half_width":2.0}"""
        MouseRegionMode.evaluate(reading(1f, 0f), ctx(outOfRange), digitalEmit, mouse)
        val (_, xFrac, _) = mouse.setCalls.single()
        assertEquals(1.0f, xFrac, 1e-5f)
    }

    @Test
    fun blankSettings_fallBackToDefaults() {
        // Defaults: deadzone 0.10, region centered, half 0.5 (full-screen).
        MouseRegionMode.evaluate(reading(1f, 0f), ctx(""), digitalEmit, mouse)
        val (_, xFrac, yFrac) = mouse.setCalls.single()
        assertEquals(1.0f, xFrac, 1e-5f)
        assertEquals(0.5f, yFrac, 1e-5f)
    }

    @Test
    fun malformedJson_fallsBackToDefaults() {
        MouseRegionMode.evaluate(reading(1f, 0f), ctx("not-json"), digitalEmit, mouse)
        val (_, xFrac, _) = mouse.setCalls.single()
        assertEquals(1.0f, xFrac, 1e-5f)
    }

    @Test
    fun missingKeys_fillFromDefaults() {
        // Only override deadzone; region settings should default.
        val partial = """{"deadzone":0.05}"""
        // Magnitude 0.06 — past the 0.05 deadzone, but not the default 0.10.
        MouseRegionMode.evaluate(reading(0.06f, 0f), ctx(partial), digitalEmit, mouse)
        assertEquals(1, mouse.setCalls.size)
    }

    // ── Validation surface ───────────────────────────────────────────────────

    @Test
    fun validInputs_includesClickAndOuterRing() {
        assertEquals(setOf("click", "outer_ring"), MouseRegionMode.validInputs())
    }

    @Test
    fun mode_isMouseRegion() {
        assertEquals(com.mapo.data.model.steam.BindingMode.MOUSE_REGION, MouseRegionMode.mode)
    }

    @Test
    fun defaultSettingsJson_includesAllKeys() {
        val json = MouseRegionMode.defaultSettingsJson()
        // Smoke-check that each tunable shows up so a future field-add
        // doesn't silently break VDF import / settings UI defaults.
        assertTrue(json.contains("deadzone"))
        assertTrue(json.contains("region_center_x"))
        assertTrue(json.contains("region_center_y"))
        assertTrue(json.contains("region_half_width"))
        assertTrue(json.contains("region_half_height"))
        assertTrue(json.contains("invert_y"))
    }
}
