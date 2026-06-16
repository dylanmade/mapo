package com.mapo.service.input.modes

import com.mapo.data.model.steam.BindingMode
import com.mapo.data.model.steam.InputSource
import com.mapo.service.input.AnalogEvent
import io.mockk.mockk
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.math.cos
import kotlin.math.sin

/**
 * Behavior tests for [ScrollWheelMode] — stick rotation → tick edges on
 * `scroll_clockwise` / `scroll_counter_clockwise`.
 *
 * Steam-parity: tick fires on threshold-crossing of angular delta from the
 * last sampled baseline; back-to-back DOWN→UP pulse per tick so the
 * activator engine sees one press cycle (and emits exactly one mouse
 * wheel notch if bound to MOUSE_WHEEL_UP/DOWN).
 *
 * Angle convention (matches [FlickStickMode]): `atan2(x, -y)` → 0 = up,
 * +π/2 = right, ±π = down, −π/2 = left. Visually clockwise = positive
 * angular delta → `scroll_clockwise` sub-input.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class ScrollWheelModeTest {

    private val mouse = mockk<MouseEmitter>(relaxed = true)
    private val gamepad = mockk<GamepadEmitter>(relaxed = true)
    private val emitted = mutableListOf<Pair<String, Boolean>>()
    private val digitalEmit: (String, Boolean) -> Unit = { sub, down -> emitted += sub to down }

    @After
    fun reset() {
        ScrollWheelMode.resetState()
        emitted.clear()
    }

    private fun ctx(
        settingsJson: String = "",
        source: InputSource = InputSource.RIGHT_JOYSTICK,
    ) = ModeContext(
        source = source,
        settingsJson = settingsJson,
        priorLatched = emptyMap(),
        activeLayerIds = emptyList(),
        gamepad = gamepad,
    )

    /**
     * Stick event from a polar angle. `angleRad` = 0 means stick UP, +π/2 =
     * RIGHT, π = DOWN, −π/2 = LEFT (matches the mode's atan2(x, −y) convention).
     * Mapo's screen convention has +y = down, so we invert sin's sign for the
     * y axis.
     */
    private fun stickAtAngle(
        angleRad: Float,
        magnitude: Float = 0.8f,
        source: InputSource = InputSource.RIGHT_JOYSTICK,
    ) = AnalogEvent(
        source = source,
        x = magnitude * sin(angleRad),
        y = -magnitude * cos(angleRad),
        timestampMs = 0L,
    )

    private fun deadzone(source: InputSource = InputSource.RIGHT_JOYSTICK) = AnalogEvent(
        source = source,
        x = 0f,
        y = 0f,
        timestampMs = 0L,
    )

    // sensitivity 16 = 360/16 = 22.5° per tick (the resolution these rotation
    // tests were originally written against).
    private val sens16 = """{"sensitivity":16}"""

    private fun evaluate(reading: AnalogEvent, settings: String = sens16) {
        ScrollWheelMode.evaluate(reading, ctx(settings, reading.source), digitalEmit, mouse)
    }

    // ── Metadata ─────────────────────────────────────────────────────────────

    @Test
    fun mode_isScrollWheel() {
        assertEquals(BindingMode.SCROLL_WHEEL, ScrollWheelMode.mode)
    }

    @Test
    fun validInputs_includeRotationSubInputsAndPassthroughs() {
        assertEquals(
            setOf("click", "outer_ring", "scroll_clockwise", "scroll_counter_clockwise"),
            ScrollWheelMode.validInputs(),
        )
    }

    @Test
    fun handlerRegistry_returnsScrollWheelMode() {
        assertSame(ScrollWheelMode, BindingMode.SCROLL_WHEEL.handler())
    }

    // ── Source filter ────────────────────────────────────────────────────────

    @Test
    fun nonJoystickSources_areNoOp() {
        // GYRO can't be a Scroll Wheel source (no Steam parity for it). DPAD,
        // BUTTON_DIAMOND, triggers — same.
        for (src in listOf(InputSource.GYRO, InputSource.DPAD, InputSource.BUTTON_DIAMOND, InputSource.LEFT_TRIGGER)) {
            evaluate(stickAtAngle(0f, source = src))
            evaluate(stickAtAngle(0.5f, source = src))
        }
        assertTrue("non-joystick sources emit nothing; got $emitted", emitted.isEmpty())
    }

    // ── Deadzone / baseline ──────────────────────────────────────────────────

    @Test
    fun belowDeadzone_emitsNothing_andClearsBaseline() {
        // Set baseline from a deflected sample, then drop below deadzone, then
        // jump to a far angle. If baseline was cleared, the post-deadzone
        // sample becomes the new baseline (no tick); if it wasn't, the angular
        // jump from old baseline to far angle would fire a spurious tick.
        evaluate(stickAtAngle(0f))                 // baseline at 0
        evaluate(deadzone())                       // below deadzone → clear
        evaluate(stickAtAngle((Math.PI / 2).toFloat()))  // 90° push — fresh baseline, no tick
        assertTrue("deadzone clears baseline; got $emitted", emitted.isEmpty())
    }

    @Test
    fun firstDeflection_setsBaselineButDoesNotEmit() {
        evaluate(stickAtAngle(0f))
        assertTrue("first sample after deadzone emits nothing; got $emitted", emitted.isEmpty())
    }

    // ── Threshold crossing — clockwise ───────────────────────────────────────

    @Test
    fun rotateClockwiseAcrossOneThreshold_emitsOneClockwiseTick() {
        // Default threshold = 22.5°. Rotate from 0° to 25° clockwise.
        evaluate(stickAtAngle(0f))                            // baseline
        evaluate(stickAtAngle((25 * Math.PI / 180).toFloat()))
        // One DOWN→UP pulse on scroll_clockwise.
        assertEquals(listOf("scroll_clockwise" to true, "scroll_clockwise" to false), emitted)
    }

    @Test
    fun rotateClockwiseAcrossTwoThresholds_emitsTwoClockwiseTicks() {
        // 0° to 50° = 50/22.5 = 2.22 threshold-widths → 2 ticks.
        evaluate(stickAtAngle(0f))
        evaluate(stickAtAngle((50 * Math.PI / 180).toFloat()))
        assertEquals(
            listOf(
                "scroll_clockwise" to true, "scroll_clockwise" to false,
                "scroll_clockwise" to true, "scroll_clockwise" to false,
            ),
            emitted,
        )
    }

    // ── Threshold crossing — counter-clockwise ───────────────────────────────

    @Test
    fun rotateCounterClockwiseAcrossOneThreshold_emitsOneCcwTick() {
        evaluate(stickAtAngle(0f))
        evaluate(stickAtAngle((-25 * Math.PI / 180).toFloat()))
        assertEquals(
            listOf("scroll_counter_clockwise" to true, "scroll_counter_clockwise" to false),
            emitted,
        )
    }

    // ── Sub-threshold remainder accumulates ──────────────────────────────────

    @Test
    fun subThresholdMotion_emitsNothing_thenAccumulatesIntoNextTick() {
        evaluate(stickAtAngle(0f))                            // baseline
        evaluate(stickAtAngle((10 * Math.PI / 180).toFloat()))  // +10° — sub-threshold
        assertTrue("sub-threshold emits nothing; got $emitted", emitted.isEmpty())
        evaluate(stickAtAngle((25 * Math.PI / 180).toFloat()))  // +25° total — should fire 1 tick
        assertEquals(listOf("scroll_clockwise" to true, "scroll_clockwise" to false), emitted)
    }

    @Test
    fun afterTick_baselineAdvancesExactlyOneThreshold_remainderRetained() {
        // Rotate to 30° (22.5° tick + 7.5° remainder). Baseline should be at
        // 22.5°. Next rotation of just 15° more (total = 45°) should fire
        // another tick at the (22.5° + 22.5°) crossing.
        evaluate(stickAtAngle(0f))
        evaluate(stickAtAngle((30 * Math.PI / 180).toFloat()))  // +30° → 1 tick, baseline at 22.5°
        assertEquals(listOf("scroll_clockwise" to true, "scroll_clockwise" to false), emitted)
        emitted.clear()
        evaluate(stickAtAngle((45 * Math.PI / 180).toFloat()))  // 45 - 22.5 = 22.5° → 1 more tick
        assertEquals(listOf("scroll_clockwise" to true, "scroll_clockwise" to false), emitted)
    }

    // ── Direction reversal ───────────────────────────────────────────────────

    @Test
    fun reverseDirectionMidRotation_firesOppositeTick() {
        evaluate(stickAtAngle(0f))
        evaluate(stickAtAngle((25 * Math.PI / 180).toFloat()))  // CW tick — baseline → 22.5°
        emitted.clear()
        // Now rotate CCW to 0°. From baseline at 22.5° to 0° = −22.5° (just at threshold).
        // (boundary case: |delta| / threshold = 1, so 1 tick).
        evaluate(stickAtAngle(0f))
        assertEquals(
            listOf("scroll_counter_clockwise" to true, "scroll_counter_clockwise" to false),
            emitted,
        )
    }

    // ── Wraparound (±π) ──────────────────────────────────────────────────────

    @Test
    fun rotateAcrossPiWraparound_isShortestPath() {
        // Baseline near +π (just below). Rotate to just past −π (i.e. across
        // the down-position wraparound). The angular delta should be the
        // short way around (a small positive crossing), not the long way
        // (≈ +2π).
        evaluate(stickAtAngle((170 * Math.PI / 180).toFloat()))   // baseline ≈ +170°
        evaluate(stickAtAngle((-165 * Math.PI / 180).toFloat()))  // ≈ −165° (i.e. visually past +180°)
        // Shortest path: 170 → 180 → -180 → -165 = +25° → 1 CW tick.
        assertEquals(listOf("scroll_clockwise" to true, "scroll_clockwise" to false), emitted)
    }

    // ── Invert setting ───────────────────────────────────────────────────────

    @Test
    fun invertSetting_swapsClockwiseAndCcw() {
        evaluate(stickAtAngle(0f), settings = """{"sensitivity":16,"invert_swipe":true}""")
        evaluate(stickAtAngle((25 * Math.PI / 180).toFloat()), settings = """{"sensitivity":16,"invert_swipe":true}""")
        // Without invert, this would be scroll_clockwise. Inverted: ccw.
        assertEquals(
            listOf("scroll_counter_clockwise" to true, "scroll_counter_clockwise" to false),
            emitted,
        )
    }

    // ── Custom threshold ─────────────────────────────────────────────────────

    @Test
    fun customScrollAngle_changesTickResolution() {
        // sensitivity 8 → 360/8 = 45° per tick. Rotate by 50° = 1 tick (not 2).
        val s = """{"sensitivity":8}"""
        evaluate(stickAtAngle(0f), settings = s)
        evaluate(stickAtAngle((50 * Math.PI / 180).toFloat()), settings = s)
        assertEquals(listOf("scroll_clockwise" to true, "scroll_clockwise" to false), emitted)
    }

    @Test
    fun fineScrollAngle_firesMoreTicksPerRotation() {
        // sensitivity 36 → 360/36 = 10° per tick.
        val s = """{"sensitivity":36}"""
        evaluate(stickAtAngle(0f), settings = s)
        // 45° / 10° = 4 ticks.
        evaluate(stickAtAngle((45 * Math.PI / 180).toFloat()), settings = s)
        assertEquals(4, emitted.count { it.first == "scroll_clockwise" && it.second })
        assertEquals(4, emitted.count { it.first == "scroll_clockwise" && !it.second })
        assertFalse("no CCW ticks", emitted.any { it.first == "scroll_counter_clockwise" })
    }

    // ── Custom deadzone ──────────────────────────────────────────────────────

    @Test
    fun customDeadzone_widerThanDefault_silencesSmallDeflections() {
        // Default deadzone 0.20. Set to 0.50 — a 0.4-magnitude stick is now
        // below deadzone and should clear the baseline.
        val s = """{"deadzone_inner_radius":0.5}"""
        evaluate(stickAtAngle(0f, magnitude = 0.8f), settings = s)   // baseline
        evaluate(stickAtAngle(0f, magnitude = 0.4f), settings = s)   // below custom deadzone
        evaluate(stickAtAngle((180 * Math.PI / 180).toFloat(), magnitude = 0.8f), settings = s)
        // The 0.4-mag sample cleared the baseline; the 180° sample sets a
        // fresh baseline. Net emitted = 0.
        assertTrue("deadzone widening clears baseline; got $emitted", emitted.isEmpty())
    }

    // ── resetState ───────────────────────────────────────────────────────────

    @Test
    fun resetState_clearsAllBaselines() {
        evaluate(stickAtAngle(0f))                            // baseline set
        ScrollWheelMode.resetState()
        // After reset, the next deflection is treated as a fresh first sample
        // — no tick from the previous baseline.
        evaluate(stickAtAngle((90 * Math.PI / 180).toFloat()))  // huge angular delta if not reset
        assertTrue("resetState clears state; got $emitted", emitted.isEmpty())
    }

    // ── Per-source isolation ─────────────────────────────────────────────────

    @Test
    fun leftAndRightSticksTrackBaselinesIndependently() {
        // LJ sets its baseline at 0°, RJ at 90°. Then LJ rotates by +25° and
        // RJ doesn't change. Only LJ should fire a tick.
        evaluate(stickAtAngle(0f, source = InputSource.LEFT_JOYSTICK))
        evaluate(stickAtAngle((90 * Math.PI / 180).toFloat(), source = InputSource.RIGHT_JOYSTICK))
        emitted.clear()
        evaluate(stickAtAngle((25 * Math.PI / 180).toFloat(), source = InputSource.LEFT_JOYSTICK))
        assertEquals(listOf("scroll_clockwise" to true, "scroll_clockwise" to false), emitted)
    }

    // ── Mouse emitter quiescence ─────────────────────────────────────────────

    @Test
    fun scrollWheelMode_doesNotTouchMouseEmitter() {
        // Wheel emission flows through the digital-edge → activator → bind path,
        // not directly through MouseEmitter. Verify the mode never calls into
        // mouse at all (cursor / wheel injects come from the activator engine).
        evaluate(stickAtAngle(0f))
        evaluate(stickAtAngle((180 * Math.PI / 180).toFloat()))
        io.mockk.verify(exactly = 0) { mouse.setStickVelocity(any(), any(), any()) }
        io.mockk.verify(exactly = 0) { mouse.addRelativeDelta(any(), any()) }
        io.mockk.verify(exactly = 0) { mouse.scheduleSmoothDelta(any(), any(), any()) }
        io.mockk.verify(exactly = 0) { mouse.setStickAbsoluteTarget(any(), any(), any()) }
    }

    // ── Settings parsing ─────────────────────────────────────────────────────

    @Test
    fun settings_parse_emptyJson_returnsDefaults() {
        assertEquals(ScrollWheelSettings.DEFAULTS, ScrollWheelSettings.parse(""))
    }

    @Test
    fun settings_parse_malformedJson_returnsDefaults() {
        assertEquals(ScrollWheelSettings.DEFAULTS, ScrollWheelSettings.parse("{not json"))
    }

    @Test
    fun settings_parse_partialOverridesPreserveDefaults() {
        val s = ScrollWheelSettings.parse("""{"sensitivity":120}""")
        assertEquals(120f, s.sensitivity, 1e-6f)
        assertEquals(ScrollWheelSettings.DEFAULT_DEADZONE, s.deadzone, 1e-6f)
        assertEquals(false, s.invert)
        assertEquals(ScrollWheelSettings.SwipeDirection.CIRCULAR, s.swipeDirection)
    }

    @Test
    fun settings_parse_clampsValuesAtBoundaries() {
        val s = ScrollWheelSettings.parse(
            """{"deadzone_inner_radius":-1,"sensitivity":-5}"""
        )
        assertTrue("deadzone clamps to ≥ 0", s.deadzone >= 0f)
        assertTrue("sensitivity clamps to ≥ 1", s.sensitivity >= 1f)
    }

    @Test
    fun swipeDirection_horizontal_emitsOnXTravel() {
        // Horizontal swipe: full −1..+1 sweep = sensitivity ticks. sensitivity 10
        // → threshold 0.2 units. Move x from 0 to 0.5 = 2 ticks.
        val s = """{"sensitivity":10,"swipe_direction":"horizontal"}"""
        evaluate(AnalogEvent(InputSource.RIGHT_JOYSTICK, x = 0f, y = -0.8f, timestampMs = 0L), s)
        evaluate(AnalogEvent(InputSource.RIGHT_JOYSTICK, x = 0.5f, y = -0.8f, timestampMs = 0L), s)
        assertEquals(2, emitted.count { it.first == "scroll_clockwise" && it.second })
    }

    @Test
    fun defaultJson_roundTripsThroughParse() {
        assertEquals(
            ScrollWheelSettings.DEFAULTS,
            ScrollWheelSettings.parse(ScrollWheelSettings.DEFAULT_JSON),
        )
    }
}
