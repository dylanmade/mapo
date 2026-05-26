package com.mapo.service.input.modes

import android.os.SystemClock
import com.mapo.data.model.steam.InputSource
import com.mapo.service.input.AnalogEvent
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
            "dpad_north" to priorN,
            "dpad_south" to priorS,
            "dpad_east" to priorE,
            "dpad_west" to priorW,
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

    private val settings4Way = DpadMode.defaultSettingsJson()
    private val settings8Way =
        """{"inner_deadzone":0.20,"outer_deadzone":0.05,"dpad_layout":"8_way"}"""

    // ── 4-way layout ─────────────────────────────────────────────────────────

    @Test
    fun fourWay_pushNorth_emitsDpadNorthDown() {
        // Stick fully up: y = -1 (Android +y = down). |y| > |x| → vertical;
        // y < 0 → north.
        DpadMode.evaluate(reading(0f, -1f), ctx(), emit, MouseEmitter.NOOP)
        assertEquals(listOf("dpad_north" to true), emits)
    }

    @Test
    fun fourWay_pushSouth_emitsDpadSouthDown() {
        DpadMode.evaluate(reading(0f, 1f), ctx(), emit, MouseEmitter.NOOP)
        assertEquals(listOf("dpad_south" to true), emits)
    }

    @Test
    fun fourWay_pushEast_emitsDpadEastDown() {
        DpadMode.evaluate(reading(1f, 0f), ctx(), emit, MouseEmitter.NOOP)
        assertEquals(listOf("dpad_east" to true), emits)
    }

    @Test
    fun fourWay_pushWest_emitsDpadWestDown() {
        DpadMode.evaluate(reading(-1f, 0f), ctx(), emit, MouseEmitter.NOOP)
        assertEquals(listOf("dpad_west" to true), emits)
    }

    @Test
    fun fourWay_diagonalSnapsToDominantAxis_xWins() {
        // (0.7, -0.5): |x|=0.7 > |y|=0.5 → east only, no north.
        DpadMode.evaluate(reading(0.7f, -0.5f), ctx(), emit, MouseEmitter.NOOP)
        assertEquals(listOf("dpad_east" to true), emits)
    }

    @Test
    fun fourWay_diagonalSnapsToDominantAxis_yWins() {
        // (0.3, -0.8): |y|=0.8 > |x|=0.3 → north only, no east.
        DpadMode.evaluate(reading(0.3f, -0.8f), ctx(), emit, MouseEmitter.NOOP)
        assertEquals(listOf("dpad_north" to true), emits)
    }

    @Test
    fun fourWay_transitionAcrossDiagonal_emitsCleanUpDown() {
        // Was at north (|y| was dominant). Stick rotates to (0.6, -0.4):
        // |x|=0.6 > |y|=0.4 → switch to east. Single evaluate call should
        // emit UP on north + DOWN on east.
        DpadMode.evaluate(reading(0.6f, -0.4f), ctx(priorN = true), emit, MouseEmitter.NOOP)
        assertTrue("Expected north UP, got $emits", "dpad_north" to false in emits)
        assertTrue("Expected east DOWN, got $emits", "dpad_east" to true in emits)
        assertEquals(2, emits.size)
    }

    @Test
    fun fourWay_sustainedDeflection_doesNotReFire() {
        // Already latched east, still deflected east — must not re-emit east DOWN.
        DpadMode.evaluate(reading(0.9f, 0f), ctx(priorE = true), emit, MouseEmitter.NOOP)
        assertTrue("Expected no emission while sustained-latched, got $emits", emits.isEmpty())
    }

    // ── 8-way layout ─────────────────────────────────────────────────────────

    @Test
    fun eightWay_diagonalEmitsBothAxes() {
        // NE push at (0.7, -0.7): axial threshold ≈ 0.141, both axes clear.
        DpadMode.evaluate(reading(0.7f, -0.7f), ctx(settingsJson = settings8Way), emit, MouseEmitter.NOOP)
        assertTrue("Expected dpad_north DOWN, got $emits", "dpad_north" to true in emits)
        assertTrue("Expected dpad_east DOWN, got $emits", "dpad_east" to true in emits)
        assertEquals(2, emits.size)
    }

    @Test
    fun eightWay_shallowDiagonal_emitsOnlyDominantAxis() {
        // (0.7, -0.05): |y|=0.05 < axial threshold (~0.141) → only east emits.
        DpadMode.evaluate(reading(0.7f, -0.05f), ctx(settingsJson = settings8Way), emit, MouseEmitter.NOOP)
        assertEquals(listOf("dpad_east" to true), emits)
    }

    @Test
    fun eightWay_releasingOneAxisOfDiagonal_emitsOnlyThatAxisUp() {
        // Was NE-latched. Stick rotates to (0.9, -0.05) — N now below axial
        // floor, E still well above. Should emit only the N UP.
        DpadMode.evaluate(
            reading(0.9f, -0.05f),
            ctx(priorN = true, priorE = true, settingsJson = settings8Way),
            emit, MouseEmitter.NOOP,
        )
        assertEquals(listOf("dpad_north" to false), emits)
    }

    // ── Deadzone + hysteresis ────────────────────────────────────────────────

    @Test
    fun belowInnerDeadzone_emitsNothing() {
        // Magnitude ≈ 0.10, below default inner_deadzone of 0.20.
        DpadMode.evaluate(reading(0.08f, -0.06f), ctx(), emit, MouseEmitter.NOOP)
        assertTrue("Expected no emission below deadzone, got $emits", emits.isEmpty())
    }

    @Test
    fun atRest_whenLatched_holdsInsideHysteresisBand() {
        // Was east-latched. Stick drops to magnitude 0.18 — below
        // inner_deadzone (0.20) but above release floor (0.20 - 0.05 = 0.15).
        // Latch should hold; no emit.
        DpadMode.evaluate(reading(0.18f, 0f), ctx(priorE = true), emit, MouseEmitter.NOOP)
        assertTrue("Expected no emission inside hysteresis band, got $emits", emits.isEmpty())
    }

    @Test
    fun droppingBelowHysteresisFloor_releasesAllDirections() {
        // Was east-latched. Stick drops to magnitude 0.10 — below release
        // floor (0.15). Must emit east UP.
        DpadMode.evaluate(reading(0.10f, 0f), ctx(priorE = true), emit, MouseEmitter.NOOP)
        assertEquals(listOf("dpad_east" to false), emits)
    }

    @Test
    fun stickReturnsToCenter_releasesMultipleLatchedDirections() {
        // Was NE-latched (8-way). Stick returns to center. Both N and E unlatch.
        DpadMode.evaluate(
            reading(0f, 0f),
            ctx(priorN = true, priorE = true, settingsJson = settings8Way),
            emit, MouseEmitter.NOOP,
        )
        assertTrue("Expected dpad_north UP, got $emits", "dpad_north" to false in emits)
        assertTrue("Expected dpad_east UP, got $emits", "dpad_east" to false in emits)
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
        assertEquals(listOf("dpad_east" to true), emits)
    }

    @Test
    fun missingKeys_fallBackToDefaults() {
        // Tolerant parse: a binding_group seeded with `{}` (e.g. pre-Brick-K
        // DpadMode default that only had "dpad_layout") should pick up Steam
        // defaults so direction emit still works.
        DpadMode.evaluate(reading(0f, -1f), ctx(settingsJson = "{}"), emit, MouseEmitter.NOOP)
        assertEquals(listOf("dpad_north" to true), emits)
    }

    @Test
    fun legacyDpadLayoutOnlyJson_stillWorks() {
        // The pre-Brick-K DpadMode default was `{"dpad_layout":"4_way"}` — no
        // deadzones. The parser must tolerate the older shape and apply Steam
        // defaults for the missing keys.
        val legacy = """{"dpad_layout":"4_way"}"""
        DpadMode.evaluate(reading(0f, -1f), ctx(settingsJson = legacy), emit, MouseEmitter.NOOP)
        assertEquals(listOf("dpad_north" to true), emits)
    }

    @Test
    fun malformedJson_fallsBackToDefaults() {
        val garbage = "{not valid json"
        DpadMode.evaluate(reading(0f, -1f), ctx(settingsJson = garbage), emit, MouseEmitter.NOOP)
        assertEquals(listOf("dpad_north" to true), emits)
    }

    @Test
    fun unknownLayoutValue_fallsBackToFourWay() {
        // Defensive: if someone hand-edits the JSON with a typo'd layout, we
        // should default rather than disable direction emit.
        val typo = """{"inner_deadzone":0.20,"outer_deadzone":0.05,"dpad_layout":"6_way"}"""
        // Diagonal (0.7, -0.7) in 4-way should pick vertical on tie → north.
        DpadMode.evaluate(reading(0.7f, -0.7f), ctx(settingsJson = typo), emit, MouseEmitter.NOOP)
        assertEquals(listOf("dpad_north" to true), emits)
    }

    @Test
    fun defaultSettings_matchSteamShapedDefaults() {
        val parsed = DpadSettings.parse(DpadMode.defaultSettingsJson())
        assertEquals(0.20f, parsed.innerDeadzone, 1e-4f)
        assertEquals(0.05f, parsed.outerDeadzone, 1e-4f)
        assertEquals("4_way", parsed.dpadLayout)
    }
}
