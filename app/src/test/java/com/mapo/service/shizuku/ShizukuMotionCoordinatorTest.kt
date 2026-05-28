package com.mapo.service.shizuku

import android.content.Context
import com.mapo.data.model.steam.BindingMode
import com.mapo.data.model.steam.InputSource
import com.mapo.data.repository.ProfileRepository
import com.mapo.service.input.CompiledActionSet
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
            shizukuReady = true,
        )
        val current = ShizukuMotionCoordinator.PredicateBreakdown(
            remapEnabled = true,
            analogModeConfigured = true,
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
            shizukuReady = true,
        )
        val current = ShizukuMotionCoordinator.PredicateBreakdown(
            remapEnabled = false,
            analogModeConfigured = true,
            shizukuReady = true,
        )
        assertFalse(coordinator.shouldShowDegradedToast(prior, current))
    }

    @Test
    fun degradedToast_silent_onFirstEmission() {
        val current = ShizukuMotionCoordinator.PredicateBreakdown(
            remapEnabled = true,
            analogModeConfigured = true,
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
            shizukuReady = true,
        )
        val current = ShizukuMotionCoordinator.PredicateBreakdown(
            remapEnabled = true,
            analogModeConfigured = false,
            shizukuReady = false,
        )
        assertFalse(coordinator.shouldShowDegradedToast(prior, current))
    }

    @Test
    fun predicate_shouldEnable_isTrueOnlyWhenAllThreeAxesHold() {
        val all = ShizukuMotionCoordinator.PredicateBreakdown(true, true, true)
        assertEquals(true, all.shouldEnable)
        listOf(
            ShizukuMotionCoordinator.PredicateBreakdown(false, true, true),
            ShizukuMotionCoordinator.PredicateBreakdown(true, false, true),
            ShizukuMotionCoordinator.PredicateBreakdown(true, true, false),
        ).forEach { assertFalse(it.shouldEnable) }
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
