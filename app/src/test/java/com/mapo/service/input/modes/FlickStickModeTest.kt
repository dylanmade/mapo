package com.mapo.service.input.modes

import com.mapo.data.model.steam.BindingMode
import com.mapo.data.model.steam.InputSource
import com.mapo.service.input.AnalogEvent
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Behavior tests for [FlickStickMode] — the NEUTRAL → HOLDING flick/sweep state
 * machine, snapping, output axis/invert, front-angle deadzone, flick-on-awake,
 * per-source isolation, and settings parsing. Pure settings math lives in the
 * companion ([FlickStickSettings]).
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class FlickStickModeTest {

    private val mouse = mockk<MouseEmitter>(relaxed = true)
    private val gamepad = mockk<GamepadEmitter>(relaxed = true)

    private val digitalEmitted = mutableListOf<Pair<String, Boolean>>()
    private val digitalEmit: (String, Boolean) -> Unit = { k, v -> digitalEmitted += k to v }

    @After
    fun reset() {
        FlickStickMode.resetState()
        digitalEmitted.clear()
    }

    // No snapping + no front-angle deadzone, and flick-on-awake so a deflected
    // first reading flicks (tests don't prime with a centered reading) → assert raw
    // flick targets cleanly.
    private val rawJson =
        """{"snap_angle":"no_snapping","front_angle_deadzone":0,"flick_on_awake":true}"""

    private fun ctx(settingsJson: String = rawJson) = ModeContext(
        source = InputSource.RIGHT_JOYSTICK,
        settingsJson = settingsJson,
        priorLatched = emptyMap(),
        activeLayerIds = emptyList(),
        gamepad = gamepad,
    )

    private fun reading(
        source: InputSource = InputSource.RIGHT_JOYSTICK,
        x: Float,
        y: Float,
        timestampMs: Long = 0L,
    ) = AnalogEvent(source = source, x = x, y = y, timestampMs = timestampMs)

    private val defaultVel = FlickStickSettings.DEFAULTS.velocityPxPerRad

    // ── Mode metadata ────────────────────────────────────────────────────────

    @Test
    fun mode_isFlickStick() {
        assertEquals(BindingMode.FLICK_STICK, FlickStickMode.mode)
    }

    @Test
    fun validInputs_areClickAndOuterRing() {
        assertEquals(setOf("click", "outer_ring"), FlickStickMode.validInputs())
    }

    @Test
    fun nonJoystickSource_isNoOp() {
        FlickStickMode.evaluate(
            reading = reading(source = InputSource.GYRO, x = 1f, y = 0f),
            ctx = ctx(),
            digitalEmit = digitalEmit,
            mouse = mouse,
        )
        verify(exactly = 0) { mouse.addRelativeDelta(any(), any()) }
        verify(exactly = 0) { mouse.scheduleSmoothDelta(any(), any(), any()) }
    }

    // ── NEUTRAL → flick ──────────────────────────────────────────────────────

    @Test
    fun stickInsideInnerDeadzone_emitsNothing() {
        // Default inner deadzone is 50%; (0.2, 0.2) magnitude ~0.28 is inside.
        FlickStickMode.evaluate(
            reading = reading(x = 0.2f, y = 0.2f, timestampMs = 0L),
            ctx = ctx(),
            digitalEmit = digitalEmit,
            mouse = mouse,
        )
        verify(exactly = 0) { mouse.scheduleSmoothDelta(any(), any(), any()) }
    }

    @Test
    fun flickRight_schedulesPositiveHorizontalDelta() {
        val dxSlot = slot<Float>()
        every { mouse.scheduleSmoothDelta(capture(dxSlot), 0f, any()) } returns Unit
        FlickStickMode.evaluate(
            reading = reading(x = 1f, y = 0f, timestampMs = 0L),
            ctx = ctx(),
            digitalEmit = digitalEmit,
            mouse = mouse,
        )
        val expected = kotlin.math.PI.toFloat() / 2f * defaultVel
        assertEquals(expected, dxSlot.captured, 1f)
    }

    @Test
    fun flickLeft_schedulesNegativeHorizontalDelta() {
        val dxSlot = slot<Float>()
        every { mouse.scheduleSmoothDelta(capture(dxSlot), 0f, any()) } returns Unit
        FlickStickMode.evaluate(
            reading = reading(x = -1f, y = 0f, timestampMs = 0L),
            ctx = ctx(),
            digitalEmit = digitalEmit,
            mouse = mouse,
        )
        assertTrue("left flick should be negative dx", dxSlot.captured < 0f)
    }

    @Test
    fun frontAngleDeadzone_suppressesNearForwardFlick() {
        // flick-on-awake so the stick can flick immediately; default 7° front-angle
        // deadzone; a flick straight up (angle 0) must still be suppressed.
        FlickStickMode.evaluate(
            reading = reading(x = 0f, y = -1f, timestampMs = 0L),
            ctx = ctx(settingsJson = """{"flick_on_awake":true}"""),
            digitalEmit = digitalEmit,
            mouse = mouse,
        )
        verify(exactly = 0) { mouse.scheduleSmoothDelta(any(), any(), any()) }
    }

    @Test
    fun outputVertical_routesFlickToYAxis() {
        val dySlot = slot<Float>()
        every { mouse.scheduleSmoothDelta(0f, capture(dySlot), any()) } returns Unit
        FlickStickMode.evaluate(
            reading = reading(x = 1f, y = 0f, timestampMs = 0L),
            ctx = ctx(settingsJson = """{"snap_angle":"no_snapping","front_angle_deadzone":0,"flick_on_awake":true,"output_axis":"vertical"}"""),
            digitalEmit = digitalEmit,
            mouse = mouse,
        )
        assertTrue("vertical output should write the y axis", dySlot.captured > 0f)
    }

    @Test
    fun invertOutput_flipsFlickSign() {
        val dxSlot = slot<Float>()
        every { mouse.scheduleSmoothDelta(capture(dxSlot), 0f, any()) } returns Unit
        FlickStickMode.evaluate(
            reading = reading(x = 1f, y = 0f, timestampMs = 0L),
            ctx = ctx(settingsJson = """{"snap_angle":"no_snapping","front_angle_deadzone":0,"flick_on_awake":true,"invert_output":true}"""),
            digitalEmit = digitalEmit,
            mouse = mouse,
        )
        // Rightward flick (+π/2) inverted → negative.
        assertTrue("invert should flip the flick to negative", dxSlot.captured < 0f)
    }

    @Test
    fun flickOnAwake_false_suppressesFlickWhenAlreadyDeflected() {
        // Fresh state, first reading already past inner deadzone, flick_on_awake off
        // → no flick fires (must return home first).
        FlickStickMode.evaluate(
            reading = reading(x = 1f, y = 0f, timestampMs = 0L),
            ctx = ctx(settingsJson = """{"snap_angle":"no_snapping","front_angle_deadzone":0,"flick_on_awake":false}"""),
            digitalEmit = digitalEmit,
            mouse = mouse,
        )
        verify(exactly = 0) { mouse.scheduleSmoothDelta(any(), any(), any()) }
    }

    @Test
    fun flickOnAwake_true_flicksImmediatelyWhenDeflected() {
        every { mouse.scheduleSmoothDelta(any(), any(), any()) } returns Unit
        FlickStickMode.evaluate(
            reading = reading(x = 1f, y = 0f, timestampMs = 0L),
            ctx = ctx(settingsJson = """{"snap_angle":"no_snapping","front_angle_deadzone":0,"flick_on_awake":true}"""),
            digitalEmit = digitalEmit,
            mouse = mouse,
        )
        verify(exactly = 1) { mouse.scheduleSmoothDelta(any(), 0f, any()) }
    }

    // ── HOLDING / sweep ──────────────────────────────────────────────────────

    @Test
    fun holdPhase_isLockedOut_duringFlickTimeWindow() {
        every { mouse.scheduleSmoothDelta(any(), any(), any()) } returns Unit
        FlickStickMode.evaluate(
            reading = reading(x = 1f, y = 0f, timestampMs = 0L),
            ctx = ctx(),
            digitalEmit = digitalEmit,
            mouse = mouse,
        )
        // 30ms in (inside the ~90ms default flick window) — no sweep emission.
        FlickStickMode.evaluate(
            reading = reading(x = 0.996f, y = 0.087f, timestampMs = 30L),
            ctx = ctx(),
            digitalEmit = digitalEmit,
            mouse = mouse,
        )
        verify(exactly = 0) { mouse.addRelativeDelta(any(), any()) }
    }

    @Test
    fun holdPhase_emitsSweepAfterLockout() {
        every { mouse.scheduleSmoothDelta(any(), any(), any()) } returns Unit
        FlickStickMode.evaluate(
            reading = reading(x = 1f, y = 0f, timestampMs = 0L),
            ctx = ctx(),
            digitalEmit = digitalEmit,
            mouse = mouse,
        )
        // Settle pass well past the lockout.
        FlickStickMode.evaluate(
            reading = reading(x = 1f, y = 0f, timestampMs = 300L),
            ctx = ctx(),
            digitalEmit = digitalEmit,
            mouse = mouse,
        )
        // Now rotate CW toward +3π/4 → positive sweep dx.
        val dxSlot = slot<Float>()
        every { mouse.addRelativeDelta(capture(dxSlot), 0f) } returns Unit
        FlickStickMode.evaluate(
            reading = reading(x = 0.707f, y = 0.707f, timestampMs = 320L),
            ctx = ctx(),
            digitalEmit = digitalEmit,
            mouse = mouse,
        )
        assertTrue("CW sweep should emit positive dx", dxSlot.captured > 0f)
    }

    @Test
    fun releaseToCenter_resetsState_nextPushReFlicks() {
        val dxSlot = slot<Float>()
        every { mouse.scheduleSmoothDelta(capture(dxSlot), 0f, any()) } returns Unit
        FlickStickMode.evaluate(
            reading = reading(x = 1f, y = 0f, timestampMs = 0L),
            ctx = ctx(), digitalEmit = digitalEmit, mouse = mouse,
        )
        FlickStickMode.evaluate(
            reading = reading(x = 0f, y = 0f, timestampMs = 200L),
            ctx = ctx(), digitalEmit = digitalEmit, mouse = mouse,
        )
        FlickStickMode.evaluate(
            reading = reading(x = -1f, y = 0f, timestampMs = 300L),
            ctx = ctx(), digitalEmit = digitalEmit, mouse = mouse,
        )
        val expected = -kotlin.math.PI.toFloat() / 2f * defaultVel
        assertEquals("re-flick after center release fires fresh −π/2", expected, dxSlot.captured, 1f)
    }

    @Test
    fun leftAndRightStick_haveIndependentFlickState() {
        val dxCalls = mutableListOf<Float>()
        every { mouse.scheduleSmoothDelta(any(), 0f, any()) } answers { dxCalls += firstArg<Float>() }
        FlickStickMode.evaluate(
            reading = reading(source = InputSource.LEFT_JOYSTICK, x = 1f, y = 0f, timestampMs = 0L),
            ctx = ctx().copy(source = InputSource.LEFT_JOYSTICK),
            digitalEmit = digitalEmit, mouse = mouse,
        )
        FlickStickMode.evaluate(
            reading = reading(source = InputSource.RIGHT_JOYSTICK, x = -1f, y = 0f, timestampMs = 0L),
            ctx = ctx(), digitalEmit = digitalEmit, mouse = mouse,
        )
        assertEquals(2, dxCalls.size)
        assertTrue("left stick scheduled positive dx", dxCalls[0] > 0f)
        assertTrue("right stick scheduled negative dx", dxCalls[1] < 0f)
    }

    // ── Snapping ─────────────────────────────────────────────────────────────

    @Test
    fun snap_none_doesNotChangeTarget() {
        val s = FlickStickSettings.DEFAULTS.copy(snapMode = FlickStickSettings.SnapMode.NONE)
        assertEquals(0.7f, s.snapAngle(0.7f), 1e-6f)
    }

    @Test
    fun snap_ninety_quantizesToNearestQuarter() {
        val s = FlickStickSettings.DEFAULTS.copy(snapMode = FlickStickSettings.SnapMode.QUARTER)
        assertEquals(0f, s.snapAngle(0.4f), 1e-5f) // ~23° → 0
        assertEquals(kotlin.math.PI.toFloat() / 2f, s.snapAngle(1.3f), 1e-5f) // ~74° → 90°
    }

    @Test
    fun snap_eighths_quantizesTo45() {
        val s = FlickStickSettings.DEFAULTS.copy(snapMode = FlickStickSettings.SnapMode.EIGHTHS)
        assertEquals(kotlin.math.PI.toFloat() / 4f, s.snapAngle(0.5f), 1e-5f) // ~28.6° → 45°
    }

    @Test
    fun snap_forwardOnly_snapsNearForward_passesThroughElsewhere() {
        val s = FlickStickSettings.DEFAULTS.copy(snapMode = FlickStickSettings.SnapMode.FORWARD_ONLY)
        assertEquals("near-forward snaps to 0", 0f, s.snapAngle(0.3f), 1e-6f)
        val big = kotlin.math.PI.toFloat() / 2f // 90° — outside the ±45° window
        assertEquals("far-from-forward passes through", big, s.snapAngle(big), 1e-6f)
    }

    // ── Settings parsing ─────────────────────────────────────────────────────

    @Test
    fun parse_emptyAndEmptyObject_returnDefaults() {
        assertEquals(FlickStickSettings.DEFAULTS, FlickStickSettings.parse(""))
        assertEquals(FlickStickSettings.DEFAULTS, FlickStickSettings.parse("{}"))
    }

    @Test
    fun parse_malformed_fallsBackToDefaults() {
        assertEquals(FlickStickSettings.DEFAULTS, FlickStickSettings.parse("not json {"))
    }

    @Test
    fun parse_dotsPer360_derivesVelocityPxPerRad() {
        val s = FlickStickSettings.parse("""{"dots_per_360":6283}""")
        // 6283 / 2π ≈ 1000 px/rad.
        assertEquals(1000f, s.velocityPxPerRad, 1f)
    }

    @Test
    fun flickTurnTightness_higherMeansShorterFlick() {
        val tight = FlickStickSettings.parse("""{"flick_turn_tightness":100}""").flickTimeMs
        val loose = FlickStickSettings.parse("""{"flick_turn_tightness":0}""").flickTimeMs
        assertTrue("higher tightness = shorter flick time", tight < loose)
    }

    @Test
    fun parse_snapAngleVariants() {
        assertEquals(FlickStickSettings.SnapMode.NONE, FlickStickSettings.parse("""{"snap_angle":"no_snapping"}""").snapMode)
        assertEquals(FlickStickSettings.SnapMode.HALF, FlickStickSettings.parse("""{"snap_angle":"180"}""").snapMode)
        assertEquals(FlickStickSettings.SnapMode.QUARTER, FlickStickSettings.parse("""{"snap_angle":"90"}""").snapMode)
        assertEquals(FlickStickSettings.SnapMode.SIXTHS, FlickStickSettings.parse("""{"snap_angle":"sixths"}""").snapMode)
        assertEquals(FlickStickSettings.SnapMode.EIGHTHS, FlickStickSettings.parse("""{"snap_angle":"eighths"}""").snapMode)
        // Default + unknown both fall to forward_only.
        assertEquals(FlickStickSettings.SnapMode.FORWARD_ONLY, FlickStickSettings.parse("""{"snap_angle":"garbage"}""").snapMode)
        assertEquals(FlickStickSettings.SnapMode.FORWARD_ONLY, FlickStickSettings.DEFAULTS.snapMode)
    }

    @Test
    fun parse_clampsOutOfRange() {
        val s = FlickStickSettings.parse(
            """{"dots_per_360":99999,"sweep_sensitivity":99,"release_dampening":-5}""",
        )
        assertTrue(s.dotsPer360 <= 32000f)
        assertTrue(s.sweepSensitivity <= 6f)
        assertTrue(s.releaseDampening >= 0f)
    }
}
