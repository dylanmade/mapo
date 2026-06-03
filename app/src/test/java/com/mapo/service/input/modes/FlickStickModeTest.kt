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
 * Behavior tests for [FlickStickMode]. Covers the NEUTRAL → FLICKING →
 * HOLDING state machine, per-source state isolation, snap-to-cardinal,
 * settings parsing, and the source-filter (joysticks only).
 *
 * Each test resets the global per-source state in [@After] so leftover
 * state from a prior test can't bleed into the next.
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

    private fun ctx(settingsJson: String = "") = ModeContext(
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

    // ── Mode metadata ────────────────────────────────────────────────────────

    @Test
    fun mode_isFlickStick() {
        assertEquals(BindingMode.FLICK_STICK, FlickStickMode.mode)
    }

    @Test
    fun validInputs_areClickAndOuterRing() {
        // Matches the joystick-side inputs convention used by JoystickMouseMode
        // / JoystickMoveMode so users can switch between joystick modes without
        // re-binding click/outer_ring activators.
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
    }

    // ── NEUTRAL → FLICKING transition ────────────────────────────────────────

    @Test
    fun stickInsideDeadzone_emitsNothing() {
        FlickStickMode.evaluate(
            reading = reading(x = 0.05f, y = 0.05f, timestampMs = 0L),
            ctx = ctx(),
            digitalEmit = digitalEmit,
            mouse = mouse,
        )
        verify(exactly = 0) { mouse.addRelativeDelta(any(), any()) }
    }

    @Test
    fun firstActiveReading_emitsFullFlickImmediately() {
        // Steam-faithful (verified on Steam Deck by user 2026-06-03): the
        // flick fires the *full* target angle in one delta at the moment
        // the stick crosses the activation ring. Push stick right (x=1,
        // y=0) → target = atan2(1, 0) = π/2. One addRelativeDelta call
        // with dx = π/2 * velocity ≈ 2356px (at velocity=1500).
        val dxSlot = slot<Float>()
        every { mouse.addRelativeDelta(capture(dxSlot), 0f) } returns Unit
        FlickStickMode.evaluate(
            reading = reading(x = 1f, y = 0f, timestampMs = 0L),
            ctx = ctx(),
            digitalEmit = digitalEmit,
            mouse = mouse,
        )
        val expectedPx = kotlin.math.PI.toFloat() / 2f *
            FlickStickSettings.DEFAULT_VELOCITY_PX_PER_RAD
        assertEquals(expectedPx, dxSlot.captured, 1f)
    }

    @Test
    fun flickRightward_emitsPositiveDx() {
        val dxSlot = slot<Float>()
        every { mouse.addRelativeDelta(capture(dxSlot), 0f) } returns Unit
        FlickStickMode.evaluate(
            reading = reading(x = 1f, y = 0f, timestampMs = 0L),
            ctx = ctx(),
            digitalEmit = digitalEmit,
            mouse = mouse,
        )
        assertTrue("dx should be positive for rightward flick", dxSlot.captured > 0f)
    }

    @Test
    fun flickLeftward_emitsNegativeDx() {
        val dxSlot = slot<Float>()
        every { mouse.addRelativeDelta(capture(dxSlot), 0f) } returns Unit
        FlickStickMode.evaluate(
            reading = reading(x = -1f, y = 0f, timestampMs = 0L),
            ctx = ctx(),
            digitalEmit = digitalEmit,
            mouse = mouse,
        )
        assertTrue("dx should be negative for leftward flick", dxSlot.captured < 0f)
    }

    @Test
    fun flickStraightUp_emitsZeroAngle() {
        // Stick up (x=0, y=-1) → target angle atan2(0, 1) = 0 → no flick.
        FlickStickMode.evaluate(
            reading = reading(x = 0f, y = -1f, timestampMs = 0L),
            ctx = ctx(),
            digitalEmit = digitalEmit,
            mouse = mouse,
        )
        verify(exactly = 0) { mouse.addRelativeDelta(neq(0f), any()) }
    }

    @Test
    fun flick180Down_emitsFullPiAngle() {
        // Stick down (x=0, y=+1) → atan2(0, -1) = π → flick is a full
        // 180° turn (Steam-Deck-verified by user 2026-06-03).
        val dxSlot = slot<Float>()
        every { mouse.addRelativeDelta(capture(dxSlot), 0f) } returns Unit
        FlickStickMode.evaluate(
            reading = reading(x = 0f, y = 1f, timestampMs = 0L),
            ctx = ctx(),
            digitalEmit = digitalEmit,
            mouse = mouse,
        )
        val expectedPx = kotlin.math.PI.toFloat() *
            FlickStickSettings.DEFAULT_VELOCITY_PX_PER_RAD
        assertEquals(expectedPx, dxSlot.captured, 1f)
    }

    @Test
    fun flickPartialAngle_emitsProportionalAmount() {
        // 45° east push → +π/4 flick. Confirms the angle-not-just-cardinal
        // observation: a 45° flick produces a 45° turn (user-verified on
        // Steam Deck 2026-06-03).
        val dxSlot = slot<Float>()
        every { mouse.addRelativeDelta(capture(dxSlot), 0f) } returns Unit
        // (x, y) = (sin(π/4), -cos(π/4)) = (0.707, -0.707) — stick pointing
        // up-and-to-the-right at 45° from screen-up.
        FlickStickMode.evaluate(
            reading = reading(x = 0.707f, y = -0.707f, timestampMs = 0L),
            ctx = ctx(),
            digitalEmit = digitalEmit,
            mouse = mouse,
        )
        val expectedPx = kotlin.math.PI.toFloat() / 4f *
            FlickStickSettings.DEFAULT_VELOCITY_PX_PER_RAD
        assertEquals(expectedPx, dxSlot.captured, 1f)
    }

    // ── HOLDING phase ────────────────────────────────────────────────────────

    @Test
    fun holdPhase_emitsDeltaProportionalToStickRotation() {
        // First event fires the flick to +π/2 and transitions to HOLDING
        // with lastStickAngle = +π/2. Second event rotates stick CW
        // (toward "down"): atan2(0.707, -0.707) → wait, that's NW. We want
        // (x=0.707, y=+0.707) = SE which is atan2(0.707, -0.707)... hmm
        // let me re-check. With y-down convention, "down-and-to-the-right"
        // is (x=+0.707, y=+0.707). atan2(0.707, -0.707) = 3π/4. So delta
        // from +π/2 to 3π/4 is +π/4 > 0 → positive dx.
        FlickStickMode.evaluate(
            reading = reading(x = 1f, y = 0f, timestampMs = 0L),
            ctx = ctx(),
            digitalEmit = digitalEmit,
            mouse = mouse,
        )
        val dxSlot = slot<Float>()
        every { mouse.addRelativeDelta(capture(dxSlot), 0f) } returns Unit
        FlickStickMode.evaluate(
            reading = reading(x = 0.707f, y = 0.707f, timestampMs = 10L),
            ctx = ctx(),
            digitalEmit = digitalEmit,
            mouse = mouse,
        )
        assertTrue("hold-phase CW rotation should emit positive dx", dxSlot.captured > 0f)
    }

    @Test
    fun holdPhase_oppositeRotationEmitsNegativeDx() {
        FlickStickMode.evaluate(
            reading = reading(x = 1f, y = 0f, timestampMs = 0L),
            ctx = ctx(),
            digitalEmit = digitalEmit,
            mouse = mouse,
        )
        // Rotate CCW from +π/2 (east) toward +π/4 (NE): stick (0.707, -0.707).
        val dxSlot = slot<Float>()
        every { mouse.addRelativeDelta(capture(dxSlot), 0f) } returns Unit
        FlickStickMode.evaluate(
            reading = reading(x = 0.707f, y = -0.707f, timestampMs = 10L),
            ctx = ctx(),
            digitalEmit = digitalEmit,
            mouse = mouse,
        )
        assertTrue("hold-phase CCW rotation should emit negative dx", dxSlot.captured < 0f)
    }

    @Test
    fun releaseToCenter_resetsState_nextPushReFlicks() {
        // First push → flick to the right and transition to HOLDING.
        FlickStickMode.evaluate(
            reading = reading(x = 1f, y = 0f, timestampMs = 0L),
            ctx = ctx(),
            digitalEmit = digitalEmit,
            mouse = mouse,
        )
        // Release to center.
        FlickStickMode.evaluate(
            reading = reading(x = 0f, y = 0f, timestampMs = 100L),
            ctx = ctx(),
            digitalEmit = digitalEmit,
            mouse = mouse,
        )
        // Second push (leftward) — must re-flick to −π/2, not interpret
        // as a hold-phase rotation from +π/2 (which would emit +π delta,
        // i.e. a 180° turn the WRONG way).
        val dxSlot = slot<Float>()
        every { mouse.addRelativeDelta(capture(dxSlot), 0f) } returns Unit
        FlickStickMode.evaluate(
            reading = reading(x = -1f, y = 0f, timestampMs = 200L),
            ctx = ctx(),
            digitalEmit = digitalEmit,
            mouse = mouse,
        )
        val expectedPx = -kotlin.math.PI.toFloat() / 2f *
            FlickStickSettings.DEFAULT_VELOCITY_PX_PER_RAD
        assertEquals(
            "re-flick after center-release must fire fresh −π/2 target",
            expectedPx, dxSlot.captured, 1f,
        )
    }

    // ── Per-source state isolation ───────────────────────────────────────────

    @Test
    fun leftAndRightStick_haveIndependentFlickState() {
        // Left stick flicks right; right stick independently flicks left.
        // Both fire on first-event activation and must not share state.
        FlickStickMode.evaluate(
            reading = reading(source = InputSource.LEFT_JOYSTICK, x = 1f, y = 0f, timestampMs = 0L),
            ctx = ctx().copy(source = InputSource.LEFT_JOYSTICK),
            digitalEmit = digitalEmit,
            mouse = mouse,
        )
        val dxSlot = slot<Float>()
        every { mouse.addRelativeDelta(capture(dxSlot), 0f) } returns Unit
        FlickStickMode.evaluate(
            reading = reading(source = InputSource.RIGHT_JOYSTICK, x = -1f, y = 0f, timestampMs = 0L),
            ctx = ctx(),  // RIGHT_JOYSTICK
            digitalEmit = digitalEmit,
            mouse = mouse,
        )
        assertTrue(
            "right stick's leftward flick must produce negative dx regardless of left stick's prior state",
            dxSlot.captured < 0f,
        )
    }

    // ── Snap-to-cardinal ─────────────────────────────────────────────────────

    @Test
    fun snapMode_off_doesNotChangeFlickTarget() {
        val s = FlickStickSettings.DEFAULTS
        assertEquals(0.7f, s.snapAngle(0.7f), 1e-6f)
        assertEquals(-0.7f, s.snapAngle(-0.7f), 1e-6f)
    }

    @Test
    fun snapMode_fourWay_fullStrength_snapsToNearestCardinal() {
        val s = FlickStickSettings.DEFAULTS.copy(
            snapMode = FlickStickSettings.SnapMode.FOUR_WAY,
            snapStrength = 1f,
        )
        // 0.4 rad (~23°) snaps to 0 (north).
        assertEquals(0f, s.snapAngle(0.4f), 1e-5f)
        // 1.3 rad (~74°) snaps to π/2 (~1.5708, east).
        assertEquals(kotlin.math.PI.toFloat() / 2f, s.snapAngle(1.3f), 1e-5f)
    }

    @Test
    fun snapMode_eightWay_snapsToNearest45Deg() {
        val s = FlickStickSettings.DEFAULTS.copy(
            snapMode = FlickStickSettings.SnapMode.EIGHT_WAY,
            snapStrength = 1f,
        )
        // 0.5 rad (~28.6°) snaps to π/4 (45°, NE).
        assertEquals(kotlin.math.PI.toFloat() / 4f, s.snapAngle(0.5f), 1e-5f)
    }

    @Test
    fun snapMode_partialStrength_blendsTowardNearest() {
        val s = FlickStickSettings.DEFAULTS.copy(
            snapMode = FlickStickSettings.SnapMode.FOUR_WAY,
            snapStrength = 0.5f,
        )
        // Raw 0.4, nearest cardinal 0, midpoint 0.2.
        assertEquals(0.2f, s.snapAngle(0.4f), 1e-5f)
    }

    // ── Settings parsing ─────────────────────────────────────────────────────

    @Test
    fun settings_parse_emptyString_returnsDefaults() {
        val s = FlickStickSettings.parse("")
        assertEquals(FlickStickSettings.DEFAULTS, s)
    }

    @Test
    fun settings_parse_malformedJson_fallsBackToDefaults() {
        val s = FlickStickSettings.parse("not valid json {")
        assertEquals(FlickStickSettings.DEFAULTS, s)
    }

    @Test
    fun settings_parse_partialOverridesPreserveDefaults() {
        val s = FlickStickSettings.parse("""{"flick_time":50}""")
        assertEquals(50, s.flickTimeMs)
        assertEquals(FlickStickSettings.DEFAULT_DEADZONE, s.deadzone, 1e-6f)
        assertEquals(FlickStickSettings.DEFAULT_VELOCITY_PX_PER_RAD, s.velocityPxPerRad, 1e-6f)
    }

    @Test
    fun settings_parse_clampsOutOfRangeValues() {
        val s = FlickStickSettings.parse(
            """{"flick_deadzone":-5,"flick_exponent":99,"flick_velocity":-100}"""
        )
        assertTrue("deadzone must clamp to ≥ 0.05", s.deadzone >= 0.05f)
        assertTrue("exponent must clamp to ≤ 5", s.exponent <= 5f)
        assertTrue("velocity must clamp to ≥ 1", s.velocityPxPerRad >= 1f)
    }

    @Test
    fun settings_parse_snapModeAcceptsVariants() {
        assertEquals(
            FlickStickSettings.SnapMode.FOUR_WAY,
            FlickStickSettings.parse("""{"flick_snap_mode":"4_way"}""").snapMode,
        )
        assertEquals(
            FlickStickSettings.SnapMode.EIGHT_WAY,
            FlickStickSettings.parse("""{"flick_snap_mode":"8_way"}""").snapMode,
        )
        assertEquals(
            FlickStickSettings.SnapMode.NONE,
            FlickStickSettings.parse("""{"flick_snap_mode":"garbage"}""").snapMode,
        )
    }

    @Test
    fun defaultJson_roundTripsThroughParse() {
        val s = FlickStickSettings.parse(FlickStickSettings.DEFAULT_JSON)
        assertEquals(FlickStickSettings.DEFAULTS, s)
    }

    // ── resetState() ─────────────────────────────────────────────────────────

    @Test
    fun resetState_clearsPerSourceState() {
        // Push stick right → flick fires, now in HOLDING.
        FlickStickMode.evaluate(
            reading = reading(x = 1f, y = 0f, timestampMs = 0L),
            ctx = ctx(),
            digitalEmit = digitalEmit,
            mouse = mouse,
        )
        FlickStickMode.resetState()
        // Without reset, this leftward stick on next event would be
        // interpreted as a hold-phase rotation from +π/2 → −π/2 (a +π
        // delta wrap, large positive dx). With reset, NEUTRAL → fresh
        // flick to −π/2 → expected negative dx.
        val dxSlot = slot<Float>()
        every { mouse.addRelativeDelta(capture(dxSlot), 0f) } returns Unit
        FlickStickMode.evaluate(
            reading = reading(x = -1f, y = 0f, timestampMs = 100L),
            ctx = ctx(),
            digitalEmit = digitalEmit,
            mouse = mouse,
        )
        assertTrue("post-reset push must re-flick (negative dx)", dxSlot.captured < 0f)
    }
}
