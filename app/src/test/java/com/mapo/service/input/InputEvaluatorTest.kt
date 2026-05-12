package com.mapo.service.input

import com.mapo.data.model.steam.ActivatorType
import com.mapo.data.model.steam.BindingOutput
import com.mapo.data.model.steam.InputSource
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.mockk.verifyOrder
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class InputEvaluatorTest {

    private lateinit var dispatcher: InputDispatcher
    private lateinit var emitter: OutputEmitter
    private val compiledConfig = MutableStateFlow(CompiledConfig.EMPTY)
    private val ENTER = BindingOutput.KeyPress("ENTER")
    private val ESCAPE = BindingOutput.KeyPress("ESCAPE")
    private val BUTTON_A = InputAddress(InputSource.BUTTON_DIAMOND, "button_a")

    private lateinit var subject: InputEvaluator

    @Before
    fun setUp() {
        dispatcher = mockk(relaxed = true)
        emitter = mockk(relaxed = true)
        every { dispatcher.compiledConfig } returns compiledConfig
        every { emitter.emitPress(any()) } returns true  // default to "has release" semantics
        subject = InputEvaluator(dispatcher, emitter)
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

    // ── Non-FULL_PRESS activator types (Phase 3 territory) ────────────────────

    @Test
    fun press_nonFullPressActivator_isSkipped() {
        compiledConfig.value = configWith(BUTTON_A to activator(ActivatorType.LONG_PRESS, ENTER))

        val consumed = subject.handleDigital(BUTTON_A, isDown = true)

        // Address matched but the lone activator was a non-FULL_PRESS one — the event
        // is still consumed (we don't want it to pass through to the game) but nothing
        // gets emitted.
        assertTrue(consumed)
        verify(exactly = 0) { emitter.emitPress(any()) }
    }

    @Test
    fun press_mixedActivatorTypes_onlyFullPressFires() {
        compiledConfig.value = configWith(
            BUTTON_A to listOf(
                CompiledActivator(activatorId = 1L, type = ActivatorType.LONG_PRESS, bindings = listOf(ESCAPE)),
                CompiledActivator(activatorId = 2L, type = ActivatorType.FULL_PRESS, bindings = listOf(ENTER)),
            ),
        )

        subject.handleDigital(BUTTON_A, isDown = true)

        verify(exactly = 1) { emitter.emitPress(ENTER) }
        verify(exactly = 0) { emitter.emitPress(ESCAPE) }
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
        assertFalse("Release of a fire-and-done address shouldn't consume", releaseConsumed)
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

    private fun activator(type: ActivatorType, vararg bindings: BindingOutput) = listOf(
        CompiledActivator(activatorId = 0L, type = type, bindings = bindings.toList()),
    )

    private fun configWith(vararg entries: Pair<InputAddress, List<CompiledActivator>>): CompiledConfig {
        val inputs = entries.associate { (addr, activators) ->
            addr to CompiledInput(groupInputId = 0L, activators = activators)
        }
        return CompiledConfig(activeActionSetId = 1L, inputs = inputs)
    }
}
