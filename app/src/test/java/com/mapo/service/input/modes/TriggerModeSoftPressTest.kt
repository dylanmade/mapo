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
}
