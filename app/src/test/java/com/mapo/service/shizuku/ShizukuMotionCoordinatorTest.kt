package com.mapo.service.shizuku

import android.content.Context
import com.mapo.data.model.steam.BindingMode
import com.mapo.data.model.steam.InputSource
import com.mapo.data.repository.AppProfileBindingRepository
import com.mapo.data.repository.ProfileRepository
import com.mapo.service.foreground.ForegroundAppMonitor
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
 * Brick F: predicate truth-table + degraded-mode transition tests. Targets the
 * pure helpers extracted from the [combine] lambda — [ShizukuMotionCoordinator.evaluatePredicate]
 * and [ShizukuMotionCoordinator.shouldShowDegradedToast] — so tests don't have
 * to stage live coroutine flows.
 *
 * Cases covered:
 *  - No foreground package → predicate disables.
 *  - No active profile → predicate disables.
 *  - Foreground app NOT bound to active profile → predicate disables.
 *  - Foreground app bound but all-digital modes → predicate disables.
 *  - Foreground app bound, base set has analog mode, Shizuku ready → enables.
 *  - Same as above but Shizuku NOT ready → predicate disables (new Brick F
 *    third clause).
 *  - Foreground bound, digital base, active layer has analog mode → enables.
 *  - Layer has analog mode but layer not in active stack → disables.
 *  - `activeSetId == 0L` (lazy-uninit) falls back to startingActionSetId.
 *  - Degraded-mode transition (was enabled, Shizuku flipped not-ready, other
 *    clauses still hold) → true.
 *  - Non-degraded transitions (other clause flipped, no prior, etc.) → false.
 */
class ShizukuMotionCoordinatorTest {

    private lateinit var coordinator: ShizukuMotionCoordinator

    @Before
    fun setUp() {
        coordinator = ShizukuMotionCoordinator(
            appContext = mockk<Context>(relaxed = true),
            foregroundAppMonitor = mockk<ForegroundAppMonitor>(relaxed = true),
            profileRepository = mockk<ProfileRepository>(relaxed = true),
            appProfileBindingRepository = mockk<AppProfileBindingRepository>(relaxed = true),
            inputDispatcher = mockk<InputDispatcher>(relaxed = true),
            inputEvaluator = mockk<InputEvaluator>(relaxed = true),
            shizukuConnection = mockk<ShizukuConnection>(relaxed = true),
            applicationScope = mockk<CoroutineScope>(relaxed = true),
        )
    }

    // ── Predicate truth table ────────────────────────────────────────────────

    @Test
    fun nullForegroundPackage_disables() {
        val b = coordinator.evaluatePredicate(
            foregroundPkg = null,
            activeProfileId = 1L,
            bindings = setOf("com.example.game"),
            compiled = analogConfig(),
            activeSetId = 1L,
            activeLayers = emptyList(),
            shizukuReady = true,
        )
        assertFalse(b.shouldEnable)
        assertFalse(b.foregroundAppBound)
    }

    @Test
    fun nullActiveProfile_disables() {
        val b = coordinator.evaluatePredicate(
            foregroundPkg = "com.example.game",
            activeProfileId = null,
            bindings = setOf("com.example.game"),
            compiled = analogConfig(),
            activeSetId = 1L,
            activeLayers = emptyList(),
            shizukuReady = true,
        )
        assertFalse(b.shouldEnable)
        assertFalse(b.foregroundAppBound)
    }

    @Test
    fun unboundForegroundPackage_disables() {
        val b = coordinator.evaluatePredicate(
            foregroundPkg = "com.unrelated.app",
            activeProfileId = 1L,
            bindings = setOf("com.example.game"),
            compiled = analogConfig(),
            activeSetId = 1L,
            activeLayers = emptyList(),
            shizukuReady = true,
        )
        assertFalse(b.shouldEnable)
        assertFalse(b.foregroundAppBound)
    }

    @Test
    fun boundApp_butAllDigitalModes_disables() {
        val b = coordinator.evaluatePredicate(
            foregroundPkg = "com.example.game",
            activeProfileId = 1L,
            bindings = setOf("com.example.game"),
            compiled = digitalConfig(),
            activeSetId = 1L,
            activeLayers = emptyList(),
            shizukuReady = true,
        )
        assertFalse(b.shouldEnable)
        assertTrue(b.foregroundAppBound)
        assertFalse(b.analogModeInScope)
    }

    @Test
    fun boundApp_andAnalogMode_andShizukuReady_enables() {
        val b = coordinator.evaluatePredicate(
            foregroundPkg = "com.example.game",
            activeProfileId = 1L,
            bindings = setOf("com.example.game"),
            compiled = analogConfig(),
            activeSetId = 1L,
            activeLayers = emptyList(),
            shizukuReady = true,
        )
        assertTrue(b.shouldEnable)
        assertTrue(b.foregroundAppBound)
        assertTrue(b.analogModeInScope)
        assertTrue(b.shizukuReady)
    }

    @Test
    fun boundApp_andAnalogMode_butShizukuNotReady_disables() {
        // Brick F's new third clause. Cold-start "Shizuku not granted" case —
        // predicate keeps Shizuku enumeration off until the user grants
        // permission (Brick G's dialog territory).
        val b = coordinator.evaluatePredicate(
            foregroundPkg = "com.example.game",
            activeProfileId = 1L,
            bindings = setOf("com.example.game"),
            compiled = analogConfig(),
            activeSetId = 1L,
            activeLayers = emptyList(),
            shizukuReady = false,
        )
        assertFalse(b.shouldEnable)
        assertTrue(b.foregroundAppBound)
        assertTrue(b.analogModeInScope)
        assertFalse(b.shizukuReady)
    }

    @Test
    fun boundApp_digitalBase_activeLayerHasAnalog_enables() {
        val b = coordinator.evaluatePredicate(
            foregroundPkg = "com.example.game",
            activeProfileId = 1L,
            bindings = setOf("com.example.game"),
            compiled = digitalBase_analogLayerConfig(layerId = 99L),
            activeSetId = 1L,
            activeLayers = listOf(99L),
            shizukuReady = true,
        )
        assertTrue(b.shouldEnable)
    }

    @Test
    fun boundApp_digitalBase_layerHasAnalogButNotActive_disables() {
        val b = coordinator.evaluatePredicate(
            foregroundPkg = "com.example.game",
            activeProfileId = 1L,
            bindings = setOf("com.example.game"),
            compiled = digitalBase_analogLayerConfig(layerId = 99L),
            activeSetId = 1L,
            activeLayers = emptyList(),
            shizukuReady = true,
        )
        assertFalse(b.shouldEnable)
    }

    @Test
    fun activeSetIdZero_fallsBackToStartingSet() {
        // 0L is the "lazy-uninit" sentinel — coordinator should resolve via
        // compiled.startingActionSetId, NOT treat the active set as missing.
        val b = coordinator.evaluatePredicate(
            foregroundPkg = "com.example.game",
            activeProfileId = 1L,
            bindings = setOf("com.example.game"),
            compiled = analogConfig(startingSetId = 7L),
            activeSetId = 0L,
            activeLayers = emptyList(),
            shizukuReady = true,
        )
        assertTrue(b.shouldEnable)
    }

    // ── Degraded-mode transition ─────────────────────────────────────────────

    @Test
    fun degradedToast_fires_whenShizukuFlips_andOtherClausesHold() {
        val prior = ShizukuMotionCoordinator.PredicateBreakdown(
            foregroundAppBound = true,
            analogModeInScope = true,
            shizukuReady = true,
        )
        val current = ShizukuMotionCoordinator.PredicateBreakdown(
            foregroundAppBound = true,
            analogModeInScope = true,
            shizukuReady = false,
        )
        assertTrue(coordinator.shouldShowDegradedToast(prior, current))
    }

    @Test
    fun degradedToast_silent_whenForegroundAppChanged() {
        // User backgrounded the game — predicate disabled for benign reasons.
        // No toast (would be noise; the user did this deliberately).
        val prior = ShizukuMotionCoordinator.PredicateBreakdown(
            foregroundAppBound = true,
            analogModeInScope = true,
            shizukuReady = true,
        )
        val current = ShizukuMotionCoordinator.PredicateBreakdown(
            foregroundAppBound = false,
            analogModeInScope = false,
            shizukuReady = true,
        )
        assertFalse(coordinator.shouldShowDegradedToast(prior, current))
    }

    @Test
    fun degradedToast_silent_onFirstEmission() {
        // No prior → no transition → no toast. Avoids a false "Shizuku
        // disconnected" the moment the coordinator first observes a Shizuku-
        // not-ready cold start.
        val current = ShizukuMotionCoordinator.PredicateBreakdown(
            foregroundAppBound = true,
            analogModeInScope = true,
            shizukuReady = false,
        )
        assertFalse(coordinator.shouldShowDegradedToast(prior = null, current = current))
    }

    @Test
    fun degradedToast_silent_whenWeWereNeverEnabled() {
        // Prior had Shizuku ready but no analog mode in scope (so not enabled).
        // Shizuku flipping not-ready in this case is a non-event for the user.
        val prior = ShizukuMotionCoordinator.PredicateBreakdown(
            foregroundAppBound = true,
            analogModeInScope = false,
            shizukuReady = true,
        )
        val current = ShizukuMotionCoordinator.PredicateBreakdown(
            foregroundAppBound = true,
            analogModeInScope = false,
            shizukuReady = false,
        )
        assertFalse(coordinator.shouldShowDegradedToast(prior, current))
    }

    @Test
    fun predicate_shouldEnable_isTrueOnlyWhenAllThreeAxesHold() {
        // Spot-check the data class's derived property.
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
                    mode = BindingMode.MOUSE_JOYSTICK,
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
