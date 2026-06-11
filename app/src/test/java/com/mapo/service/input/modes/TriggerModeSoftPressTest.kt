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
 * Brick 5 — focused unit tests for [TriggerMode.evaluate]. Targets the hysteresis
 * edge detection in isolation: stub out [InputEvaluator] entirely so a flutter
 * in the threshold neighborhood doesn't produce a flurry of synthetic edges in
 * the integration path.
 *
 * **Robolectric is required** here because [TriggerSettings.parse] uses
 * `org.json.JSONObject`, which under the project's `isReturnDefaultValues=true`
 * unit-test config returns 0.0 for every key — the stub would silently swap in
 * a zero threshold and every hysteresis assertion would flip. Robolectric
 * supplies the real Android `org.json` implementation.
 *
 * Coverage:
 *  - Crossing `soft_threshold` upward emits exactly one DOWN.
 *  - Sustained readings above threshold don't emit again (no re-fire).
 *  - Dropping into the hysteresis dead band (between `soft_threshold - soft_hysteresis`
 *    and `soft_threshold`) keeps the latch on; no UP emitted.
 *  - Dropping below `soft_threshold - soft_hysteresis` emits the UP.
 *  - Settings JSON parse is tolerant: missing keys fall back to Steam defaults.
 *  - Defaults match Steam Input (`soft_threshold=0.10`, `soft_hysteresis=0.05`).
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class TriggerModeSoftPressTest {

    /** Capture for `digitalEmit` invocations during evaluate(). */
    private val emits = mutableListOf<Pair<String, Boolean>>()
    private val emit: (String, Boolean) -> Unit = { sub, isDown -> emits += sub to isDown }

    private fun ctx(
        priorLatched: Boolean,
        settingsJson: String = TriggerMode.defaultSettingsJson(),
    ) = ModeContext(
        source = InputSource.LEFT_TRIGGER,
        settingsJson = settingsJson,
        priorLatched = mapOf(TriggerMode.SOFT_PULL_SUB_INPUT to priorLatched),
        activeLayerIds = emptyList(),
    )

    private fun reading(magnitude: Float) = AnalogEvent(
        source = InputSource.LEFT_TRIGGER,
        x = magnitude,
        y = 0f,
        timestampMs = SystemClock.uptimeMillis(),
    )

    @Test
    fun crossingThresholdUpward_emitsExactlyOneDown() {
        // Default soft_threshold = 0.10; reading at 0.15 crosses.
        TriggerMode.evaluate(reading(0.15f), ctx(priorLatched = false), emit, MouseEmitter.NOOP)
        assertEquals(listOf(TriggerMode.SOFT_PULL_SUB_INPUT to true), emits)
    }

    @Test
    fun stayingAboveThreshold_doesNotReFire() {
        // Already latched, magnitude still above threshold — must not emit again.
        TriggerMode.evaluate(reading(0.50f), ctx(priorLatched = true), emit, MouseEmitter.NOOP)
        assertTrue("Expected no emission while sustained-latched, got $emits", emits.isEmpty())
    }

    @Test
    fun belowThresholdButInsideHysteresisBand_keepsLatchOn() {
        // Latched, magnitude drops to 0.07 — between (0.10 - 0.05)=0.05 and 0.10.
        // Hysteresis says: stay latched until below 0.05. Must not emit.
        TriggerMode.evaluate(reading(0.07f), ctx(priorLatched = true), emit, MouseEmitter.NOOP)
        assertTrue("Expected no emission inside hysteresis band, got $emits", emits.isEmpty())
    }

    @Test
    fun droppingBelowHysteresisFloor_emitsExactlyOneUp() {
        // Latched, magnitude drops to 0.04 — below (0.10 - 0.05)=0.05. Unlatch.
        TriggerMode.evaluate(reading(0.04f), ctx(priorLatched = true), emit, MouseEmitter.NOOP)
        assertEquals(listOf(TriggerMode.SOFT_PULL_SUB_INPUT to false), emits)
    }

    @Test
    fun startingBelowThreshold_doesNotEmit() {
        // Magnitude has never crossed; resting trigger noise (0..0.10) must stay silent.
        TriggerMode.evaluate(reading(0.05f), ctx(priorLatched = false), emit, MouseEmitter.NOOP)
        TriggerMode.evaluate(reading(0.09f), ctx(priorLatched = false), emit, MouseEmitter.NOOP)
        assertTrue("Expected no emission below threshold, got $emits", emits.isEmpty())
    }

    @Test
    fun exactThreshold_latchesDown() {
        // The comparison is `magnitude >= soft_threshold`, so hitting 0.10 latches.
        TriggerMode.evaluate(reading(0.10f), ctx(priorLatched = false), emit, MouseEmitter.NOOP)
        assertEquals(listOf(TriggerMode.SOFT_PULL_SUB_INPUT to true), emits)
    }

    @Test
    fun customSoftThreshold_isHonored() {
        val settings = """{"soft_threshold":0.30,"soft_hysteresis":0.10}"""
        // 0.25 below custom threshold → no emit.
        TriggerMode.evaluate(reading(0.25f), ctx(priorLatched = false, settings), emit, MouseEmitter.NOOP)
        assertTrue("Below custom 0.30 must not fire, got $emits", emits.isEmpty())

        // 0.32 crosses custom threshold → DOWN.
        TriggerMode.evaluate(reading(0.32f), ctx(priorLatched = false, settings), emit, MouseEmitter.NOOP)
        assertEquals(listOf(TriggerMode.SOFT_PULL_SUB_INPUT to true), emits)
    }

    @Test
    fun triggerThresholdInSteamUnits_isConvertedToMagnitude() {
        // The settings UI stores the soft-pull point in Steam units (0..32767).
        // 16383 ≈ 0.50 magnitude.
        val settings = """{"trigger_threshold":16383}"""
        // 0.40 below the converted 0.50 threshold → no emit.
        TriggerMode.evaluate(reading(0.40f), ctx(priorLatched = false, settings), emit, MouseEmitter.NOOP)
        assertTrue("Below converted 0.50 must not fire, got $emits", emits.isEmpty())

        // 0.55 crosses → DOWN.
        TriggerMode.evaluate(reading(0.55f), ctx(priorLatched = false, settings), emit, MouseEmitter.NOOP)
        assertEquals(listOf(TriggerMode.SOFT_PULL_SUB_INPUT to true), emits)
    }

    @Test
    fun triggerThreshold_takesPrecedenceOverLegacySoftThreshold() {
        // When both keys exist, the Steam-units trigger_threshold wins.
        val settings = """{"trigger_threshold":3277,"soft_threshold":0.90}"""
        // 3277/32767 ≈ 0.10; 0.15 crosses that, but is well below the legacy 0.90.
        TriggerMode.evaluate(reading(0.15f), ctx(priorLatched = false, settings), emit, MouseEmitter.NOOP)
        assertEquals(listOf(TriggerMode.SOFT_PULL_SUB_INPUT to true), emits)
    }

    @Test
    fun missingSettingsKeys_fallBackToSteamDefaults() {
        // Brick 4 / Brick 5 install seeded `click_threshold` only — verify the parser
        // tolerates the older shape (no `soft_threshold` / `soft_hysteresis` keys)
        // and applies the Steam defaults so a profile created before this brick
        // still gets working soft-press out of the box.
        val legacy = """{"click_threshold":0.95}"""
        TriggerMode.evaluate(reading(0.15f), ctx(priorLatched = false, legacy), emit, MouseEmitter.NOOP)
        assertEquals(listOf(TriggerMode.SOFT_PULL_SUB_INPUT to true), emits)
    }

    @Test
    fun malformedSettingsJson_fallsBackToDefaults() {
        // A corrupt JSON blob should not crash the evaluator — defaults take over so
        // motion handling keeps running.
        val garbage = "{not valid json"
        TriggerMode.evaluate(reading(0.15f), ctx(priorLatched = false, garbage), emit, MouseEmitter.NOOP)
        assertEquals(listOf(TriggerMode.SOFT_PULL_SUB_INPUT to true), emits)
    }

    @Test
    fun hysteresisAtOrAboveThreshold_stillReleasesAtRest() {
        // Regression for the 2026-06-09 wedge: a settings JSON whose hysteresis is
        // >= the threshold (here threshold 0.10, hysteresis 0.20) would push the
        // release point to <= 0, so once latched the soft pull could NEVER clear —
        // one DOWN, never an UP, and every later pull suppressed. The release point
        // must be clamped strictly into (0, press) so a trigger returning to rest
        // always unlatches.
        val settings = """{"soft_threshold":0.10,"soft_hysteresis":0.20}"""
        // Cross upward → DOWN.
        TriggerMode.evaluate(reading(0.50f), ctx(priorLatched = false, settings), emit, MouseEmitter.NOOP)
        assertEquals(listOf(TriggerMode.SOFT_PULL_SUB_INPUT to true), emits)
        emits.clear()
        // Return to rest → must emit UP (would wedge-stick before the fix).
        TriggerMode.evaluate(reading(0.0f), ctx(priorLatched = true, settings), emit, MouseEmitter.NOOP)
        assertEquals(listOf(TriggerMode.SOFT_PULL_SUB_INPUT to false), emits)
    }

    @Test
    fun zeroThreshold_doesNotLatchAtRest() {
        // A settings JSON carrying trigger_threshold:0 (partially-seeded group) parses
        // to softThreshold 0.0. Without a press floor, `magnitude >= 0` is true at rest
        // and the soft pull latches the instant the mode runs. The press floor must keep
        // a resting (0.0) trigger un-latched.
        val settings = """{"trigger_threshold":0}"""
        TriggerMode.evaluate(reading(0.0f), ctx(priorLatched = false, settings), emit, MouseEmitter.NOOP)
        assertTrue("A resting trigger must not latch the soft pull, got $emits", emits.isEmpty())
    }

    @Test
    fun fullPull_synthesizedAtClickThreshold_notMidTravel() {
        // Steam fires Full Pull at the END of travel. Mapo synthesizes full_pull from
        // the analog axis at click_threshold (default 0.95), NOT the hardware digital
        // click (which trips at ~2% on AYN Thor). A mid-travel 0.50 pull must not fire
        // full_pull; a near-end 0.97 pull must.
        TriggerMode.evaluate(reading(0.50f), ctx(priorLatched = false), emit, MouseEmitter.NOOP)
        assertTrue(
            "full_pull must not fire mid-travel, got $emits",
            emits.none { it.first == TriggerMode.FULL_PULL_SUB_INPUT },
        )
        emits.clear()
        TriggerMode.evaluate(reading(0.97f), ctx(priorLatched = false), emit, MouseEmitter.NOOP)
        assertTrue(
            "full_pull should fire near end of travel, got $emits",
            emits.contains(TriggerMode.FULL_PULL_SUB_INPUT to true),
        )
    }

    @Test
    fun fullPull_releasesWhenTriggerBacksOff() {
        // Latched at full (priorLatched only carries soft here; drive full via a high
        // reading first), then back off below the click hysteresis band → full_pull UP.
        TriggerMode.evaluate(reading(0.98f), ctx(priorLatched = false), emit, MouseEmitter.NOOP)
        assertTrue(emits.contains(TriggerMode.FULL_PULL_SUB_INPUT to true))
        emits.clear()
        // priorLatched for full_pull must be threaded back in for the release edge.
        val held = ModeContext(
            source = InputSource.LEFT_TRIGGER,
            settingsJson = TriggerMode.defaultSettingsJson(),
            priorLatched = mapOf(
                TriggerMode.SOFT_PULL_SUB_INPUT to true,
                TriggerMode.FULL_PULL_SUB_INPUT to true,
            ),
            activeLayerIds = emptyList(),
        )
        TriggerMode.evaluate(reading(0.50f), held, emit, MouseEmitter.NOOP)
        assertTrue(
            "full_pull should release when trigger backs off the end, got $emits",
            emits.contains(TriggerMode.FULL_PULL_SUB_INPUT to false),
        )
    }

    @Test
    fun defaultSettings_matchSteamDefaults() {
        // Pins the Steam-Input defaults so a later mode change doesn't silently
        // drift away from parity.
        val parsed = TriggerSettings.parse(TriggerMode.defaultSettingsJson())
        assertEquals(0.95f, parsed.clickThreshold, 1e-4f)
        assertEquals(0.10f, parsed.softThreshold, 1e-4f)
        assertEquals(0.05f, parsed.softHysteresis, 1e-4f)
    }

    @Test
    fun validInputs_includeFullPullAndSoftPull() {
        // Phase 7 Brick A: sub-inputs renamed to Steam-verbatim. The trigger has
        // "full_pull" (hardware threshold via KEYCODE_BUTTON_L2/R2) and "soft_pull"
        // (analog soft-pull via TriggerMode.evaluate's hysteresis). Both are real
        // bindable rows in the UI (L2/R2 Full Pull, L2/R2 Soft Pull) accepting any
        // activator type the user picks.
        assertEquals(
            setOf(TriggerMode.FULL_PULL_SUB_INPUT, TriggerMode.SOFT_PULL_SUB_INPUT),
            TriggerMode.validInputs(),
        )
        assertTrue(TriggerMode.accepts(TriggerMode.FULL_PULL_SUB_INPUT))
        assertTrue(TriggerMode.accepts(TriggerMode.SOFT_PULL_SUB_INPUT))
    }

    // ── Analog output (Trigger > Analog Output Trigger + range + curve) ──────────

    /** Records the last value written to each virtual trigger axis. */
    private class RecordingGamepad : GamepadEmitter {
        var left: Float? = null
        var right: Float? = null
        override fun setLeftTrigger(source: InputSource, v: Float) { left = v }
        override fun setRightTrigger(source: InputSource, v: Float) { right = v }
        override fun setLeftStick(source: InputSource, x: Float, y: Float) {}
        override fun setRightStick(source: InputSource, x: Float, y: Float) {}
        override fun setDpadHat(source: InputSource, x: Int, y: Int) {}
        override fun clearSource(source: InputSource) {}
        override fun setButton(btnCode: Int, pressed: Boolean): Boolean = false
        override fun setLeftStickOutput(x: Float, y: Float) {}
        override fun setRightStickOutput(x: Float, y: Float) {}
        override fun setLeftTriggerOutput(v: Float) {}
        override fun setRightTriggerOutput(v: Float) {}
        override fun setHatOutput(x: Int, y: Int) {}
        override fun clearOutputSticks() {}
    }

    private fun ctxGamepad(settingsJson: String, gamepad: GamepadEmitter, source: InputSource = InputSource.LEFT_TRIGGER) =
        ModeContext(
            source = source,
            settingsJson = settingsJson,
            priorLatched = emptyMap(),
            activeLayerIds = emptyList(),
            gamepad = gamepad,
        )

    @Test
    fun analogOutput_defaultsToMatchingTrigger_andPassesRangedValue() {
        // Absent analog_output_trigger → matching trigger (left source → left axis).
        // Default range start≈0.03 / end≈0.98, linear curve. A ~mid pull maps roughly 1:1.
        val gp = RecordingGamepad()
        TriggerMode.evaluate(reading(0.50f), ctxGamepad("{}", gp), emit, MouseEmitter.NOOP)
        assertTrue("left axis should get the analog value, got ${gp.left}", (gp.left ?: -1f) > 0.4f)
        // Only the target axis is written; the right axis is left untouched (= 0 contribution).
        assertEquals("right axis untouched for a left-trigger matching default", null, gp.right)
    }

    @Test
    fun analogOutput_off_writesZeroToBothAxes() {
        val gp = RecordingGamepad()
        TriggerMode.evaluate(reading(0.90f), ctxGamepad("""{"analog_output_trigger":"off"}""", gp), emit, MouseEmitter.NOOP)
        // Off emits nothing (contribution stays cleared by handleConfigChange).
        assertEquals(null, gp.left)
        assertEquals(null, gp.right)
    }

    @Test
    fun analogOutput_explicitRight_sendsToRightAxis() {
        val gp = RecordingGamepad()
        TriggerMode.evaluate(reading(0.90f), ctxGamepad("""{"analog_output_trigger":"right"}""", gp), emit, MouseEmitter.NOOP)
        assertTrue("right axis should get the value, got ${gp.right}", (gp.right ?: -1f) > 0.5f)
        assertEquals(null, gp.left)
    }

    @Test
    fun analogOutput_rangeStartDeadzone_belowStartIsZero() {
        // range start 16000/32767 ≈ 0.49 → a 0.30 pull is inside the start deadzone → 0.
        val gp = RecordingGamepad()
        val settings = """{"analog_output_trigger":"left","trigger_range_start":16000,"trigger_range_end":32000}"""
        TriggerMode.evaluate(reading(0.30f), ctxGamepad(settings, gp), emit, MouseEmitter.NOOP)
        assertEquals(0f, gp.left ?: -1f, 1e-4f)
    }

    @Test
    fun analogOutput_rangeEnd_pastEndIsMax() {
        val gp = RecordingGamepad()
        val settings = """{"analog_output_trigger":"left","trigger_range_start":1000,"trigger_range_end":16000}"""
        // 0.90 magnitude is well past 16000/32767 ≈ 0.49 → clamps to max 1.0.
        TriggerMode.evaluate(reading(0.90f), ctxGamepad(settings, gp), emit, MouseEmitter.NOOP)
        assertEquals(1f, gp.left ?: -1f, 1e-4f)
    }

    // ── Threshold trigger style ──────────────────────────────────────────────

    /** Run a sequence of (magnitude, timeMs) through evaluate, threading the latch state. */
    private fun runPull(style: String, steps: List<Pair<Float, Long>>): List<Pair<String, Boolean>> {
        TriggerMode.resetState()
        val out = mutableListOf<Pair<String, Boolean>>()
        var soft = false
        var full = false
        val cap: (String, Boolean) -> Unit = { sub, down ->
            out += sub to down
            if (sub == TriggerMode.SOFT_PULL_SUB_INPUT) soft = down
            if (sub == TriggerMode.FULL_PULL_SUB_INPUT) full = down
        }
        for ((mag, t) in steps) {
            val ctx = ModeContext(
                source = InputSource.LEFT_TRIGGER,
                settingsJson = """{"threshold_trigger_style":"$style"}""",
                priorLatched = mapOf(
                    TriggerMode.SOFT_PULL_SUB_INPUT to soft,
                    TriggerMode.FULL_PULL_SUB_INPUT to full,
                ),
                activeLayerIds = emptyList(),
            )
            TriggerMode.evaluate(
                AnalogEvent(source = InputSource.LEFT_TRIGGER, x = mag, y = 0f, timestampMs = t),
                ctx, cap, MouseEmitter.NOOP,
            )
        }
        return out
    }

    @Test
    fun exclusive_softFirst_locksOutFull() {
        // Cross soft → soft fires + claims the pull; reaching full is locked out.
        val edges = runPull("hip_fire_exclusive", listOf(0f to 0L, 0.50f to 10L, 0.97f to 20L, 0f to 30L))
        assertTrue("soft should fire", edges.contains(TriggerMode.SOFT_PULL_SUB_INPUT to true))
        assertFalse("full must be locked out", edges.any { it.first == TriggerMode.FULL_PULL_SUB_INPUT })
    }

    @Test
    fun exclusive_fullFirst_locksOutSoft() {
        // A single event past both thresholds → full claims the pull; soft is locked out.
        val edges = runPull("hip_fire_exclusive", listOf(0f to 0L, 0.97f to 10L, 0f to 20L))
        assertTrue("full should fire", edges.contains(TriggerMode.FULL_PULL_SUB_INPUT to true))
        assertFalse("soft must be locked out", edges.any { it.first == TriggerMode.SOFT_PULL_SUB_INPUT })
    }

    @Test
    fun hipFire_fastPull_skipsSoft() {
        // Soft crossed then full reached within the aggressive window (120ms) → soft skipped.
        val edges = runPull("hip_fire_aggressive", listOf(0f to 0L, 0.15f to 10L, 0.97f to 50L, 0f to 120L))
        assertTrue("full should fire", edges.contains(TriggerMode.FULL_PULL_SUB_INPUT to true))
        assertFalse("soft should be skipped on a fast pull", edges.any { it.first == TriggerMode.SOFT_PULL_SUB_INPUT })
    }

    @Test
    fun hipFire_slowPull_firesSoftThenFull() {
        // Soft held past the window before reaching full → soft fires first, then full.
        val edges = runPull("hip_fire_aggressive", listOf(0f to 0L, 0.15f to 10L, 0.20f to 200L, 0.97f to 300L, 0f to 400L))
        val softDown = edges.indexOf(TriggerMode.SOFT_PULL_SUB_INPUT to true)
        val fullDown = edges.indexOf(TriggerMode.FULL_PULL_SUB_INPUT to true)
        assertTrue("soft should fire on a slow pull, got $edges", softDown >= 0)
        assertTrue("full should fire, got $edges", fullDown >= 0)
        assertTrue("soft must fire before full, got $edges", softDown < fullDown)
    }
}
