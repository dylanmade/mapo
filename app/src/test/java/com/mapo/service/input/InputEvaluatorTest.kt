package com.mapo.service.input

import com.mapo.data.model.steam.ActivatorType
import com.mapo.data.model.steam.BindingOutput
import com.mapo.data.model.steam.InputSource
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.mockk.verifyOrder
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class InputEvaluatorTest {

    private lateinit var dispatcher: InputDispatcher
    private lateinit var emitter: OutputEmitter
    private val compiledConfig = MutableStateFlow(CompiledConfig.EMPTY)
    private val ENTER = BindingOutput.KeyPress("ENTER")
    private val ESCAPE = BindingOutput.KeyPress("ESCAPE")
    private val SPACE = BindingOutput.KeyPress("SPACE")
    private val BUTTON_A = InputAddress(InputSource.BUTTON_DIAMOND, "button_a")

    private val testDispatcher = StandardTestDispatcher()
    private val testScope = TestScope(testDispatcher)

    private lateinit var subject: InputEvaluator

    @Before
    fun setUp() {
        dispatcher = mockk(relaxed = true)
        emitter = mockk(relaxed = true)
        every { dispatcher.compiledConfig } returns compiledConfig
        every { emitter.emitPress(any()) } returns true  // default to "has release" semantics
        subject = InputEvaluator(dispatcher, emitter, testScope)
    }

    // ── Pass-through (no config / no match) ───────────────────────────────────

    @Test
    fun press_unknownAddress_returnsFalse_noEmission() {
        // EMPTY config has no inputs
        val consumed = subject.handleDigital(BUTTON_A, isDown = true)
        assertFalse(consumed)
        verify(exactly = 0) { emitter.emitPress(any()) }
    }

    @Test
    fun release_unheldAddress_returnsFalse_noEmission() {
        val consumed = subject.handleDigital(BUTTON_A, isDown = false)
        assertFalse(consumed)
        verify(exactly = 0) { emitter.emitRelease(any()) }
    }

    // ── FULL_PRESS press → release roundtrip ──────────────────────────────────

    @Test
    fun press_thenRelease_emitsPressThenRelease() {
        compiledConfig.value = configWith(BUTTON_A to activator(ActivatorType.FULL_PRESS, ENTER))

        val pressConsumed = subject.handleDigital(BUTTON_A, isDown = true)
        val releaseConsumed = subject.handleDigital(BUTTON_A, isDown = false)

        assertTrue(pressConsumed)
        assertTrue(releaseConsumed)
        verifyOrder {
            emitter.emitPress(ENTER)
            emitter.emitRelease(ENTER)
        }
        assertEquals(0, subject.heldAddressCount())
    }

    @Test
    fun multipleAddresses_releaseIndependently() {
        val BUTTON_B = InputAddress(InputSource.BUTTON_DIAMOND, "button_b")
        compiledConfig.value = configWith(
            BUTTON_A to activator(ActivatorType.FULL_PRESS, ENTER),
            BUTTON_B to activator(ActivatorType.FULL_PRESS, ESCAPE),
        )

        subject.handleDigital(BUTTON_A, isDown = true)
        subject.handleDigital(BUTTON_B, isDown = true)
        assertEquals(2, subject.heldAddressCount())

        subject.handleDigital(BUTTON_A, isDown = false)
        verify(exactly = 1) { emitter.emitRelease(ENTER) }
        verify(exactly = 0) { emitter.emitRelease(ESCAPE) }
        assertEquals(1, subject.heldAddressCount())

        subject.handleDigital(BUTTON_B, isDown = false)
        verify(exactly = 1) { emitter.emitRelease(ESCAPE) }
        assertEquals(0, subject.heldAddressCount())
    }

    // ── LONG_PRESS (Brick 3.1) ───────────────────────────────────────────────

    @Test
    fun longPress_releasedBeforeThreshold_doesNotFire() = testScope.runTest {
        compiledConfig.value = configWith(
            BUTTON_A to activator(ActivatorType.LONG_PRESS, ESCAPE, longPressTimeMs = 300L),
        )

        val pressConsumed = subject.handleDigital(BUTTON_A, isDown = true)
        advanceTimeBy(150L)  // halfway to threshold
        val releaseConsumed = subject.handleDigital(BUTTON_A, isDown = false)
        advanceTimeBy(1_000L)  // let any leaked timer fire — should be cancelled

        assertTrue("Press of a configured LONG_PRESS address is consumed", pressConsumed)
        assertTrue("Release of a configured address is consumed", releaseConsumed)
        verify(exactly = 0) { emitter.emitPress(any()) }
        verify(exactly = 0) { emitter.emitRelease(any()) }
        assertEquals(0, subject.pendingAddressCount())
    }

    @Test
    fun longPress_heldPastThreshold_firesAndReleasesOnUp() = testScope.runTest {
        compiledConfig.value = configWith(
            BUTTON_A to activator(ActivatorType.LONG_PRESS, ESCAPE, longPressTimeMs = 300L),
        )

        subject.handleDigital(BUTTON_A, isDown = true)
        advanceTimeBy(350L)  // past the threshold
        runCurrent()
        verify(exactly = 1) { emitter.emitPress(ESCAPE) }
        assertEquals(0, subject.pendingAddressCount())
        assertEquals(1, subject.heldAddressCount())

        subject.handleDigital(BUTTON_A, isDown = false)
        verify(exactly = 1) { emitter.emitRelease(ESCAPE) }
        assertEquals(0, subject.heldAddressCount())
    }

    @Test
    fun longPress_andFullPress_bothFireWhenHeldPastThreshold() = testScope.runTest {
        // Steam default: a FULL + LONG on the same input both fire when the user holds —
        // FULL on DOWN edge, LONG once the threshold elapses. Both release on UP.
        compiledConfig.value = configWith(
            BUTTON_A to listOf(
                CompiledActivator(1L, ActivatorType.FULL_PRESS, listOf(ENTER), CompiledActivatorSettings.DEFAULTS),
                CompiledActivator(2L, ActivatorType.LONG_PRESS, listOf(ESCAPE),
                    CompiledActivatorSettings(longPressTimeMs = 300L)),
            ),
        )

        subject.handleDigital(BUTTON_A, isDown = true)
        verify(exactly = 1) { emitter.emitPress(ENTER) }   // FULL fires immediately
        verify(exactly = 0) { emitter.emitPress(ESCAPE) }

        advanceTimeBy(350L)
        runCurrent()
        verify(exactly = 1) { emitter.emitPress(ESCAPE) }  // LONG fires at threshold

        subject.handleDigital(BUTTON_A, isDown = false)
        verify(exactly = 1) { emitter.emitRelease(ENTER) }
        verify(exactly = 1) { emitter.emitRelease(ESCAPE) }
    }

    @Test
    fun longPress_releaseBeforeThreshold_doesNotPreventLaterPress() = testScope.runTest {
        // A pending timer must be cancelled cleanly so a subsequent DOWN starts fresh.
        compiledConfig.value = configWith(
            BUTTON_A to activator(ActivatorType.LONG_PRESS, ESCAPE, longPressTimeMs = 300L),
        )

        subject.handleDigital(BUTTON_A, isDown = true)
        subject.handleDigital(BUTTON_A, isDown = false)
        subject.handleDigital(BUTTON_A, isDown = true)
        advanceTimeBy(350L)
        runCurrent()

        // Only the second press's timer should have produced an emission.
        verify(exactly = 1) { emitter.emitPress(ESCAPE) }
    }

    // ── START_PRESS (Brick 3.1) ──────────────────────────────────────────────

    @Test
    fun startPress_emitsTapOnDown_noEmissionOnUp() {
        compiledConfig.value = configWith(
            BUTTON_A to activator(ActivatorType.START_PRESS, ENTER),
        )

        val pressConsumed = subject.handleDigital(BUTTON_A, isDown = true)
        verifyOrder {
            emitter.emitPress(ENTER)
            emitter.emitRelease(ENTER)
        }

        val releaseConsumed = subject.handleDigital(BUTTON_A, isDown = false)
        // Same release count as before — the UP doesn't fire anything extra.
        verify(exactly = 1) { emitter.emitRelease(ENTER) }
        verify(exactly = 1) { emitter.emitPress(ENTER) }

        assertTrue(pressConsumed)
        assertTrue("UP of a configured address is still consumed", releaseConsumed)
        assertEquals(0, subject.heldAddressCount())
    }

    // ── RELEASE_PRESS (Brick 3.1) ────────────────────────────────────────────

    @Test
    fun releasePress_emitsTapOnUpOnly() {
        compiledConfig.value = configWith(
            BUTTON_A to activator(ActivatorType.RELEASE_PRESS, ESCAPE),
        )

        val pressConsumed = subject.handleDigital(BUTTON_A, isDown = true)
        verify(exactly = 0) { emitter.emitPress(any()) }
        assertTrue("DOWN of a configured address is consumed even when nothing fires", pressConsumed)

        val releaseConsumed = subject.handleDigital(BUTTON_A, isDown = false)
        verifyOrder {
            emitter.emitPress(ESCAPE)
            emitter.emitRelease(ESCAPE)
        }
        assertTrue(releaseConsumed)
        assertEquals(0, subject.heldAddressCount())
    }

    @Test
    fun releasePress_andFullPress_bothFire() {
        // FULL fires on DOWN; RELEASE fires on UP. Both should land cleanly on one input.
        compiledConfig.value = configWith(
            BUTTON_A to listOf(
                CompiledActivator(1L, ActivatorType.FULL_PRESS, listOf(ENTER), CompiledActivatorSettings.DEFAULTS),
                CompiledActivator(2L, ActivatorType.RELEASE_PRESS, listOf(SPACE), CompiledActivatorSettings.DEFAULTS),
            ),
        )

        subject.handleDigital(BUTTON_A, isDown = true)
        verify(exactly = 1) { emitter.emitPress(ENTER) }
        verify(exactly = 0) { emitter.emitPress(SPACE) }

        subject.handleDigital(BUTTON_A, isDown = false)
        verify(exactly = 1) { emitter.emitRelease(ENTER) }  // FULL released on UP
        verify(exactly = 1) { emitter.emitPress(SPACE) }    // RELEASE fired on UP
        verify(exactly = 1) { emitter.emitRelease(SPACE) }  // and ended its own tap
    }

    // ── Non-FULL_PRESS activator types (Phase 3.2/3.3 territory) ─────────────

    @Test
    fun press_doublePressActivator_isSkippedThisBrick() {
        // 3.1 doesn't implement DOUBLE_PRESS yet. The event is still consumed (we don't
        // want it leaking to the foreground app) but no emission happens.
        compiledConfig.value = configWith(
            BUTTON_A to activator(ActivatorType.DOUBLE_PRESS, ENTER),
        )

        val consumed = subject.handleDigital(BUTTON_A, isDown = true)

        assertTrue(consumed)
        verify(exactly = 0) { emitter.emitPress(any()) }
    }

    // ── Fire-and-done bindings (emitter returned false) ───────────────────────

    @Test
    fun press_fireAndDoneBinding_notTrackedInHeldSet() {
        // MouseButton/MouseWheel are fire-and-done — the emitter returns false to say
        // "don't bother tracking, there's no release". The evaluator should still mark
        // the press as consumed but should not store the binding for release.
        val click = BindingOutput.MouseButton("MOUSE_LEFT")
        every { emitter.emitPress(click) } returns false
        compiledConfig.value = configWith(BUTTON_A to activator(ActivatorType.FULL_PRESS, click))

        val pressConsumed = subject.handleDigital(BUTTON_A, isDown = true)
        val releaseConsumed = subject.handleDigital(BUTTON_A, isDown = false)

        assertTrue(pressConsumed)
        assertTrue("Release of a configured-but-fire-and-done address still consumes", releaseConsumed)
        verify(exactly = 1) { emitter.emitPress(click) }
        verify(exactly = 0) { emitter.emitRelease(any()) }
    }

    // ── Defensive guards ──────────────────────────────────────────────────────

    @Test
    fun press_alreadyHeld_releasesStaleBindingsFirst() {
        compiledConfig.value = configWith(BUTTON_A to activator(ActivatorType.FULL_PRESS, ENTER))

        subject.handleDigital(BUTTON_A, isDown = true)
        // Same address pressed again without a release — possible if Android delivers a
        // duplicate DOWN due to a flaky controller. Should NOT leak the stale press.
        subject.handleDigital(BUTTON_A, isDown = true)

        verify(exactly = 2) { emitter.emitPress(ENTER) }
        verify(exactly = 1) { emitter.emitRelease(ENTER) }
        assertEquals(1, subject.heldAddressCount())
    }

    @Test
    fun configChange_whileHeld_releaseStillFiresOriginalBinding() {
        // Press with binding A configured. Then config swaps to a totally different binding.
        // On release, the *original* press's bindings should be released — the evaluator
        // can't unwind a DOWN it didn't take, but it must always release what it took.
        compiledConfig.value = configWith(BUTTON_A to activator(ActivatorType.FULL_PRESS, ENTER))
        subject.handleDigital(BUTTON_A, isDown = true)

        compiledConfig.value = configWith(BUTTON_A to activator(ActivatorType.FULL_PRESS, ESCAPE))
        subject.handleDigital(BUTTON_A, isDown = false)

        verify(exactly = 1) { emitter.emitRelease(ENTER) }
        verify(exactly = 0) { emitter.emitRelease(ESCAPE) }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun activator(
        type: ActivatorType,
        vararg bindings: BindingOutput,
        longPressTimeMs: Long = CompiledActivatorSettings.DEFAULT_LONG_PRESS_TIME_MS,
    ) = listOf(
        CompiledActivator(
            activatorId = 0L,
            type = type,
            bindings = bindings.toList(),
            settings = CompiledActivatorSettings(longPressTimeMs = longPressTimeMs),
        ),
    )

    private fun configWith(vararg entries: Pair<InputAddress, List<CompiledActivator>>): CompiledConfig {
        val inputs = entries.associate { (addr, activators) ->
            addr to CompiledInput(groupInputId = 0L, activators = activators)
        }
        return CompiledConfig(activeActionSetId = 1L, inputs = inputs)
    }
}
