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
    fun firstActiveReading_schedulesSmoothFlickOverFlickTime() {
        // Steam-faithful (verified on Steam Deck by user 2026-06-03): the
        // flick commits at threshold crossing and plays out over
        // flick_time via the mouse emitter. We assert the scheduled total
        // matches `target * velocity` and the duration matches flick_time.
        val dxSlot = slot<Float>()
        val durSlot = slot<Long>()
        every {
            mouse.scheduleSmoothDelta(capture(dxSlot), 0f, capture(durSlot))
        } returns Unit
        FlickStickMode.evaluate(
            reading = reading(x = 1f, y = 0f, timestampMs = 0L),
            ctx = ctx(),
            digitalEmit = digitalEmit,
            mouse = mouse,
        )
        val expectedPx = kotlin.math.PI.toFloat() / 2f *
            FlickStickSettings.DEFAULT_VELOCITY_PX_PER_RAD
        assertEquals(expectedPx, dxSlot.captured, 1f)
        assertEquals(
            FlickStickSettings.DEFAULT_FLICK_TIME_MS.toLong(),
            durSlot.captured,
        )
    }

    @Test
    fun flickRightward_schedulesPositiveDx() {
        val dxSlot = slot<Float>()
        every {
            mouse.scheduleSmoothDelta(capture(dxSlot), 0f, any())
        } returns Unit
        FlickStickMode.evaluate(
            reading = reading(x = 1f, y = 0f, timestampMs = 0L),
            ctx = ctx(),
            digitalEmit = digitalEmit,
            mouse = mouse,
        )
        assertTrue("dx should be positive for rightward flick", dxSlot.captured > 0f)
    }

    @Test
    fun flickLeftward_schedulesNegativeDx() {
        val dxSlot = slot<Float>()
        every {
            mouse.scheduleSmoothDelta(capture(dxSlot), 0f, any())
        } returns Unit
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
        verify(exactly = 0) {
            mouse.scheduleSmoothDelta(neq(0f), any(), any())
        }
    }

    @Test
    fun flick180Down_schedulesFullPiAngle() {
        // Stick down (x=0, y=+1) → atan2(0, -1) = π → flick is a full
        // 180° turn (Steam-Deck-verified by user 2026-06-03).
        val dxSlot = slot<Float>()
        every {
            mouse.scheduleSmoothDelta(capture(dxSlot), 0f, any())
        } returns Unit
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
    fun flickPartialAngle_schedulesProportionalAmount() {
        // 45° east push → +π/4 flick. Confirms the angle-not-just-cardinal
        // observation: a 45° flick produces a 45° turn (user-verified on
        // Steam Deck 2026-06-03).
        val dxSlot = slot<Float>()
        every {
            mouse.scheduleSmoothDelta(capture(dxSlot), 0f, any())
        } returns Unit
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

    @Test
    fun activation_doesNotCall_addRelativeDelta_anymore() {
        // Regression guard: the prior instant-snap model fired the flick
        // through addRelativeDelta. The Steam-parity model schedules via
        // scheduleSmoothDelta instead — addRelativeDelta is only used by
        // the hold-phase emissions now.
        FlickStickMode.evaluate(
            reading = reading(x = 1f, y = 0f, timestampMs = 0L),
            ctx = ctx(),
            digitalEmit = digitalEmit,
            mouse = mouse,
        )
        verify(exactly = 0) { mouse.addRelativeDelta(any(), any()) }
    }

    @Test
    fun holdPhase_isLockedOut_duringFlickTimeWindow() {
        // The reported "bounce-back" artifact: when the user's stick angle
        // drifts during the finish of their push (e.g. activation at 89°,
        // stabilizes at 95°), hold-phase rotation must NOT fire while the
        // flick is still resolving.
        every {
            mouse.scheduleSmoothDelta(any(), any(), any())
        } returns Unit
        // Activation at t=0, target = +π/2.
        FlickStickMode.evaluate(
            reading = reading(x = 1f, y = 0f, timestampMs = 0L),
            ctx = ctx(),
            digitalEmit = digitalEmit,
            mouse = mouse,
        )
        // 50ms in, still inside the 100ms flick_time window — stick has
        // drifted by ~5°. Hold-phase MUST NOT emit.
        FlickStickMode.evaluate(
            reading = reading(x = 0.996f, y = 0.087f, timestampMs = 50L),
            ctx = ctx(),
            digitalEmit = digitalEmit,
            mouse = mouse,
        )
        verify(exactly = 0) { mouse.addRelativeDelta(any(), any()) }
    }

    @Test
    fun holdPhase_baselinesAtCurrentAngle_afterLockoutEnds() {
        // Right after the lockout, the next stick reading re-baselines
        // lastStickAngleRad without emitting (so post-lockout hold-phase
        // measures intentional rotation from where the stick *currently
        // is*, not from the activation target). Subsequent reading then
        // emits a delta relative to the settled baseline.
        every {
            mouse.scheduleSmoothDelta(any(), any(), any())
        } returns Unit
        // Activation: target +π/2 at t=0.
        FlickStickMode.evaluate(
            reading = reading(x = 1f, y = 0f, timestampMs = 0L),
            ctx = ctx(),
            digitalEmit = digitalEmit,
            mouse = mouse,
        )
        // After lockout (t > 100ms): stick has drifted to ~105°. The
        // settle pass must NOT emit a delta from 90° → 105°.
        FlickStickMode.evaluate(
            reading = reading(x = 0.966f, y = 0.259f, timestampMs = 150L),
            ctx = ctx(),
            digitalEmit = digitalEmit,
            mouse = mouse,
        )
        verify(exactly = 0) { mouse.addRelativeDelta(any(), any()) }

        // Next read: stick continues to rotate (intentional this time) →
        // the delta from the settled baseline should emit.
        val dxSlot = slot<Float>()
        every {
            mouse.addRelativeDelta(capture(dxSlot), 0f)
        } returns Unit
        FlickStickMode.evaluate(
            reading = reading(x = 0.866f, y = 0.5f, timestampMs = 160L),  // ~120°
            ctx = ctx(),
            digitalEmit = digitalEmit,
            mouse = mouse,
        )
        assertTrue(
            "post-settle rotation should emit a hold-phase delta",
            dxSlot.captured > 0f,
        )
    }

    // ── HOLDING phase ────────────────────────────────────────────────────────

    @Test
    fun holdPhase_emitsDeltaProportionalToStickRotation() {
        // After activation at t=0, the lockout runs through t=100ms.
        // The first post-lockout event re-baselines the angle (no emit).
        // The next event past that emits a delta relative to the settled
        // baseline.
        every { mouse.scheduleSmoothDelta(any(), any(), any()) } returns Unit
        FlickStickMode.evaluate(
            reading = reading(x = 1f, y = 0f, timestampMs = 0L),
            ctx = ctx(),
            digitalEmit = digitalEmit,
            mouse = mouse,
        )
        // Settle pass at t=110ms: lastStickAngleRad re-baselined to π/2.
        FlickStickMode.evaluate(
            reading = reading(x = 1f, y = 0f, timestampMs = 110L),
            ctx = ctx(),
            digitalEmit = digitalEmit,
            mouse = mouse,
        )
        // CW rotation from +π/2 toward +3π/4 (stick = (0.707, +0.707)).
        val dxSlot = slot<Float>()
        every { mouse.addRelativeDelta(capture(dxSlot), 0f) } returns Unit
        FlickStickMode.evaluate(
            reading = reading(x = 0.707f, y = 0.707f, timestampMs = 120L),
            ctx = ctx(),
            digitalEmit = digitalEmit,
            mouse = mouse,
        )
        assertTrue("hold-phase CW rotation should emit positive dx", dxSlot.captured > 0f)
    }

    @Test
    fun holdPhase_oppositeRotationEmitsNegativeDx() {
        every { mouse.scheduleSmoothDelta(any(), any(), any()) } returns Unit
        FlickStickMode.evaluate(
            reading = reading(x = 1f, y = 0f, timestampMs = 0L),
            ctx = ctx(),
            digitalEmit = digitalEmit,
            mouse = mouse,
        )
        // Settle pass past lockout.
        FlickStickMode.evaluate(
            reading = reading(x = 1f, y = 0f, timestampMs = 110L),
            ctx = ctx(),
            digitalEmit = digitalEmit,
            mouse = mouse,
        )
        // CCW rotation from +π/2 toward +π/4 (stick = (0.707, -0.707)).
        val dxSlot = slot<Float>()
        every { mouse.addRelativeDelta(capture(dxSlot), 0f) } returns Unit
        FlickStickMode.evaluate(
            reading = reading(x = 0.707f, y = -0.707f, timestampMs = 120L),
            ctx = ctx(),
            digitalEmit = digitalEmit,
            mouse = mouse,
        )
        assertTrue("hold-phase CCW rotation should emit negative dx", dxSlot.captured < 0f)
    }

    @Test
    fun releaseToCenter_resetsState_nextPushReFlicks() {
        val dxSlot = slot<Float>()
        every {
            mouse.scheduleSmoothDelta(capture(dxSlot), 0f, any())
        } returns Unit
        // First push → flick to the right and transition to HOLDING.
        FlickStickMode.evaluate(
            reading = reading(x = 1f, y = 0f, timestampMs = 0L),
            ctx = ctx(),
            digitalEmit = digitalEmit,
            mouse = mouse,
        )
        // Release to center.
        FlickStickMode.evaluate(
            reading = reading(x = 0f, y = 0f, timestampMs = 200L),
            ctx = ctx(),
            digitalEmit = digitalEmit,
            mouse = mouse,
        )
        // Second push (leftward) — must re-flick to −π/2, not interpret
        // as a hold-phase rotation from +π/2.
        FlickStickMode.evaluate(
            reading = reading(x = -1f, y = 0f, timestampMs = 300L),
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
        // Both schedule playouts on first-event activation and must not
        // share state.
        val dxCalls = mutableListOf<Float>()
        every {
            mouse.scheduleSmoothDelta(any(), 0f, any())
        } answers {
            dxCalls += firstArg<Float>()
        }
        FlickStickMode.evaluate(
            reading = reading(source = InputSource.LEFT_JOYSTICK, x = 1f, y = 0f, timestampMs = 0L),
            ctx = ctx().copy(source = InputSource.LEFT_JOYSTICK),
            digitalEmit = digitalEmit,
            mouse = mouse,
        )
        FlickStickMode.evaluate(
            reading = reading(source = InputSource.RIGHT_JOYSTICK, x = -1f, y = 0f, timestampMs = 0L),
            ctx = ctx(),  // RIGHT_JOYSTICK
            digitalEmit = digitalEmit,
            mouse = mouse,
        )
        assertEquals("both sticks should schedule independent flick playouts", 2, dxCalls.size)
        assertTrue("left stick scheduled positive dx", dxCalls[0] > 0f)
        assertTrue("right stick scheduled negative dx", dxCalls[1] < 0f)
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
        every { mouse.scheduleSmoothDelta(any(), any(), any()) } returns Unit
        // Push stick right → flick scheduled, now in HOLDING.
        FlickStickMode.evaluate(
            reading = reading(x = 1f, y = 0f, timestampMs = 0L),
            ctx = ctx(),
            digitalEmit = digitalEmit,
            mouse = mouse,
        )
        FlickStickMode.resetState()
        // Without reset, this leftward stick on next event would be
        // interpreted as a hold-phase rotation from +π/2 (after lockout).
        // With reset, the state goes back to NEUTRAL → fresh flick fires
        // a scheduled playout with negative dx target.
        val dxSlot = slot<Float>()
        every {
            mouse.scheduleSmoothDelta(capture(dxSlot), 0f, any())
        } returns Unit
        FlickStickMode.evaluate(
            reading = reading(x = -1f, y = 0f, timestampMs = 100L),
            ctx = ctx(),
            digitalEmit = digitalEmit,
            mouse = mouse,
        )
        assertTrue("post-reset push must re-flick (negative dx)", dxSlot.captured < 0f)
    }
}
