@file:OptIn(ExperimentalCoroutinesApi::class)

package com.mappo.service.shizuku

import com.mappo.data.model.steam.InputSource
import com.mappo.service.input.AnalogEvent
import com.mappo.service.input.InputEvaluator
import com.mappo.shizuku.IMappoInputService
import com.mappo.shizuku.InputSourceId
import com.mappo.shizuku.RawAnalogEvent
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Brick D coverage: the [RawAnalogEvent] → [AnalogEvent] conversion table is
 * exhaustively checked, and the binder callback's dispatch into
 * [InputEvaluator.handleAnalogReadings] is verified end-to-end via the test
 * scope's `UnconfinedTestDispatcher` — collectors run inline so the assertion
 * lands before the test method returns.
 *
 * Robolectric is required for the same reason [ShizukuConnectionTest] uses it:
 * the Shizuku/binder types are loaded eagerly; the test never hits a live binder.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class ShizukuMotionStreamTest {

    private val testScope: TestScope = TestScope(UnconfinedTestDispatcher())

    @After
    fun teardown() {
        (testScope as CoroutineScope).cancel()
    }

    /**
     * Builds a stream with a mocked [ShizukuConnection] whose service flow stays
     * at `null` — the registerCallback path is irrelevant to Brick D's dispatch
     * pipeline (we drive [ShizukuMotionStream.callback] directly).
     */
    private fun makeStream(evaluator: InputEvaluator): ShizukuMotionStream {
        val connection = mockk<ShizukuConnection>(relaxed = true)
        val serviceFlow: StateFlow<IMappoInputService?> = MutableStateFlow(null)
        every { connection.service } returns serviceFlow
        return ShizukuMotionStream(connection, evaluator, testScope)
    }

    // ── Conversion table ─────────────────────────────────────────────────────

    @Test
    fun convertToAnalogEvent_leftJoystick_mapsToLeftJoystickSource() {
        val stream = makeStream(mockk(relaxed = true))
        val converted = stream.convertToAnalogEvent(
            RawAnalogEvent(
                sourceOrdinal = InputSourceId.LEFT_JOYSTICK,
                x = -0.5f,
                y = 0.8f,
                timestampNs = 7_000_000L,
            ),
        )!!
        assertEquals(InputSource.LEFT_JOYSTICK, converted.source)
        assertEquals(-0.5f, converted.x, EPS)
        assertEquals(0.8f, converted.y, EPS)
        assertEquals(7L, converted.timestampMs)
    }

    @Test
    fun convertToAnalogEvent_rightJoystick_mapsToRightJoystickSource() {
        val stream = makeStream(mockk(relaxed = true))
        val converted = stream.convertToAnalogEvent(
            RawAnalogEvent(
                sourceOrdinal = InputSourceId.RIGHT_JOYSTICK,
                x = 1f,
                y = -1f,
                timestampNs = 0L,
            ),
        )!!
        assertEquals(InputSource.RIGHT_JOYSTICK, converted.source)
        assertEquals(1f, converted.x, EPS)
        assertEquals(-1f, converted.y, EPS)
    }

    @Test
    fun convertToAnalogEvent_leftTrigger_mapsToLeftTriggerSource() {
        val stream = makeStream(mockk(relaxed = true))
        val converted = stream.convertToAnalogEvent(
            RawAnalogEvent(
                sourceOrdinal = InputSourceId.LEFT_TRIGGER,
                x = 0.5f,
                y = 0f,
                timestampNs = 12_000_000L,
            ),
        )!!
        assertEquals(InputSource.LEFT_TRIGGER, converted.source)
        assertEquals(0.5f, converted.x, EPS)
        assertEquals(0f, converted.y, EPS)
        // 12 million ns / 1 million = 12 ms.
        assertEquals(12L, converted.timestampMs)
    }

    @Test
    fun convertToAnalogEvent_rightTrigger_mapsToRightTriggerSource() {
        val stream = makeStream(mockk(relaxed = true))
        val converted = stream.convertToAnalogEvent(
            RawAnalogEvent(
                sourceOrdinal = InputSourceId.RIGHT_TRIGGER,
                x = 1f, y = 0f, timestampNs = 0L,
            ),
        )!!
        assertEquals(InputSource.RIGHT_TRIGGER, converted.source)
    }

    @Test
    fun convertToAnalogEvent_dpad_mapsToDpadSource() {
        val stream = makeStream(mockk(relaxed = true))
        val converted = stream.convertToAnalogEvent(
            RawAnalogEvent(
                sourceOrdinal = InputSourceId.DPAD,
                x = -1f, y = 0f, timestampNs = 0L,
            ),
        )!!
        assertEquals(InputSource.DPAD, converted.source)
    }

    @Test
    fun convertToAnalogEvent_unknownSourceId_returnsNull() {
        // UNKNOWN is the sentinel for "service couldn't classify"; dropping it
        // is the right degraded behavior.
        val stream = makeStream(mockk(relaxed = true))
        val raw = RawAnalogEvent(
            sourceOrdinal = InputSourceId.UNKNOWN,
            x = 0f, y = 0f, timestampNs = 0L,
        )
        assertNull(stream.convertToAnalogEvent(raw))
    }

    @Test
    fun convertToAnalogEvent_futureSourceId_returnsNull() {
        // Forward-compat: if a service binary ships with a new InputSourceId
        // before :app is rebuilt, the app must drop instead of crash.
        val stream = makeStream(mockk(relaxed = true))
        val raw = RawAnalogEvent(
            sourceOrdinal = 999,
            x = 1f, y = 1f, timestampNs = 0L,
        )
        assertNull(stream.convertToAnalogEvent(raw))
    }

    // ── Callback → evaluator dispatch ────────────────────────────────────────

    @Test
    fun onAnalogEvent_dispatchesConvertedReadingToEvaluator() {
        val evaluator = mockk<InputEvaluator>(relaxed = true)
        val stream = makeStream(evaluator)

        stream.callback.onAnalogEvent(
            RawAnalogEvent(
                sourceOrdinal = InputSourceId.RIGHT_JOYSTICK,
                x = -0.25f,
                y = 0.75f,
                timestampNs = 5_000_000L,
            ),
        )
        testScope.testScheduler.advanceUntilIdle()

        val captured = slot<List<AnalogEvent>>()
        verify(exactly = 1) { evaluator.handleAnalogReadings(capture(captured)) }
        val readings = captured.captured
        assertEquals(1, readings.size)
        val r = readings.first()
        assertEquals(InputSource.RIGHT_JOYSTICK, r.source)
        assertEquals(-0.25f, r.x, EPS)
        assertEquals(0.75f, r.y, EPS)
        assertEquals(5L, r.timestampMs)
    }

    @Test
    fun onAnalogEvent_dropsUnknownSourceId_withoutEvaluatorCall() {
        val evaluator = mockk<InputEvaluator>(relaxed = true)
        val stream = makeStream(evaluator)

        stream.callback.onAnalogEvent(
            RawAnalogEvent(
                sourceOrdinal = InputSourceId.UNKNOWN,
                x = 0f, y = 0f, timestampNs = 0L,
            ),
        )
        testScope.testScheduler.advanceUntilIdle()

        verify(exactly = 0) { evaluator.handleAnalogReadings(any()) }
    }

    @Test
    fun onAnalogEvent_swallowsEvaluatorException_streamSurvives() {
        // A misbehaving SourceMode must not kill the binder thread or the
        // consumer coroutine — the next event has to keep flowing.
        val evaluator = mockk<InputEvaluator>(relaxed = true)
        every { evaluator.handleAnalogReadings(any()) } throws
            RuntimeException("simulated SourceMode failure")
        val stream = makeStream(evaluator)

        // Must not propagate the exception.
        stream.callback.onAnalogEvent(
            RawAnalogEvent(
                sourceOrdinal = InputSourceId.LEFT_TRIGGER,
                x = 0.3f, y = 0f, timestampNs = 0L,
            ),
        )
        testScope.testScheduler.advanceUntilIdle()
        verify(exactly = 1) { evaluator.handleAnalogReadings(any()) }
    }

    @Test
    fun onAnalogEvent_null_isDropped() {
        // AIDL nullable param — service is well-behaved today, but the stub
        // signature allows null and the callback's defensive check matters.
        val evaluator = mockk<InputEvaluator>(relaxed = true)
        val stream = makeStream(evaluator)
        stream.callback.onAnalogEvent(null)
        testScope.testScheduler.advanceUntilIdle()
        verify(exactly = 0) { evaluator.handleAnalogReadings(any()) }
    }

    companion object {
        private const val EPS = 0.0001f
    }
}
