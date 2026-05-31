package com.mapo.service.input

import com.mapo.data.model.steam.BindingMode
import com.mapo.data.model.steam.InputSource
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Predicate truth-table + start/stop lifecycle tests for
 * [GyroLifecycleCoordinator]. Plain JUnit + mockk; no Robolectric needed
 * because the predicate is a pure helper and the lifecycle is verified
 * against a mocked [GyroSensorStream].
 */
@OptIn(ExperimentalCoroutinesApi::class)
class GyroLifecycleCoordinatorTest {

    private val testScope: TestScope = TestScope(UnconfinedTestDispatcher())
    private lateinit var inputDispatcher: InputDispatcher
    private lateinit var inputEvaluator: InputEvaluator
    private lateinit var gyroSensorStream: GyroSensorStream
    private lateinit var mouseEmitter: MouseEmitterImpl
    private lateinit var subject: GyroLifecycleCoordinator

    @Before
    fun setUp() {
        inputDispatcher = mockk(relaxed = true)
        inputEvaluator = mockk(relaxed = true)
        gyroSensorStream = mockk(relaxed = true) {
            every { hasGyro } returns true
            every { isRunning } returns false
        }
        mouseEmitter = mockk(relaxed = true)
        subject = GyroLifecycleCoordinator(
            inputDispatcher,
            inputEvaluator,
            gyroSensorStream,
            mouseEmitter,
            testScope,
        )
    }

    @After
    fun tearDown() {
        (testScope as CoroutineScope).cancel()
    }

    // ── Predicate truth table ───────────────────────────────────────────────

    @Test
    fun predicate_emptyConfig_isFalse() {
        val cfg = CompiledConfig.EMPTY
        assertFalse(subject.hasGyroModeInScope(cfg, activeSetId = 0L, activeLayers = emptyList()))
    }

    @Test
    fun predicate_gyroDeviceDefault_isFalse() {
        // DEVICE_DEFAULT == no entry under (GYRO, _) in inputs map.
        // So the predicate sees no GYRO binding at all — false.
        val cfg = configWithGyroBinding(BindingMode.DEVICE_DEFAULT)
        assertFalse(subject.hasGyroModeInScope(cfg, activeSetId = SET_ID, activeLayers = emptyList()))
    }

    @Test
    fun predicate_gyroNone_isFalse() {
        // NONE means "intercept and silence"; we don't need the sensor running
        // to silence (we just don't forward events). False.
        val cfg = configWithGyroBinding(BindingMode.NONE)
        assertFalse(subject.hasGyroModeInScope(cfg, activeSetId = SET_ID, activeLayers = emptyList()))
    }

    @Test
    fun predicate_gyroToMouse_isTrue() {
        val cfg = configWithGyroBinding(BindingMode.GYRO_TO_MOUSE)
        assertTrue(subject.hasGyroModeInScope(cfg, activeSetId = SET_ID, activeLayers = emptyList()))
    }

    @Test
    fun predicate_gyroToJoystickCamera_isTrue() {
        val cfg = configWithGyroBinding(BindingMode.GYRO_TO_JOYSTICK_CAMERA)
        assertTrue(subject.hasGyroModeInScope(cfg, activeSetId = SET_ID, activeLayers = emptyList()))
    }

    @Test
    fun predicate_dpadOnGyro_isTrue() {
        // Dpad on a gyro source = "tilt-as-dpad," needs the sensor running.
        val cfg = configWithGyroBinding(BindingMode.DPAD)
        assertTrue(subject.hasGyroModeInScope(cfg, activeSetId = SET_ID, activeLayers = emptyList()))
    }

    @Test
    fun predicate_directionalSwipe_isTrue() {
        val cfg = configWithGyroBinding(BindingMode.DIRECTIONAL_SWIPE)
        assertTrue(subject.hasGyroModeInScope(cfg, activeSetId = SET_ID, activeLayers = emptyList()))
    }

    @Test
    fun predicate_gyroOnLayer_isTrue() {
        // Base set has nothing; an active layer overlays GYRO_TO_MOUSE.
        val cfg = configWithGyroBindingOnLayer(BindingMode.GYRO_TO_MOUSE, layerId = LAYER_ID)
        assertTrue(subject.hasGyroModeInScope(cfg, activeSetId = SET_ID, activeLayers = listOf(LAYER_ID)))
    }

    @Test
    fun predicate_gyroOnInactiveLayer_isFalse() {
        // Layer exists but isn't in activeLayers → don't enumerate it.
        val cfg = configWithGyroBindingOnLayer(BindingMode.GYRO_TO_MOUSE, layerId = LAYER_ID)
        assertFalse(subject.hasGyroModeInScope(cfg, activeSetId = SET_ID, activeLayers = emptyList()))
    }

    @Test
    fun predicate_activeSetIdZero_fallsBackToStartingSet() {
        // activeSetId of 0L is the "lazy-uninit" marker — resolve to
        // compiled.startingActionSetId.
        val cfg = configWithGyroBinding(BindingMode.GYRO_TO_MOUSE)
        assertTrue(subject.hasGyroModeInScope(cfg, activeSetId = 0L, activeLayers = emptyList()))
    }

    @Test
    fun predicate_nonGyroSourceWithGyroMode_isFalse() {
        // A stick (LEFT_JOYSTICK) bound to JOYSTICK_MOUSE — not gyro, so the
        // sensor doesn't need to run.
        val cfg = configWithBinding(InputSource.LEFT_JOYSTICK, BindingMode.JOYSTICK_MOUSE)
        assertFalse(subject.hasGyroModeInScope(cfg, activeSetId = SET_ID, activeLayers = emptyList()))
    }

    // ── Lifecycle ───────────────────────────────────────────────────────────

    @Test
    fun start_skipsCombineLoop_whenDeviceHasNoGyro() {
        every { gyroSensorStream.hasGyro } returns false
        subject.start()
        // No combine subscriptions, no start/stop calls beyond the initial check.
        verify(exactly = 0) { gyroSensorStream.start(any()) }
        verify(exactly = 0) { gyroSensorStream.stop() }
    }

    @Test
    fun stop_pausesSensorStream() {
        subject.start()
        subject.stop()
        verify { gyroSensorStream.stop() }
    }

    // ── Test fixtures ───────────────────────────────────────────────────────

    private fun configWithGyroBinding(mode: BindingMode): CompiledConfig =
        configWithBinding(InputSource.GYRO, mode)

    private fun configWithBinding(source: InputSource, mode: BindingMode): CompiledConfig {
        val address = InputAddress(source, "_unused")
        val inputs: Map<InputAddress, CompiledInput> = if (mode == BindingMode.DEVICE_DEFAULT) {
            // DEVICE_DEFAULT = absent from inputs map.
            emptyMap()
        } else {
            mapOf(address to CompiledInput(groupInputId = 1L, activators = emptyList(), mode = mode))
        }
        val noneSources: Set<InputSource> =
            if (mode == BindingMode.NONE) setOf(source) else emptySet()
        return CompiledConfig(
            startingActionSetId = SET_ID,
            sets = mapOf(
                SET_ID to CompiledActionSet(
                    actionSetId = SET_ID,
                    inputs = inputs,
                    noneModeSources = noneSources,
                ),
            ),
        )
    }

    private fun configWithGyroBindingOnLayer(mode: BindingMode, layerId: Long): CompiledConfig {
        val address = InputAddress(InputSource.GYRO, "_unused")
        val layer = CompiledLayer(
            layerId = layerId,
            inputs = mapOf(
                address to CompiledInput(groupInputId = 1L, activators = emptyList(), mode = mode),
            ),
        )
        return CompiledConfig(
            startingActionSetId = SET_ID,
            sets = mapOf(
                SET_ID to CompiledActionSet(
                    actionSetId = SET_ID,
                    inputs = emptyMap(),
                    layers = mapOf(layerId to layer),
                ),
            ),
        )
    }

    companion object {
        private const val SET_ID = 100L
        private const val LAYER_ID = 200L
    }
}
