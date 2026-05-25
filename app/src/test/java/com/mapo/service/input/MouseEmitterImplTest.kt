package com.mapo.service.input

import com.mapo.data.model.steam.InputSource
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import org.junit.After
import org.junit.Before
import org.junit.Test

/**
 * Integration-loop tests for [MouseEmitterImpl]. Uses the codebase-standard
 * `TestScope(UnconfinedTestDispatcher())` + `@After { scope.cancel() }`
 * pattern (same as `ShizukuMotionStreamTest`) rather than `runTest { }` —
 * the emitter's internal launch is a `while (isActive) { delay(...); ... }`
 * loop that only exits when every velocity slot zeros out. `runTest` would
 * wait for it indefinitely; manual scope control lets us cancel cleanly in
 * teardown regardless of what the test left running.
 *
 * The mocked [InputDispatcher] is verified for *behavioral* shape — drag
 * begin / end, presence of `injectMouseMove` calls, no calls when velocity
 * is zero. Exact pixel deltas are sensitive to dispatcher implementation
 * choices (truncation, residual accumulation) and aren't pinned here; those
 * live in [com.mapo.service.input.modes.StickToMouseSettingsTest] which
 * exercises the pure math.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MouseEmitterImplTest {

    private val testScope: TestScope = TestScope(UnconfinedTestDispatcher())
    private lateinit var dispatcher: InputDispatcher
    private lateinit var subject: MouseEmitterImpl

    @Before
    fun setUp() {
        dispatcher = mockk(relaxed = true)
        subject = MouseEmitterImpl(dispatcher, testScope)
    }

    @After
    fun tearDown() {
        (testScope as CoroutineScope).cancel()
    }

    @Test
    fun zeroVelocity_doesNotStartLoop() {
        subject.setStickVelocity(InputSource.LEFT_JOYSTICK, 0f, 0f)
        testScope.advanceTimeBy(100L)
        testScope.runCurrent()
        verify(exactly = 0) { dispatcher.beginContinuousCursor() }
        verify(exactly = 0) { dispatcher.injectMouseMove(any(), any()) }
    }

    @Test
    fun nonZeroVelocity_beginsContinuousAndDispatchesMoves() {
        subject.setStickVelocity(InputSource.LEFT_JOYSTICK, 1000f, 0f)
        // Step interval is 8ms; advance well past several steps so the
        // residual accumulator has crossed integer thresholds.
        testScope.advanceTimeBy(100L)
        testScope.runCurrent()
        verify(atLeast = 1) { dispatcher.beginContinuousCursor() }
        verify(atLeast = 1) { dispatcher.injectMouseMove(any(), any()) }
    }

    @Test
    fun returnToZero_endsContinuousAndStopsDispatching() {
        subject.setStickVelocity(InputSource.LEFT_JOYSTICK, 1000f, 0f)
        testScope.advanceTimeBy(50L)
        testScope.runCurrent()
        // Stick returns to deadzone center → mode calls setStickVelocity(0, 0)
        subject.setStickVelocity(InputSource.LEFT_JOYSTICK, 0f, 0f)
        testScope.advanceUntilIdle()
        verify(atLeast = 1) { dispatcher.endContinuousCursor() }
    }

    @Test
    fun twoSources_summed_thenIndependentlyCleared() {
        subject.setStickVelocity(InputSource.LEFT_JOYSTICK, 500f, 0f)
        subject.setStickVelocity(InputSource.RIGHT_JOYSTICK, 0f, 500f)
        testScope.advanceTimeBy(50L)
        testScope.runCurrent()
        // Both contribute, integration loop is running.
        verify(atLeast = 1) { dispatcher.injectMouseMove(any(), any()) }
        // Clear LJ; RJ still contributes → loop stays running.
        subject.setStickVelocity(InputSource.LEFT_JOYSTICK, 0f, 0f)
        testScope.advanceTimeBy(50L)
        testScope.runCurrent()
        verify(exactly = 0) { dispatcher.endContinuousCursor() }
        // Clear RJ — now loop should exit.
        subject.setStickVelocity(InputSource.RIGHT_JOYSTICK, 0f, 0f)
        testScope.advanceUntilIdle()
        verify(atLeast = 1) { dispatcher.endContinuousCursor() }
    }

    @Test
    fun clearAllVelocities_stopsLoop() {
        subject.setStickVelocity(InputSource.LEFT_JOYSTICK, 1000f, 1000f)
        testScope.advanceTimeBy(30L)
        testScope.runCurrent()
        subject.clearAllVelocities()
        testScope.advanceUntilIdle()
        verify(atLeast = 1) { dispatcher.endContinuousCursor() }
    }

    @Test
    fun residualAccumulation_slowVelocity_eventuallyMoves() {
        // 30 px/sec * 8 ms = 0.24 px/step — would truncate to zero every step
        // without residual carry-forward. Over 200 ms (~25 steps) we should
        // accumulate ~6 px of motion → at least one non-zero dispatch.
        subject.setStickVelocity(InputSource.LEFT_JOYSTICK, 30f, 0f)
        testScope.advanceTimeBy(200L)
        testScope.runCurrent()
        verify(atLeast = 1) { dispatcher.injectMouseMove(any(), any()) }
    }
}
