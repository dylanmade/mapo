package com.mappo.service.input.modes

import android.os.SystemClock
import com.mappo.data.model.steam.InputSource
import com.mappo.service.input.AnalogEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Brick K — focused unit tests for [DpadMode.evaluate]'s analog path
 * (stick-as-dpad / "WASD from joystick"). Targets the 4-way / 8-way
 * quantization + per-direction transition emit logic in isolation; the
 * integration path (synthetic edge → activator engine) is covered by
 * `InputEvaluatorTest`'s analog path.
 *
 * **Note on terminology.** What Steam Input calls "Joystick Move" is true
 * analog XInput-axis passthrough (no synthetic dpad emit). What's tested
 * here is Steam's "Dpad mode applied to a joystick source" — same shape as
 * a physical dpad but driven by stick (x, y) instead of `KEYCODE_DPAD_*`.
 *
 * **Robolectric is required** because [DpadSettings.parse] uses
 * `org.json.JSONObject` — under the project's `isReturnDefaultValues=true`
 * unit-test config, the stub returns 0.0 for every key and the deadzones
 * would silently collapse to zero.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class DpadModeAnalogTest {

    private val emits = mutableListOf<Pair<String, Boolean>>()
    private val emit: (String, Boolean) -> Unit = { sub, isDown -> emits += sub to isDown }

    private fun ctx(
        priorN: Boolean = false,
        priorS: Boolean = false,
        priorE: Boolean = false,
        priorW: Boolean = false,
        settingsJson: String = DpadMode.defaultSettingsJson(),
    ) = ModeContext(
        source = InputSource.LEFT_JOYSTICK,
        settingsJson = settingsJson,
        priorLatched = mapOf(
            "dpad_up" to priorN,
            "dpad_down" to priorS,
            "dpad_right" to priorE,
            "dpad_left" to priorW,
        ),
        activeLayerIds = emptyList(),
    )

    private fun reading(x: Float, y: Float, source: InputSource = InputSource.LEFT_JOYSTICK) =
        AnalogEvent(
            source = source,
            x = x,
            y = y,
            timestampMs = SystemClock.uptimeMillis(),
        )

    // Explicit 4-way override — the runtime default is now 8-way (matches the spec +
    // settings dropdown), so the 4-way tests must opt into the layout rather than relying
    // on the default. Inherits the spec deadzone/outer-ring defaults via the absent keys.
    private val settings4Way = """{"dpad_layout":"4_way"}"""
    private val settings8Way =
        """{"inner_deadzone":0.20,"outer_deadzone":0.05,"dpad_layout":"8_way"}"""

    // ── 4-way layout ─────────────────────────────────────────────────────────

    @Test
    fun fourWay_pushNorth_emitsDpadNorthDown() {
        // Magnitude 0.6: above the default deadzone but below the outer-ring radius
        // (≈0.763) so only the direction emits. |y| > |x| → vertical; y < 0 → north.
        DpadMode.evaluate(reading(0f, -0.6f), ctx(settingsJson = settings4Way), emit, MouseEmitter.NOOP)
        assertEquals(listOf("dpad_up" to true), emits)
    }

    @Test
    fun fourWay_pushSouth_emitsDpadSouthDown() {
        DpadMode.evaluate(reading(0f, 0.6f), ctx(settingsJson = settings4Way), emit, MouseEmitter.NOOP)
        assertEquals(listOf("dpad_down" to true), emits)
    }

    @Test
    fun fourWay_pushEast_emitsDpadEastDown() {
        DpadMode.evaluate(reading(0.6f, 0f), ctx(settingsJson = settings4Way), emit, MouseEmitter.NOOP)
        assertEquals(listOf("dpad_right" to true), emits)
    }

    @Test
    fun fourWay_pushWest_emitsDpadWestDown() {
        DpadMode.evaluate(reading(-0.6f, 0f), ctx(settingsJson = settings4Way), emit, MouseEmitter.NOOP)
        assertEquals(listOf("dpad_left" to true), emits)
    }

    @Test
    fun fourWay_diagonalSnapsToDominantAxis_xWins() {
        // (0.7, -0.5): |x|=0.7 > |y|=0.5 → east only, no north.
        DpadMode.evaluate(reading(0.7f, -0.5f), ctx(settingsJson = settings4Way), emit, MouseEmitter.NOOP)
        assertEquals(listOf("dpad_right" to true), emits)
    }

    @Test
    fun fourWay_diagonalSnapsToDominantAxis_yWins() {
        // (0.25, -0.6): |y|=0.6 > |x|=0.25 → north only; mag below outer-ring radius.
        DpadMode.evaluate(reading(0.25f, -0.6f), ctx(settingsJson = settings4Way), emit, MouseEmitter.NOOP)
        assertEquals(listOf("dpad_up" to true), emits)
    }

    @Test
    fun fourWay_transitionAcrossDiagonal_emitsCleanUpDown() {
        // Was at north (|y| was dominant). Stick rotates to (0.6, -0.4):
        // |x|=0.6 > |y|=0.4 → switch to east. Single evaluate call should
        // emit UP on north + DOWN on east.
        DpadMode.evaluate(
            reading(0.6f, -0.4f),
            ctx(priorN = true, settingsJson = settings4Way),
            emit, MouseEmitter.NOOP,
        )
        assertTrue("Expected north UP, got $emits", "dpad_up" to false in emits)
        assertTrue("Expected east DOWN, got $emits", "dpad_right" to true in emits)
        assertEquals(2, emits.size)
    }

    @Test
    fun fourWay_sustainedDeflection_doesNotReFire() {
        // Already latched east, still deflected east (mag 0.5, below outer-ring
        // radius) — must not re-emit east DOWN.
        DpadMode.evaluate(
            reading(0.5f, 0f),
            ctx(priorE = true, settingsJson = settings4Way),
            emit, MouseEmitter.NOOP,
        )
        assertTrue("Expected no emission while sustained-latched, got $emits", emits.isEmpty())
    }

    // ── 8-way layout ─────────────────────────────────────────────────────────

    @Test
    fun eightWay_diagonalEmitsBothAxes() {
        // NE push at (0.7, -0.7): axial threshold ≈ 0.141, both axes clear.
        DpadMode.evaluate(reading(0.7f, -0.7f), ctx(settingsJson = settings8Way), emit, MouseEmitter.NOOP)
        assertTrue("Expected dpad_north DOWN, got $emits", "dpad_up" to true in emits)
        assertTrue("Expected dpad_east DOWN, got $emits", "dpad_right" to true in emits)
        assertEquals(2, emits.size)
    }

    @Test
    fun eightWay_shallowDiagonal_emitsOnlyDominantAxis() {
        // (0.7, -0.05): |y|=0.05 < axial threshold (~0.141) → only east emits.
        DpadMode.evaluate(reading(0.7f, -0.05f), ctx(settingsJson = settings8Way), emit, MouseEmitter.NOOP)
        assertEquals(listOf("dpad_right" to true), emits)
    }

    @Test
    fun eightWay_releasingOneAxisOfDiagonal_emitsOnlyThatAxisUp() {
        // Was NE-latched. Stick rotates to (0.6, -0.05) — N now below axial
        // floor, E still above; mag below outer-ring radius. Only the N UP.
        DpadMode.evaluate(
            reading(0.6f, -0.05f),
            ctx(priorN = true, priorE = true, settingsJson = settings8Way),
            emit, MouseEmitter.NOOP,
        )
        assertEquals(listOf("dpad_up" to false), emits)
    }

    // ── Deadzone + hysteresis ────────────────────────────────────────────────

    @Test
    fun belowInnerDeadzone_emitsNothing() {
        // Magnitude ≈ 0.10, below default deadzone (10000/32767 ≈ 0.305).
        DpadMode.evaluate(reading(0.08f, -0.06f), ctx(), emit, MouseEmitter.NOOP)
        assertTrue("Expected no emission below deadzone, got $emits", emits.isEmpty())
    }

    @Test
    fun atRest_whenLatched_holdsInsideHysteresisBand() {
        // Was east-latched. Default inner ≈ 0.305, outer 0.05 → release floor ≈ 0.255.
        // Stick at magnitude 0.28 — inside the [0.255, 0.305] band → latch holds.
        // 4-way layout so the cardinal isn't gated by the 8-way axial threshold (this
        // exercises the deadzone hysteresis band, not diagonal overlap).
        DpadMode.evaluate(
            reading(0.28f, 0f),
            ctx(priorE = true, settingsJson = settings4Way),
            emit, MouseEmitter.NOOP,
        )
        assertTrue("Expected no emission inside hysteresis band, got $emits", emits.isEmpty())
    }

    @Test
    fun droppingBelowHysteresisFloor_releasesAllDirections() {
        // Was east-latched. Stick drops to magnitude 0.10 — below release
        // floor (0.15). Must emit east UP.
        DpadMode.evaluate(reading(0.10f, 0f), ctx(priorE = true), emit, MouseEmitter.NOOP)
        assertEquals(listOf("dpad_right" to false), emits)
    }

    @Test
    fun stickReturnsToCenter_releasesMultipleLatchedDirections() {
        // Was NE-latched (8-way). Stick returns to center. Both N and E unlatch.
        DpadMode.evaluate(
            reading(0f, 0f),
            ctx(priorN = true, priorE = true, settingsJson = settings8Way),
            emit, MouseEmitter.NOOP,
        )
        assertTrue("Expected dpad_north UP, got $emits", "dpad_up" to false in emits)
        assertTrue("Expected dpad_east UP, got $emits", "dpad_right" to false in emits)
        assertEquals(2, emits.size)
    }

    // ── Source guard ─────────────────────────────────────────────────────────

    @Test
    fun dpadSource_analogPathSkipped() {
        // The DPAD source emits dpad_* sub-inputs via KEYCODE_DPAD_* through the
        // accessibility service. Analog path skips to avoid double-fire on
        // controllers that report both BTN_DPAD_* and ABS_HAT0*.
        DpadMode.evaluate(
            reading(0f, -1f, source = InputSource.DPAD),
            ctx(),
            emit, MouseEmitter.NOOP,
        )
        assertTrue("Expected no emission for DPAD source, got $emits", emits.isEmpty())
    }

    // ── Settings parse ───────────────────────────────────────────────────────

    @Test
    fun customInnerDeadzone_isHonored() {
        val custom = """{"inner_deadzone":0.50,"outer_deadzone":0.05,"dpad_layout":"4_way"}"""
        // Magnitude 0.40 — below custom 0.50 inner_deadzone.
        DpadMode.evaluate(reading(0.40f, 0f), ctx(settingsJson = custom), emit, MouseEmitter.NOOP)
        assertTrue("Expected no emission below custom deadzone, got $emits", emits.isEmpty())

        emits.clear()
        // Magnitude 0.60 — clears custom deadzone.
        DpadMode.evaluate(reading(0.60f, 0f), ctx(settingsJson = custom), emit, MouseEmitter.NOOP)
        assertEquals(listOf("dpad_right" to true), emits)
    }

    @Test
    fun missingKeys_fallBackToDefaults() {
        // Tolerant parse: a binding_group seeded with `{}` (e.g. pre-Brick-K
        // DpadMode default that only had "dpad_layout") should pick up Steam
        // defaults so direction emit still works.
        DpadMode.evaluate(reading(0f, -0.6f), ctx(settingsJson = "{}"), emit, MouseEmitter.NOOP)
        assertEquals(listOf("dpad_up" to true), emits)
    }

    @Test
    fun legacyDpadLayoutOnlyJson_stillWorks() {
        // The pre-Brick-K DpadMode default was `{"dpad_layout":"4_way"}` — no
        // deadzones. The parser must tolerate the older shape and apply Steam
        // defaults for the missing keys.
        val legacy = """{"dpad_layout":"4_way"}"""
        DpadMode.evaluate(reading(0f, -0.6f), ctx(settingsJson = legacy), emit, MouseEmitter.NOOP)
        assertEquals(listOf("dpad_up" to true), emits)
    }

    @Test
    fun malformedJson_fallsBackToDefaults() {
        val garbage = "{not valid json"
        DpadMode.evaluate(reading(0f, -0.6f), ctx(settingsJson = garbage), emit, MouseEmitter.NOOP)
        assertEquals(listOf("dpad_up" to true), emits)
    }

    @Test
    fun unknownLayoutValue_fallsBackToDefaultEightWay() {
        // Defensive: if someone hand-edits the JSON with a typo'd layout, we
        // should default rather than disable direction emit. The spec default
        // is 8-way, so a diagonal emits both axes.
        val typo = """{"inner_deadzone":0.20,"outer_deadzone":0.05,"dpad_layout":"6_way"}"""
        DpadMode.evaluate(reading(0.7f, -0.7f), ctx(settingsJson = typo), emit, MouseEmitter.NOOP)
        assertTrue("Expected dpad_up DOWN, got $emits", "dpad_up" to true in emits)
        assertTrue("Expected dpad_right DOWN, got $emits", "dpad_right" to true in emits)
        assertEquals(2, emits.size)
    }

    @Test
    fun defaultSettings_matchSteamShapedDefaults() {
        val parsed = DpadSettings.parse(DpadMode.defaultSettingsJson())
        // Deadzone default = 10000/32767 ≈ 0.305 (joystick DPad menu's spec default).
        assertEquals(10000f / 32767f, parsed.innerDeadzone, 1e-4f)
        assertEquals(0.05f, parsed.outerDeadzone, 1e-4f)
        assertEquals("8_way", parsed.dpadLayout)
        assertEquals(5.0f, parsed.tiltSensitivity, 1e-4f)
        assertEquals(4000f, parsed.overlapRegion, 1e-4f)
    }

    // ── GYRO source (angle-integrated, tilt-and-hold semantics) ─────────────

    @org.junit.Before
    fun resetDpadGyroState() {
        // DpadMode's gyro integrator is a singleton — each test starts from
        // a clean slate so the prior test's accumulated tilt doesn't bleed.
        DpadMode.resetState()
    }

    private fun gyroCtx(
        priorN: Boolean = false,
        priorS: Boolean = false,
        priorE: Boolean = false,
        priorW: Boolean = false,
        settingsJson: String = DpadMode.defaultSettingsJson(),
    ) = ModeContext(
        source = InputSource.GYRO,
        settingsJson = settingsJson,
        priorLatched = mapOf(
            "dpad_up" to priorN,
            "dpad_down" to priorS,
            "dpad_right" to priorE,
            "dpad_left" to priorW,
        ),
        activeLayerIds = emptyList(),
    )

    private fun gyroReading(rollRate: Float, pitchRate: Float, timestampMs: Long) =
        AnalogEvent(InputSource.GYRO, rollRate, pitchRate, timestampMs)

    @Test
    fun gyro_firstEvent_seedsTimestampWithoutEmit() {
        // First event after reset seeds the integrator baseline and emits no
        // direction — accumulated angle is 0 until the next event provides a
        // dt to integrate over.
        DpadMode.evaluate(gyroReading(2.0f, 0f, 1_000L), gyroCtx(), emit, MouseEmitter.NOOP)
        assertTrue("Expected no emission on first gyro event, got $emits", emits.isEmpty())
        val (angleX, angleY) = DpadMode.integratedGyroAngleFor(InputSource.GYRO)!!
        assertEquals(0f, angleX, EPSILON)
        assertEquals(0f, angleY, EPSILON)
    }

    @Test
    fun gyro_sustainedRoll_emitsHeldDpadRight() {
        // Roll right 1 rad/sec for 100 ms after the seed → 0.1 rad. Scaled by
        // tilt_sensitivity 5.0 → effX = 0.5. Magnitude clears the 0.20 inner
        // deadzone, dominant axis = X (Y is 0) → emit dpad_right DOWN.
        val ctx = gyroCtx()
        DpadMode.evaluate(gyroReading(1.0f, 0f, 0L), ctx, emit, MouseEmitter.NOOP) // seed
        DpadMode.evaluate(gyroReading(1.0f, 0f, 100L), ctx, emit, MouseEmitter.NOOP) // 0.1 rad → 0.5 mag
        assertEquals(listOf("dpad_right" to true), emits)
    }

    @Test
    fun gyro_smallTilt_belowDeadzone_emitsNothing() {
        // 0.1 rad/sec roll for 100 ms → 0.01 rad → effX = 0.05. Below 0.20
        // inner deadzone. No direction emit.
        val ctx = gyroCtx()
        DpadMode.evaluate(gyroReading(0.1f, 0f, 0L), ctx, emit, MouseEmitter.NOOP) // seed
        DpadMode.evaluate(gyroReading(0.1f, 0f, 100L), ctx, emit, MouseEmitter.NOOP)
        assertTrue("Expected no emission for small accumulated tilt, got $emits", emits.isEmpty())
    }

    @Test
    fun gyro_pitchForward_emitsDpadUp() {
        // y > 0 is screen-down. Negative pitch (forward tilt) integrates to
        // negative effY → emits dpad_up. Mirrors the WASD-natural sign
        // convention used by the joystick path.
        val ctx = gyroCtx()
        DpadMode.evaluate(gyroReading(0f, -1.0f, 0L), ctx, emit, MouseEmitter.NOOP) // seed
        DpadMode.evaluate(gyroReading(0f, -1.0f, 100L), ctx, emit, MouseEmitter.NOOP)
        assertEquals(listOf("dpad_up" to true), emits)
    }

    @Test
    fun gyro_resetState_clearsAccumulatedAngle() {
        val ctx = gyroCtx()
        DpadMode.evaluate(gyroReading(1.0f, 0f, 0L), ctx, emit, MouseEmitter.NOOP)
        DpadMode.evaluate(gyroReading(1.0f, 0f, 100L), ctx, emit, MouseEmitter.NOOP)
        assertTrue("expected integrated state after sustained tilt",
            DpadMode.integratedGyroAngleFor(InputSource.GYRO) != null)
        DpadMode.resetState()
        assertTrue("expected null state after reset",
            DpadMode.integratedGyroAngleFor(InputSource.GYRO) == null)
    }

    @Test
    fun gyro_dtJump_isClamped() {
        // Long gap (10 sec) without sensor events — dt should clamp to 0.1 sec
        // so the first post-resume event doesn't dump multi-second integration.
        // 1 rad/sec × 0.1 sec = 0.1 rad → 0.5 mag → fires dpad_right.
        val ctx = gyroCtx()
        DpadMode.evaluate(gyroReading(1.0f, 0f, 0L), ctx, emit, MouseEmitter.NOOP) // seed
        DpadMode.evaluate(gyroReading(1.0f, 0f, 10_000L), ctx, emit, MouseEmitter.NOOP)
        // Should be exactly one east emit, not a runaway saturation cascade.
        assertEquals(listOf("dpad_right" to true), emits)
    }

    // ── Cross gate: diagonals allowed near the edge, cardinal-only near center ──

    private val settingsCrossGate =
        """{"inner_deadzone":0.20,"outer_deadzone":0.05,"dpad_layout":"cross_gate"}"""

    @Test
    fun crossGate_edgeDiagonal_emitsBothAxes() {
        // mag ≈ 0.99 ≥ 0.7 threshold → full 8-way diagonal.
        DpadMode.evaluate(reading(0.7f, -0.7f), ctx(settingsJson = settingsCrossGate), emit, MouseEmitter.NOOP)
        assertTrue("expected north, got $emits", "dpad_up" to true in emits)
        assertTrue("expected east, got $emits", "dpad_right" to true in emits)
        assertEquals(2, emits.size)
    }

    @Test
    fun crossGate_centerDiagonal_snapsToDominantCardinal() {
        // mag ≈ 0.46 < 0.7 threshold → diagonal suppressed; |x| > |y| → east only.
        DpadMode.evaluate(reading(0.35f, -0.30f), ctx(settingsJson = settingsCrossGate), emit, MouseEmitter.NOOP)
        assertEquals(listOf("dpad_right" to true), emits)
    }

    companion object {
        private const val EPSILON = 1e-4f
    }
}
