package com.mapo.service.shizuku

import android.content.Context
import com.mapo.data.model.steam.ActivatorType
import com.mapo.data.model.steam.BindingMode
import com.mapo.data.model.steam.BindingOutput
import com.mapo.data.model.steam.InputSource
import com.mapo.data.repository.ProfileRepository
import com.mapo.service.input.CompiledActionSet
import com.mapo.service.input.CompiledActivator
import com.mapo.service.input.CompiledConfig
import com.mapo.service.input.CompiledInput
import com.mapo.service.input.CompiledLayer
import com.mapo.service.input.InputAddress
import com.mapo.service.input.InputDispatcher
import com.mapo.service.input.InputEvaluator
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Predicate truth-table + degraded-mode transition tests. Targets the pure
 * helpers extracted from the [combine] lambda —
 * [ShizukuMotionCoordinator.evaluatePredicate] and
 * [ShizukuMotionCoordinator.shouldShowDegradedToast] — so tests don't have to
 * stage live coroutine flows.
 *
 * **Brick J follow-up**: predicate axes changed. Old `foregroundAppBound` clause
 * is gone (was a pre-Shizuku focused-overlay relic); new `remapEnabled` clause
 * gates everything on the master gamepad toggle. Axes now:
 *  - `remapEnabled` — user's gamepad toggle.
 *  - `analogModeConfigured` — active set + layers have any analog mode.
 *  - `shizukuReady` — Shizuku is granted + binder alive.
 */
class ShizukuMotionCoordinatorTest {

    private lateinit var coordinator: ShizukuMotionCoordinator

    @Before
    fun setUp() {
        coordinator = ShizukuMotionCoordinator(
            appContext = mockk<Context>(relaxed = true),
            profileRepository = mockk<ProfileRepository>(relaxed = true),
            inputDispatcher = mockk<InputDispatcher>(relaxed = true),
            inputEvaluator = mockk<InputEvaluator>(relaxed = true),
            shizukuConnection = mockk<ShizukuConnection>(relaxed = true),
            applicationScope = mockk<CoroutineScope>(relaxed = true),
        )
    }

    // ── Predicate truth table ────────────────────────────────────────────────

    @Test
    fun remapDisabled_disables() {
        val b = coordinator.evaluatePredicate(
            compiled = analogConfig(),
            activeSetId = 1L,
            activeLayers = emptyList(),
            remapEnabled = false,
            shizukuReady = true,
        )
        assertFalse(b.shouldEnable)
        assertFalse(b.remapEnabled)
    }

    @Test
    fun allDigitalModes_disables() {
        val b = coordinator.evaluatePredicate(
            compiled = digitalConfig(),
            activeSetId = 1L,
            activeLayers = emptyList(),
            remapEnabled = true,
            shizukuReady = true,
        )
        assertFalse(b.shouldEnable)
        assertTrue(b.remapEnabled)
        assertFalse(b.analogModeConfigured)
    }

    @Test
    fun digitalModeWithStickOutput_marksAnyShizukuConfigured() {
        val b = coordinator.evaluatePredicate(
            compiled = digitalModeWithShizukuOutputConfig(),
            activeSetId = 1L,
            activeLayers = emptyList(),
            remapEnabled = true,
            shizukuReady = false,
        )
        // The mode is digital (not analog), but a binding emits an analog stick
        // direction, which needs the virtual gamepad → the Shizuku-wanted signal
        // must fire so the banner / drawer notification surface the gap.
        assertFalse(b.analogModeConfigured)
        assertTrue(b.anyShizukuModeConfigured)
    }

    @Test
    fun analogMode_remapEnabled_shizukuReady_enables() {
        val b = coordinator.evaluatePredicate(
            compiled = analogConfig(),
            activeSetId = 1L,
            activeLayers = emptyList(),
            remapEnabled = true,
            shizukuReady = true,
        )
        assertTrue(b.shouldEnable)
        assertTrue(b.remapEnabled)
        assertTrue(b.analogModeConfigured)
        assertTrue(b.shizukuReady)
    }

    @Test
    fun analogMode_butShizukuNotReady_disables() {
        val b = coordinator.evaluatePredicate(
            compiled = analogConfig(),
            activeSetId = 1L,
            activeLayers = emptyList(),
            remapEnabled = true,
            shizukuReady = false,
        )
        assertFalse(b.shouldEnable)
        assertTrue(b.remapEnabled)
        assertTrue(b.analogModeConfigured)
        assertFalse(b.shizukuReady)
    }

    @Test
    fun digitalBase_activeLayerHasAnalog_enables() {
        val b = coordinator.evaluatePredicate(
            compiled = digitalBase_analogLayerConfig(layerId = 99L),
            activeSetId = 1L,
            activeLayers = listOf(99L),
            remapEnabled = true,
            shizukuReady = true,
        )
        assertTrue(b.shouldEnable)
    }

    @Test
    fun digitalBase_layerHasAnalogButNotActive_disables() {
        val b = coordinator.evaluatePredicate(
            compiled = digitalBase_analogLayerConfig(layerId = 99L),
            activeSetId = 1L,
            activeLayers = emptyList(),
            remapEnabled = true,
            shizukuReady = true,
        )
        assertFalse(b.shouldEnable)
    }

    @Test
    fun activeSetIdZero_fallsBackToStartingSet() {
        // 0L is the "lazy-uninit" sentinel — coordinator should resolve via
        // compiled.startingActionSetId, NOT treat the active set as missing.
        val b = coordinator.evaluatePredicate(
            compiled = analogConfig(startingSetId = 7L),
            activeSetId = 0L,
            activeLayers = emptyList(),
            remapEnabled = true,
            shizukuReady = true,
        )
        assertTrue(b.shouldEnable)
    }

    // ── Degraded-mode transition ─────────────────────────────────────────────

    @Test
    fun degradedToast_fires_whenShizukuFlips_andOtherClausesHold() {
        val prior = ShizukuMotionCoordinator.PredicateBreakdown(
            remapEnabled = true,
            analogModeConfigured = true,
            anyShizukuModeConfigured = true,
            shizukuReady = true,
        )
        val current = ShizukuMotionCoordinator.PredicateBreakdown(
            remapEnabled = true,
            analogModeConfigured = true,
            anyShizukuModeConfigured = true,
            shizukuReady = false,
        )
        assertTrue(coordinator.shouldShowDegradedToast(prior, current))
    }

    @Test
    fun degradedToast_silent_whenUserToggledRemapOff() {
        // User intentionally flipped the gamepad toggle — silent.
        val prior = ShizukuMotionCoordinator.PredicateBreakdown(
            remapEnabled = true,
            analogModeConfigured = true,
            anyShizukuModeConfigured = true,
            shizukuReady = true,
        )
        val current = ShizukuMotionCoordinator.PredicateBreakdown(
            remapEnabled = false,
            analogModeConfigured = true,
            anyShizukuModeConfigured = true,
            shizukuReady = true,
        )
        assertFalse(coordinator.shouldShowDegradedToast(prior, current))
    }

    @Test
    fun degradedToast_silent_onFirstEmission() {
        val current = ShizukuMotionCoordinator.PredicateBreakdown(
            remapEnabled = true,
            analogModeConfigured = true,
            anyShizukuModeConfigured = true,
            shizukuReady = false,
        )
        assertFalse(coordinator.shouldShowDegradedToast(prior = null, current = current))
    }

    @Test
    fun degradedToast_silent_whenWeWereNeverEnabled() {
        // Prior had Shizuku ready but no analog mode configured (so not enabled).
        val prior = ShizukuMotionCoordinator.PredicateBreakdown(
            remapEnabled = true,
            analogModeConfigured = false,
            anyShizukuModeConfigured = false,
            shizukuReady = true,
        )
        val current = ShizukuMotionCoordinator.PredicateBreakdown(
            remapEnabled = true,
            analogModeConfigured = false,
            anyShizukuModeConfigured = false,
            shizukuReady = false,
        )
        assertFalse(coordinator.shouldShowDegradedToast(prior, current))
    }

    @Test
    fun predicate_shouldEnable_isTrueOnlyWhenAllThreeAxesHold() {
        // shouldEnable conjoins remapEnabled + analogModeConfigured + shizukuReady.
        // anyShizukuModeConfigured is independent of shouldEnable (it drives the
        // UI warning surfaces, not the inject gate), so its value is held at
        // analogModeConfigured here for fixture readability — gyro-specific
        // truth tests live separately.
        val all = ShizukuMotionCoordinator.PredicateBreakdown(
            remapEnabled = true, analogModeConfigured = true,
            anyShizukuModeConfigured = true, shizukuReady = true,
        )
        assertEquals(true, all.shouldEnable)
        listOf(
            ShizukuMotionCoordinator.PredicateBreakdown(
                remapEnabled = false, analogModeConfigured = true,
                anyShizukuModeConfigured = true, shizukuReady = true,
            ),
            ShizukuMotionCoordinator.PredicateBreakdown(
                remapEnabled = true, analogModeConfigured = false,
                anyShizukuModeConfigured = false, shizukuReady = true,
            ),
            ShizukuMotionCoordinator.PredicateBreakdown(
                remapEnabled = true, analogModeConfigured = true,
                anyShizukuModeConfigured = true, shizukuReady = false,
            ),
        ).forEach { assertFalse(it.shouldEnable) }
    }

    // ── Brick C.5: NONE-mode analog silence ──────────────────────────────────

    @Test
    fun noneOnAnalogSource_makesShouldEnableTrue_evenWithoutOtherAnalogModes() {
        // Single NONE-mode entry on LEFT_JOYSTICK. No JoyMove / MouseJoy / etc.
        // anywhere else. shouldEnable should still be true so the coordinator
        // takes EVIOCGRAB and the source is actually silenced.
        val b = coordinator.evaluatePredicate(
            compiled = configWithNoneOnSources(1L, setOf(InputSource.LEFT_JOYSTICK)),
            activeSetId = 1L,
            activeLayers = emptyList(),
            remapEnabled = true,
            shizukuReady = true,
        )
        assertTrue(b.shouldEnable)
        assertTrue(b.noneOnAnalogSourceConfigured)
        assertFalse(b.analogModeConfigured)
    }

    @Test
    fun noneOnAnalogSource_butShizukuNotReady_disables() {
        val b = coordinator.evaluatePredicate(
            compiled = configWithNoneOnSources(1L, setOf(InputSource.RIGHT_JOYSTICK)),
            activeSetId = 1L,
            activeLayers = emptyList(),
            remapEnabled = true,
            shizukuReady = false,
        )
        assertFalse(b.shouldEnable)
        assertTrue(b.noneOnAnalogSourceConfigured)
    }

    @Test
    fun noneOnDigitalSource_doesNotTriggerGrabClause() {
        // SWITCH_START + BUTTON_DIAMOND face buttons silence via
        // InputEvaluator.handleDigital returning true — no EVIOCGRAB needed.
        // The grab predicate must NOT fire on NONE-mode digital sources.
        val b = coordinator.evaluatePredicate(
            compiled = configWithNoneOnSources(1L, setOf(InputSource.SWITCH_START, InputSource.BUTTON_DIAMOND)),
            activeSetId = 1L,
            activeLayers = emptyList(),
            remapEnabled = true,
            shizukuReady = true,
        )
        assertFalse(b.noneOnAnalogSourceConfigured)
        assertFalse(b.shouldEnable)
    }

    @Test
    fun noneOnTrigger_triggersGrabClause() {
        val b = coordinator.evaluatePredicate(
            compiled = configWithNoneOnSources(1L, setOf(InputSource.LEFT_TRIGGER)),
            activeSetId = 1L,
            activeLayers = emptyList(),
            remapEnabled = true,
            shizukuReady = true,
        )
        assertTrue(b.noneOnAnalogSourceConfigured)
        assertTrue(b.shouldEnable)
    }

    @Test
    fun noneOnDpad_triggersGrabClause() {
        // Corrected 2026-06-09: Mapo's target hardware reports the physical D-Pad as an
        // ABS_HAT0 hat axis — NOT KEYCODE_DPAD_* — so it never reaches onKeyEvent and is
        // captured ONLY via the Shizuku raw reader while grabbed. NONE-on-DPAD therefore
        // depends on EVIOCGRAB to silence (the hat reading is zeroed under grab), exactly
        // like NONE on a real stick/trigger. So it MUST trip the grab clause.
        val b = coordinator.evaluatePredicate(
            compiled = configWithNoneOnSources(1L, setOf(InputSource.DPAD)),
            activeSetId = 1L,
            activeLayers = emptyList(),
            remapEnabled = true,
            shizukuReady = true,
        )
        assertTrue(b.noneOnAnalogSourceConfigured)
        assertTrue(b.shouldEnable)
    }

    @Test
    fun dpadInNonDefaultMode_triggersGrabClause() {
        // Corrected 2026-06-09: a non-Device-Default mode on the physical D-Pad (here
        // DPAD mode) MUST force a grab. The D-Pad is HAT-only on Mapo's targets, so the
        // grabbed hat is the sole capture path — without the grab the hat readings never
        // arrive and every D-Pad mode goes dead. InputEvaluator bridges the grabbed hat
        // into the digital dpad_* edge pipeline.
        val b = coordinator.evaluatePredicate(
            compiled = configWithNoneOnSourcesAndExtraInput(
                startingSetId = 1L,
                noneSources = emptySet(),
                extraSource = InputSource.DPAD,
                extraMode = BindingMode.DPAD,
            ),
            activeSetId = 1L,
            activeLayers = emptyList(),
            remapEnabled = true,
            shizukuReady = true,
        )
        assertTrue(b.analogSourceHasNonDefaultMode)
        assertTrue(b.shouldEnable)
    }

    @Test
    fun noneOnAnalog_andGyroStickMode_bothClausesTrue() {
        // Combined config: LJ in NONE + GYRO in JOYSTICK_DEFLECTION. Both
        // grab clauses true, single grab transition.
        val cfg = configWithNoneOnSourcesAndExtraInput(
            startingSetId = 1L,
            noneSources = setOf(InputSource.LEFT_JOYSTICK),
            extraSource = InputSource.GYRO,
            extraMode = BindingMode.GYRO_TO_JOYSTICK_DEFLECTION,
        )
        val b = coordinator.evaluatePredicate(
            compiled = cfg,
            activeSetId = 1L,
            activeLayers = emptyList(),
            remapEnabled = true,
            shizukuReady = true,
        )
        assertTrue(b.noneOnAnalogSourceConfigured)
        assertTrue(b.gyroStickModeConfigured)
        assertTrue(b.shouldEnable)
    }

    @Test
    fun degradedToast_fires_whenShizukuFlips_onNoneOnlyConfig() {
        // User has only NONE on LJ. Shizuku was ready, now it's not. The
        // silenced source is about to leak through — the toast should fire
        // even though analogModeConfigured stays false throughout.
        val prior = ShizukuMotionCoordinator.PredicateBreakdown(
            remapEnabled = true,
            analogModeConfigured = false,
            anyShizukuModeConfigured = false,
            noneOnAnalogSourceConfigured = true,
            analogSourceHasNonDefaultMode = true,
            shizukuReady = true,
        )
        val current = ShizukuMotionCoordinator.PredicateBreakdown(
            remapEnabled = true,
            analogModeConfigured = false,
            anyShizukuModeConfigured = false,
            noneOnAnalogSourceConfigured = true,
            analogSourceHasNonDefaultMode = true,
            shizukuReady = false,
        )
        assertTrue(coordinator.shouldShowDegradedToast(prior, current))
    }

    @Test
    fun anyShizukuModeConfigured_isSupersetOfAnalogModeConfigured_inGyroOnlyScope() {
        // Gyro-to-mouse mode alone shouldn't flip analogModeConfigured (no
        // /dev/input motion capture needed), but should flip
        // anyShizukuModeConfigured (uinput mouse output needs Shizuku).
        val cfg = configWithSourceMode(InputSource.GYRO, BindingMode.GYRO_TO_MOUSE)
        val breakdown = coordinator.evaluatePredicate(
            compiled = cfg,
            activeSetId = 1L,
            activeLayers = emptyList(),
            remapEnabled = true,
            shizukuReady = false,
        )
        assertFalse(breakdown.analogModeConfigured)
        assertTrue(breakdown.anyShizukuModeConfigured)
    }

    private fun configWithSourceMode(source: InputSource, mode: BindingMode): CompiledConfig {
        val address = InputAddress(source, "")
        val input = CompiledInput(groupInputId = 1L, activators = emptyList(), mode = mode)
        return CompiledConfig(
            startingActionSetId = 1L,
            sets = mapOf(
                1L to CompiledActionSet(actionSetId = 1L, inputs = mapOf(address to input)),
            ),
        )
    }

    // ── Fixtures ─────────────────────────────────────────────────────────────

    /** Config with one set whose LEFT_JOYSTICK is in JOYSTICK_MOVE (analog) mode. */
    private fun analogConfig(startingSetId: Long = 1L): CompiledConfig {
        val set = CompiledActionSet(
            actionSetId = startingSetId,
            inputs = mapOf(
                InputAddress(InputSource.LEFT_JOYSTICK, "click") to CompiledInput(
                    groupInputId = 10L,
                    activators = emptyList(),
                    mode = BindingMode.JOYSTICK_MOVE,
                ),
            ),
        )
        return CompiledConfig(startingActionSetId = startingSetId, sets = mapOf(startingSetId to set))
    }

    /** Digital mode (BUTTON_PAD) but a binding outputs an analog stick direction. */
    private fun digitalModeWithShizukuOutputConfig(): CompiledConfig {
        val input = CompiledInput(
            groupInputId = 20L,
            activators = listOf(
                CompiledActivator(
                    activatorId = 1L,
                    type = ActivatorType.FULL_PRESS,
                    bindings = listOf(BindingOutput.XInputStick("LEFT", "UP")),
                ),
            ),
            mode = BindingMode.BUTTON_PAD,
        )
        val set = CompiledActionSet(
            actionSetId = 1L,
            inputs = mapOf(InputAddress(InputSource.BUTTON_DIAMOND, "button_a") to input),
        )
        return CompiledConfig(startingActionSetId = 1L, sets = mapOf(1L to set))
    }

    /** Config with all-digital modes only. */
    private fun digitalConfig(): CompiledConfig {
        val set = CompiledActionSet(
            actionSetId = 1L,
            inputs = mapOf(
                InputAddress(InputSource.LEFT_BUMPER, "click") to CompiledInput(
                    groupInputId = 10L,
                    activators = emptyList(),
                    mode = BindingMode.SINGLE_BUTTON,
                ),
                InputAddress(InputSource.BUTTON_DIAMOND, "button_a") to CompiledInput(
                    groupInputId = 11L,
                    activators = emptyList(),
                    mode = BindingMode.BUTTON_PAD,
                ),
            ),
        )
        return CompiledConfig(startingActionSetId = 1L, sets = mapOf(1L to set))
    }

    /**
     * Config whose base set has `noneModeSources` populated with the given
     * sources. No bindable `inputs` entries (NONE-mode sources don't generate
     * them, per the compile path).
     */
    private fun configWithNoneOnSources(
        startingSetId: Long,
        noneSources: Set<InputSource>,
    ): CompiledConfig {
        val set = CompiledActionSet(
            actionSetId = startingSetId,
            inputs = emptyMap(),
            noneModeSources = noneSources,
        )
        return CompiledConfig(startingActionSetId = startingSetId, sets = mapOf(startingSetId to set))
    }

    /**
     * Combined fixture: base set with [noneSources] AND an extra mode entry
     * on [extraSource]. Used to verify the grab predicate's OR semantics
     * (any one clause is enough).
     */
    private fun configWithNoneOnSourcesAndExtraInput(
        startingSetId: Long,
        noneSources: Set<InputSource>,
        extraSource: InputSource,
        extraMode: BindingMode,
    ): CompiledConfig {
        val set = CompiledActionSet(
            actionSetId = startingSetId,
            inputs = mapOf(
                InputAddress(extraSource, "") to CompiledInput(
                    groupInputId = 1L,
                    activators = emptyList(),
                    mode = extraMode,
                ),
            ),
            noneModeSources = noneSources,
        )
        return CompiledConfig(startingActionSetId = startingSetId, sets = mapOf(startingSetId to set))
    }

    /** Digital base set + a layer overlay declaring an analog (MOUSE_JOYSTICK) mode. */
    private fun digitalBase_analogLayerConfig(layerId: Long): CompiledConfig {
        val layer = CompiledLayer(
            layerId = layerId,
            inputs = mapOf(
                InputAddress(InputSource.RIGHT_JOYSTICK, "click") to CompiledInput(
                    groupInputId = 20L,
                    activators = emptyList(),
                    mode = BindingMode.JOYSTICK_MOUSE,
                ),
            ),
        )
        val set = CompiledActionSet(
            actionSetId = 1L,
            inputs = mapOf(
                InputAddress(InputSource.BUTTON_DIAMOND, "button_a") to CompiledInput(
                    groupInputId = 11L,
                    activators = emptyList(),
                    mode = BindingMode.BUTTON_PAD,
                ),
            ),
            layers = mapOf(layerId to layer),
        )
        return CompiledConfig(startingActionSetId = 1L, sets = mapOf(1L to set))
    }
}
