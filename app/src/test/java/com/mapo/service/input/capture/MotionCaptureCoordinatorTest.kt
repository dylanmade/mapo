package com.mapo.service.input.capture

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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Brick 4: predicate truth-table test. Targets the pure
 * [MotionCaptureCoordinator.evaluatePredicate] function, which is the entire
 * decision logic extracted out of the [combine] lambda for testability.
 *
 * Cases covered:
 *  - No foreground package → never attach.
 *  - No active profile → never attach.
 *  - Foreground app NOT bound to active profile → never attach.
 *  - Foreground app bound, but compiled config has no analog modes → no attach.
 *  - Foreground app bound, compiled config has an analog mode on the base
 *    set → attach.
 *  - Foreground app bound, base set has only digital modes BUT an active
 *    layer's overlay declares an analog mode → attach.
 *  - Foreground app bound, layer with analog mode exists but isn't active →
 *    no attach.
 *  - activeSetId == 0L (lazy-uninit) → falls back to startingActionSetId.
 */
class MotionCaptureCoordinatorTest {

    private lateinit var coordinator: MotionCaptureCoordinator

    @Before
    fun setUp() {
        coordinator = MotionCaptureCoordinator(
            foregroundAppMonitor = mockk<ForegroundAppMonitor>(relaxed = true),
            profileRepository = mockk<ProfileRepository>(relaxed = true),
            appProfileBindingRepository = mockk<AppProfileBindingRepository>(relaxed = true),
            inputDispatcher = mockk<InputDispatcher>(relaxed = true),
            inputEvaluator = mockk<InputEvaluator>(relaxed = true),
            overlayManager = mockk<MotionCaptureOverlayManager>(relaxed = true),
            applicationScope = mockk<CoroutineScope>(relaxed = true),
        )
    }

    @Test
    fun nullForegroundPackage_doesNotAttach() {
        assertFalse(
            coordinator.evaluatePredicate(
                foregroundPkg = null,
                activeProfileId = 1L,
                bindings = setOf("com.example.game"),
                compiled = analogConfig(),
                activeSetId = 1L,
                activeLayers = emptyList(),
            ),
        )
    }

    @Test
    fun nullActiveProfile_doesNotAttach() {
        assertFalse(
            coordinator.evaluatePredicate(
                foregroundPkg = "com.example.game",
                activeProfileId = null,
                bindings = setOf("com.example.game"),
                compiled = analogConfig(),
                activeSetId = 1L,
                activeLayers = emptyList(),
            ),
        )
    }

    @Test
    fun unboundForegroundPackage_doesNotAttach() {
        assertFalse(
            coordinator.evaluatePredicate(
                foregroundPkg = "com.unrelated.app",
                activeProfileId = 1L,
                bindings = setOf("com.example.game"),
                compiled = analogConfig(),
                activeSetId = 1L,
                activeLayers = emptyList(),
            ),
        )
    }

    @Test
    fun boundApp_butAllDigitalModes_doesNotAttach() {
        assertFalse(
            coordinator.evaluatePredicate(
                foregroundPkg = "com.example.game",
                activeProfileId = 1L,
                bindings = setOf("com.example.game"),
                compiled = digitalConfig(),
                activeSetId = 1L,
                activeLayers = emptyList(),
            ),
        )
    }

    @Test
    fun boundApp_andAnalogModeInBaseSet_attaches() {
        assertTrue(
            coordinator.evaluatePredicate(
                foregroundPkg = "com.example.game",
                activeProfileId = 1L,
                bindings = setOf("com.example.game"),
                compiled = analogConfig(),
                activeSetId = 1L,
                activeLayers = emptyList(),
            ),
        )
    }

    @Test
    fun boundApp_digitalBase_activeLayerHasAnalog_attaches() {
        assertTrue(
            coordinator.evaluatePredicate(
                foregroundPkg = "com.example.game",
                activeProfileId = 1L,
                bindings = setOf("com.example.game"),
                compiled = digitalBase_analogLayerConfig(layerId = 99L),
                activeSetId = 1L,
                activeLayers = listOf(99L),
            ),
        )
    }

    @Test
    fun boundApp_digitalBase_layerHasAnalogButNotActive_doesNotAttach() {
        assertFalse(
            coordinator.evaluatePredicate(
                foregroundPkg = "com.example.game",
                activeProfileId = 1L,
                bindings = setOf("com.example.game"),
                compiled = digitalBase_analogLayerConfig(layerId = 99L),
                activeSetId = 1L,
                activeLayers = emptyList(),
            ),
        )
    }

    @Test
    fun activeSetIdZero_fallsBackToStartingSet() {
        // 0L is the "lazy-uninit" sentinel — coordinator should resolve via
        // compiled.startingActionSetId, NOT treat the active set as missing.
        assertTrue(
            coordinator.evaluatePredicate(
                foregroundPkg = "com.example.game",
                activeProfileId = 1L,
                bindings = setOf("com.example.game"),
                compiled = analogConfig(startingSetId = 7L),
                activeSetId = 0L,
                activeLayers = emptyList(),
            ),
        )
    }

    // ── Fixtures ──────────────────────────────────────────────────────────────

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
