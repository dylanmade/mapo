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
    fun longPress_andFullPress_nonInterruptable_bothFireWhenHeldPastThreshold() = testScope.runTest {
        // With interruptable=false, FULL_PRESS fires immediately on DOWN regardless of any
        // coexisting LONG. Both fire when the user holds past the threshold.
        compiledConfig.value = configWith(
            BUTTON_A to listOf(
                CompiledActivator(1L, ActivatorType.FULL_PRESS, listOf(ENTER),
                    CompiledActivatorSettings(interruptable = false)),
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
    fun longPress_andFullPress_interruptable_longSuppressesRegular() = testScope.runTest {
        // Steam default: with interruptable=true (the default), FULL is deferred while
        // LONG's threshold elapses. If LONG fires, FULL is suppressed entirely.
        compiledConfig.value = configWith(
            BUTTON_A to listOf(
                CompiledActivator(1L, ActivatorType.FULL_PRESS, listOf(ENTER),
                    CompiledActivatorSettings.DEFAULTS),
                CompiledActivator(2L, ActivatorType.LONG_PRESS, listOf(ESCAPE),
                    CompiledActivatorSettings(longPressTimeMs = 300L)),
            ),
        )

        subject.handleDigital(BUTTON_A, isDown = true)
        // FULL is deferred — no emission on DOWN.
        verify(exactly = 0) { emitter.emitPress(ENTER) }
        verify(exactly = 0) { emitter.emitPress(ESCAPE) }

        advanceTimeBy(350L)
        runCurrent()
        // LONG fires, suppressing FULL. ENTER never fires.
        verify(exactly = 1) { emitter.emitPress(ESCAPE) }
        verify(exactly = 0) { emitter.emitPress(ENTER) }

        subject.handleDigital(BUTTON_A, isDown = false)
        verify(exactly = 1) { emitter.emitRelease(ESCAPE) }
        verify(exactly = 0) { emitter.emitRelease(ENTER) }
    }

    @Test
    fun longPress_andFullPress_interruptable_upBeforeThresholdFiresRegularAsTap() = testScope.runTest {
        // The flip side: with interruptable=true, if the user releases before LONG threshold,
        // the deferred FULL fires retroactively as a tap (DOWN+UP back-to-back).
        compiledConfig.value = configWith(
            BUTTON_A to listOf(
                CompiledActivator(1L, ActivatorType.FULL_PRESS, listOf(ENTER),
                    CompiledActivatorSettings.DEFAULTS),
                CompiledActivator(2L, ActivatorType.LONG_PRESS, listOf(ESCAPE),
                    CompiledActivatorSettings(longPressTimeMs = 300L)),
            ),
        )

        subject.handleDigital(BUTTON_A, isDown = true)
        advanceTimeBy(150L)  // halfway to threshold
        subject.handleDigital(BUTTON_A, isDown = false)
        advanceTimeBy(500L)  // confirm no late LONG fire
        runCurrent()

        // FULL fires as a tap on the UP edge; LONG never fires.
        verifyOrder {
            emitter.emitPress(ENTER)
            emitter.emitRelease(ENTER)
        }
        verify(exactly = 0) { emitter.emitPress(ESCAPE) }
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

    // ── DOUBLE_PRESS (Brick 3.2) ─────────────────────────────────────────────

    @Test
    fun doublePress_only_singleTap_firesNothing() = testScope.runTest {
        // No Regular configured — a single tap should produce no emission even after the
        // window expires. The DOUBLE_PRESS only fires on a successful double-tap.
        compiledConfig.value = configWith(
            BUTTON_A to activator(ActivatorType.DOUBLE_PRESS, SPACE, doubleTapTimeMs = 250L),
        )

        subject.handleDigital(BUTTON_A, isDown = true)
        subject.handleDigital(BUTTON_A, isDown = false)
        advanceTimeBy(300L)
        runCurrent()

        verify(exactly = 0) { emitter.emitPress(any()) }
        assertEquals(0, subject.doubleTapWindowCount())
    }

    @Test
    fun doublePress_only_doubleTapFiresDoubleOnSecondDown_releasesOnUp() = testScope.runTest {
        compiledConfig.value = configWith(
            BUTTON_A to activator(ActivatorType.DOUBLE_PRESS, SPACE, doubleTapTimeMs = 250L),
        )

        subject.handleDigital(BUTTON_A, isDown = true)
        subject.handleDigital(BUTTON_A, isDown = false)
        advanceTimeBy(100L)  // well within the window
        subject.handleDigital(BUTTON_A, isDown = true)
        verify(exactly = 1) { emitter.emitPress(SPACE) }
        verify(exactly = 0) { emitter.emitRelease(SPACE) }
        assertEquals(0, subject.doubleTapWindowCount())

        subject.handleDigital(BUTTON_A, isDown = false)
        verify(exactly = 1) { emitter.emitRelease(SPACE) }
    }

    @Test
    fun doublePress_secondTapAfterWindowExpires_doesNotFireDouble() = testScope.runTest {
        compiledConfig.value = configWith(
            BUTTON_A to activator(ActivatorType.DOUBLE_PRESS, SPACE, doubleTapTimeMs = 250L),
        )

        subject.handleDigital(BUTTON_A, isDown = true)
        subject.handleDigital(BUTTON_A, isDown = false)
        advanceTimeBy(300L)  // past the window
        runCurrent()
        subject.handleDigital(BUTTON_A, isDown = true)  // arrives too late — new sequence

        verify(exactly = 0) { emitter.emitPress(SPACE) }  // Double doesn't fire
        // And the new DOWN started a fresh window (consumed but no immediate emit).
        assertEquals(1, subject.doubleTapWindowCount())
    }

    // ── Regular + Double coexistence (3.2 hardcoded interruptable=true) ───────

    @Test
    fun regular_andDouble_singleTap_firesRegularAsTapAfterWindow() = testScope.runTest {
        compiledConfig.value = configWith(
            BUTTON_A to listOf(
                CompiledActivator(1L, ActivatorType.FULL_PRESS, listOf(ENTER), CompiledActivatorSettings.DEFAULTS),
                CompiledActivator(2L, ActivatorType.DOUBLE_PRESS, listOf(SPACE),
                    CompiledActivatorSettings(longPressTimeMs = 600L, doubleTapTimeMs = 250L)),
            ),
        )

        subject.handleDigital(BUTTON_A, isDown = true)
        // Regular is deferred — no emission yet.
        verify(exactly = 0) { emitter.emitPress(ENTER) }
        verify(exactly = 0) { emitter.emitPress(SPACE) }

        subject.handleDigital(BUTTON_A, isDown = false)
        // Still no emission — window still alive, Regular still deferred.
        verify(exactly = 0) { emitter.emitPress(any()) }

        advanceTimeBy(300L)
        runCurrent()
        // Window expired with no second tap. Regular fires as a tap (button already up).
        verifyOrder {
            emitter.emitPress(ENTER)
            emitter.emitRelease(ENTER)
        }
        verify(exactly = 0) { emitter.emitPress(SPACE) }
        assertEquals(0, subject.doubleTapWindowCount())
    }

    @Test
    fun regular_andDouble_doubleTap_firesDoubleOnly_suppressesRegular() = testScope.runTest {
        compiledConfig.value = configWith(
            BUTTON_A to listOf(
                CompiledActivator(1L, ActivatorType.FULL_PRESS, listOf(ENTER), CompiledActivatorSettings.DEFAULTS),
                CompiledActivator(2L, ActivatorType.DOUBLE_PRESS, listOf(SPACE),
                    CompiledActivatorSettings(longPressTimeMs = 600L, doubleTapTimeMs = 250L)),
            ),
        )

        subject.handleDigital(BUTTON_A, isDown = true)
        subject.handleDigital(BUTTON_A, isDown = false)
        advanceTimeBy(100L)
        subject.handleDigital(BUTTON_A, isDown = true)
        subject.handleDigital(BUTTON_A, isDown = false)
        advanceTimeBy(500L)  // well past window — confirm no late-deferred-Regular
        runCurrent()

        verify(exactly = 1) { emitter.emitPress(SPACE) }
        verify(exactly = 1) { emitter.emitRelease(SPACE) }
        verify(exactly = 0) { emitter.emitPress(ENTER) }
        verify(exactly = 0) { emitter.emitRelease(ENTER) }
    }

    @Test
    fun regular_andDouble_firstTapHeldPastWindow_firesRegularAsHeld() = testScope.runTest {
        compiledConfig.value = configWith(
            BUTTON_A to listOf(
                CompiledActivator(1L, ActivatorType.FULL_PRESS, listOf(ENTER), CompiledActivatorSettings.DEFAULTS),
                CompiledActivator(2L, ActivatorType.DOUBLE_PRESS, listOf(SPACE),
                    CompiledActivatorSettings(longPressTimeMs = 600L, doubleTapTimeMs = 250L)),
            ),
        )

        subject.handleDigital(BUTTON_A, isDown = true)
        advanceTimeBy(300L)  // user is still holding the button when the window expires
        runCurrent()
        // Regular fires as held — DOWN edge only, release will come on physical UP.
        verify(exactly = 1) { emitter.emitPress(ENTER) }
        verify(exactly = 0) { emitter.emitRelease(ENTER) }
        assertEquals(1, subject.heldAddressCount())

        subject.handleDigital(BUTTON_A, isDown = false)
        verify(exactly = 1) { emitter.emitRelease(ENTER) }
    }

    // ── Non-FULL_PRESS activator types (Brick 3.3 territory) ─────────────────

    @Test
    fun chord_partnerNotConfigured_doesNotFire() {
        // CHORDED_PRESS with chord_partner=null is the freshly-added-activator state. The
        // press should consume but not fire.
        compiledConfig.value = configWith(
            BUTTON_A to activator(ActivatorType.CHORDED_PRESS, ENTER),
        )

        val consumed = subject.handleDigital(BUTTON_A, isDown = true)

        assertTrue(consumed)
        verify(exactly = 0) { emitter.emitPress(any()) }
        assertEquals(0, subject.activeChordCount())
    }

    @Test
    fun chord_partnerNotHeld_doesNotFire() = testScope.runTest {
        val BUTTON_B = InputAddress(InputSource.BUTTON_DIAMOND, "button_b")
        compiledConfig.value = configWith(
            BUTTON_A to listOf(chordActivator(ENTER, partner = BUTTON_B)),
        )

        // Press chord without partner held first → nothing fires.
        subject.handleDigital(BUTTON_A, isDown = true)
        verify(exactly = 0) { emitter.emitPress(any()) }
        assertEquals(0, subject.activeChordCount())
    }

    @Test
    fun chord_partnerHeldThenChord_fires_releasesOnChordUp() = testScope.runTest {
        val BUTTON_B = InputAddress(InputSource.BUTTON_DIAMOND, "button_b")
        compiledConfig.value = configWith(
            BUTTON_A to listOf(chordActivator(ENTER, partner = BUTTON_B)),
            // Partner needs a real CompiledInput entry so its press registers as
            // physically held — give it a no-op Regular binding.
            BUTTON_B to activator(ActivatorType.FULL_PRESS, SPACE),
        )

        // Press partner first
        subject.handleDigital(BUTTON_B, isDown = true)
        // Then press chord input → chord fires
        subject.handleDigital(BUTTON_A, isDown = true)
        verify(exactly = 1) { emitter.emitPress(ENTER) }
        assertEquals(1, subject.activeChordCount())

        // Release chord input → chord output releases
        subject.handleDigital(BUTTON_A, isDown = false)
        verify(exactly = 1) { emitter.emitRelease(ENTER) }
        assertEquals(0, subject.activeChordCount())

        // Release partner → no extra emission
        subject.handleDigital(BUTTON_B, isDown = false)
        verify(exactly = 1) { emitter.emitRelease(ENTER) }
    }

    @Test
    fun chord_partnerReleasedFirst_releasesChord() = testScope.runTest {
        val BUTTON_B = InputAddress(InputSource.BUTTON_DIAMOND, "button_b")
        compiledConfig.value = configWith(
            BUTTON_A to listOf(chordActivator(ENTER, partner = BUTTON_B)),
            BUTTON_B to activator(ActivatorType.FULL_PRESS, SPACE),
        )

        subject.handleDigital(BUTTON_B, isDown = true)
        subject.handleDigital(BUTTON_A, isDown = true)
        verify(exactly = 1) { emitter.emitPress(ENTER) }

        // User lets go of the *partner* while still holding the chord input — the chord
        // output must release (Steam-faithful: chord needs both held).
        subject.handleDigital(BUTTON_B, isDown = false)
        verify(exactly = 1) { emitter.emitRelease(ENTER) }
        assertEquals(0, subject.activeChordCount())

        // Subsequent release of the chord input is a no-op for ENTER.
        subject.handleDigital(BUTTON_A, isDown = false)
        verify(exactly = 1) { emitter.emitRelease(ENTER) }
    }

    @Test
    fun chord_pressedBeforePartner_doesNotFire_evenIfPartnerLater() = testScope.runTest {
        // Order matters: chord must be pressed AFTER partner. Pressing chord first and
        // partner second does not retroactively fire the chord.
        val BUTTON_B = InputAddress(InputSource.BUTTON_DIAMOND, "button_b")
        compiledConfig.value = configWith(
            BUTTON_A to listOf(chordActivator(ENTER, partner = BUTTON_B)),
            BUTTON_B to activator(ActivatorType.FULL_PRESS, SPACE),
        )

        subject.handleDigital(BUTTON_A, isDown = true)  // chord first — too early
        verify(exactly = 0) { emitter.emitPress(ENTER) }

        subject.handleDigital(BUTTON_B, isDown = true)  // partner arrives later
        // Even though both are now held, we don't retroactively fire — Steam-faithful.
        verify(exactly = 0) { emitter.emitPress(ENTER) }
        assertEquals(0, subject.activeChordCount())
    }

    // ── Universal settings (Brick 3.3) ───────────────────────────────────────

    @Test
    fun toggle_firstPress_latchesPressed_releaseDoesNothing() = testScope.runTest {
        compiledConfig.value = configWith(
            BUTTON_A to listOf(activatorWith(ActivatorType.FULL_PRESS, ENTER, toggle = true)),
        )

        subject.handleDigital(BUTTON_A, isDown = true)
        verify(exactly = 1) { emitter.emitPress(ENTER) }
        verify(exactly = 0) { emitter.emitRelease(ENTER) }

        // UP must not release the latched binding — that's the whole point of toggle.
        subject.handleDigital(BUTTON_A, isDown = false)
        verify(exactly = 0) { emitter.emitRelease(ENTER) }
        assertTrue(subject.isToggledOn(activatorId = 0L))
    }

    @Test
    fun toggle_secondPress_releasesLatchedBindings() = testScope.runTest {
        compiledConfig.value = configWith(
            BUTTON_A to listOf(activatorWith(ActivatorType.FULL_PRESS, ENTER, toggle = true)),
        )

        // First press → latch
        subject.handleDigital(BUTTON_A, isDown = true)
        subject.handleDigital(BUTTON_A, isDown = false)

        // Second press → release
        subject.handleDigital(BUTTON_A, isDown = true)
        verify(exactly = 1) { emitter.emitRelease(ENTER) }
        assertFalse(subject.isToggledOn(activatorId = 0L))

        // Second UP is a no-op (no held entries left).
        subject.handleDigital(BUTTON_A, isDown = false)
        verify(exactly = 1) { emitter.emitRelease(ENTER) }
    }

    @Test
    fun holdToRepeat_pulsesAtConfiguredRate_stopsOnRelease() = testScope.runTest {
        compiledConfig.value = configWith(
            BUTTON_A to listOf(activatorWith(
                ActivatorType.FULL_PRESS, ENTER,
                holdToRepeat = true,
                repeatRateMs = 100L,
            )),
        )

        subject.handleDigital(BUTTON_A, isDown = true)
        verify(exactly = 1) { emitter.emitPress(ENTER) }  // initial press

        advanceTimeBy(350L)  // expect 3 pulses (100, 200, 300)
        runCurrent()
        // initial + 3 turbo pulses = 4 total press calls. Each turbo pulse is a tap, so
        // emitPress count == 1 (initial held) + 3 (turbo) = 4; emitRelease == 3 (turbo).
        verify(exactly = 4) { emitter.emitPress(ENTER) }
        verify(exactly = 3) { emitter.emitRelease(ENTER) }
        assertEquals(1, subject.activeRepeatJobCount())

        subject.handleDigital(BUTTON_A, isDown = false)
        // The initial held press releases on UP; the turbo job is cancelled.
        verify(exactly = 4) { emitter.emitRelease(ENTER) }

        advanceTimeBy(500L)
        runCurrent()
        // No further pulses after release.
        verify(exactly = 4) { emitter.emitPress(ENTER) }
        assertEquals(0, subject.activeRepeatJobCount())
    }

    @Test
    fun fireStartDelay_releaseBeforeElapsed_cancelsEmission() = testScope.runTest {
        compiledConfig.value = configWith(
            BUTTON_A to listOf(activatorWith(
                ActivatorType.FULL_PRESS, ENTER,
                fireStartDelayMs = 200L,
            )),
        )

        subject.handleDigital(BUTTON_A, isDown = true)
        // Delay still pending — no emit yet.
        verify(exactly = 0) { emitter.emitPress(any()) }

        advanceTimeBy(100L)
        subject.handleDigital(BUTTON_A, isDown = false)
        advanceTimeBy(500L)
        runCurrent()

        // Start-delay cancelled by the release. Nothing ever fired.
        verify(exactly = 0) { emitter.emitPress(any()) }
    }

    @Test
    fun fireStartDelay_heldPastDelay_firesAfterDelay() = testScope.runTest {
        compiledConfig.value = configWith(
            BUTTON_A to listOf(activatorWith(
                ActivatorType.FULL_PRESS, ENTER,
                fireStartDelayMs = 200L,
            )),
        )

        subject.handleDigital(BUTTON_A, isDown = true)
        advanceTimeBy(250L)
        runCurrent()
        verify(exactly = 1) { emitter.emitPress(ENTER) }

        subject.handleDigital(BUTTON_A, isDown = false)
        verify(exactly = 1) { emitter.emitRelease(ENTER) }
    }

    @Test
    fun fireEndDelay_keepsBindingActivePastUp() = testScope.runTest {
        compiledConfig.value = configWith(
            BUTTON_A to listOf(activatorWith(
                ActivatorType.FULL_PRESS, ENTER,
                fireEndDelayMs = 300L,
            )),
        )

        subject.handleDigital(BUTTON_A, isDown = true)
        verify(exactly = 1) { emitter.emitPress(ENTER) }

        subject.handleDigital(BUTTON_A, isDown = false)
        // Release deferred — emitter.emitRelease NOT called yet.
        verify(exactly = 0) { emitter.emitRelease(ENTER) }

        advanceTimeBy(350L)
        runCurrent()
        verify(exactly = 1) { emitter.emitRelease(ENTER) }
    }

    @Test
    fun cycleBindings_advancesIndexEachFire() = testScope.runTest {
        compiledConfig.value = configWith(
            BUTTON_A to listOf(
                CompiledActivator(
                    activatorId = 0L,
                    type = ActivatorType.START_PRESS,
                    bindings = listOf(ENTER, ESCAPE, SPACE),
                    settings = CompiledActivatorSettings(cycleBindings = true),
                )
            ),
        )

        subject.handleDigital(BUTTON_A, isDown = true)
        subject.handleDigital(BUTTON_A, isDown = false)
        subject.handleDigital(BUTTON_A, isDown = true)
        subject.handleDigital(BUTTON_A, isDown = false)
        subject.handleDigital(BUTTON_A, isDown = true)
        subject.handleDigital(BUTTON_A, isDown = false)
        subject.handleDigital(BUTTON_A, isDown = true)  // 4th: wraps back to ENTER
        subject.handleDigital(BUTTON_A, isDown = false)

        verifyOrder {
            emitter.emitPress(ENTER)
            emitter.emitPress(ESCAPE)
            emitter.emitPress(SPACE)
            emitter.emitPress(ENTER)
        }
    }

    @Test
    fun interruptable_false_regularFiresImmediately_alongsideDouble() = testScope.runTest {
        // When the Regular activator is interruptable=false, the DOUBLE_PRESS does NOT
        // suppress it. Both effectively coexist (Regular fires at DOWN like normal).
        compiledConfig.value = configWith(
            BUTTON_A to listOf(
                CompiledActivator(
                    activatorId = 1L,
                    type = ActivatorType.FULL_PRESS,
                    bindings = listOf(ENTER),
                    settings = CompiledActivatorSettings(interruptable = false),
                ),
                CompiledActivator(
                    activatorId = 2L,
                    type = ActivatorType.DOUBLE_PRESS,
                    bindings = listOf(SPACE),
                    settings = CompiledActivatorSettings(doubleTapTimeMs = 250L),
                ),
            ),
        )

        subject.handleDigital(BUTTON_A, isDown = true)
        // With interruptable=false, Regular fires immediately on DOWN.
        verify(exactly = 1) { emitter.emitPress(ENTER) }
        verify(exactly = 0) { emitter.emitPress(SPACE) }

        subject.handleDigital(BUTTON_A, isDown = false)
        verify(exactly = 1) { emitter.emitRelease(ENTER) }
        advanceTimeBy(300L)
        runCurrent()
        // Double's window still expires cleanly with nothing left to fire.
        verify(exactly = 0) { emitter.emitPress(SPACE) }
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

    // ── Action set switching (Brick 4.2) ──────────────────────────────────────

    private fun changePresetTo(setId: Long): BindingOutput.ControllerAction =
        BindingOutput.ControllerAction(verb = "CHANGE_PRESET", args = listOf(setId.toString(), "1", "1"))

    @Test
    fun activeSet_lazyInitializes_toDefault_onFirstPress() {
        compiledConfig.value = configWithTwoSets(
            defaultSetId = 1L,
            setA = 1L to listOf(BUTTON_A to activator(ActivatorType.FULL_PRESS, ENTER)),
            setB = 2L to listOf(BUTTON_A to activator(ActivatorType.FULL_PRESS, ESCAPE)),
        )

        subject.handleDigital(BUTTON_A, isDown = true)

        verify(exactly = 1) { emitter.emitPress(ENTER) }
        verify(exactly = 0) { emitter.emitPress(ESCAPE) }
        assertEquals(1L, subject.currentActiveSetId())
    }

    @Test
    fun changePreset_swapsActiveSet_andSubsequentPressesEmitFromNewSet() {
        // Set 1: BUTTON_A → CHANGE_PRESET(2). Set 2: BUTTON_A → ESCAPE.
        compiledConfig.value = configWithTwoSets(
            defaultSetId = 1L,
            setA = 1L to listOf(
                BUTTON_A to listOf(
                    CompiledActivator(
                        activatorId = 0L,
                        type = ActivatorType.FULL_PRESS,
                        bindings = listOf(changePresetTo(2L)),
                    )
                )
            ),
            setB = 2L to listOf(BUTTON_A to activator(ActivatorType.FULL_PRESS, ESCAPE)),
        )

        // Press + release BUTTON_A in set 1 — fires CHANGE_PRESET, swaps to set 2.
        subject.handleDigital(BUTTON_A, isDown = true)
        subject.handleDigital(BUTTON_A, isDown = false)
        assertEquals(2L, subject.currentActiveSetId())

        // Now BUTTON_A in set 2 should emit ESCAPE.
        subject.handleDigital(BUTTON_A, isDown = true)
        verify(exactly = 1) { emitter.emitPress(ESCAPE) }
        // CHANGE_PRESET itself never goes through the emitter.
        verify(exactly = 0) { emitter.emitPress(match { it is BindingOutput.ControllerAction }) }
    }

    @Test
    fun changePreset_invalidTarget_isNoOp() {
        compiledConfig.value = configWithTwoSets(
            defaultSetId = 1L,
            setA = 1L to listOf(
                BUTTON_A to listOf(
                    CompiledActivator(
                        activatorId = 0L,
                        type = ActivatorType.FULL_PRESS,
                        bindings = listOf(changePresetTo(999L)),  // not in compiled config
                    )
                )
            ),
            setB = 2L to listOf(BUTTON_A to activator(ActivatorType.FULL_PRESS, ESCAPE)),
        )

        subject.handleDigital(BUTTON_A, isDown = true)

        assertEquals("Invalid CHANGE_PRESET target must not change the active set",
            1L, subject.currentActiveSetId())
    }

    @Test
    fun changePreset_releasesHeldBindingsFromOldSet() {
        // Set 1: BUTTON_A → ENTER (FULL_PRESS), BUTTON_B → CHANGE_PRESET(2).
        // Press BUTTON_A (ENTER held), then press BUTTON_B (fires CHANGE_PRESET).
        // The swap should release ENTER even though BUTTON_A is still physically held.
        val BUTTON_B = InputAddress(InputSource.BUTTON_DIAMOND, "button_b")
        compiledConfig.value = configWithTwoSets(
            defaultSetId = 1L,
            setA = 1L to listOf(
                BUTTON_A to activator(ActivatorType.FULL_PRESS, ENTER),
                BUTTON_B to listOf(
                    CompiledActivator(
                        activatorId = 0L,
                        type = ActivatorType.FULL_PRESS,
                        bindings = listOf(changePresetTo(2L)),
                    )
                ),
            ),
            setB = 2L to listOf(BUTTON_A to activator(ActivatorType.FULL_PRESS, ESCAPE)),
        )

        subject.handleDigital(BUTTON_A, isDown = true)
        subject.handleDigital(BUTTON_B, isDown = true)

        verify(exactly = 1) { emitter.emitPress(ENTER) }
        verify(exactly = 1) { emitter.emitRelease(ENTER) }
        assertEquals(0, subject.heldAddressCount())
        assertEquals(2L, subject.currentActiveSetId())
    }

    @Test
    fun configChange_whichRemovesActiveSet_fallsBackToDefault_onNextEvent() {
        // Start in set 1; CHANGE_PRESET to set 2; then config swaps to a new config
        // that only has set 3. Next press should resolve via set 3 (the new default).
        compiledConfig.value = configWithTwoSets(
            defaultSetId = 1L,
            setA = 1L to listOf(
                BUTTON_A to listOf(
                    CompiledActivator(
                        activatorId = 0L,
                        type = ActivatorType.FULL_PRESS,
                        bindings = listOf(changePresetTo(2L)),
                    )
                )
            ),
            setB = 2L to listOf(BUTTON_A to activator(ActivatorType.FULL_PRESS, ESCAPE)),
        )
        subject.handleDigital(BUTTON_A, isDown = true)
        subject.handleDigital(BUTTON_A, isDown = false)
        assertEquals(2L, subject.currentActiveSetId())

        // User deletes both sets via the editor; new config has only set 3.
        compiledConfig.value = configWithSet(
            setId = 3L,
            BUTTON_A to activator(ActivatorType.FULL_PRESS, SPACE),
        )

        subject.handleDigital(BUTTON_A, isDown = true)
        verify(exactly = 1) { emitter.emitPress(SPACE) }
        assertEquals(3L, subject.currentActiveSetId())
    }

    @Test
    fun changePreset_clearsToggleLatchFromOldSet() {
        // Set 1: BUTTON_A → ENTER (toggle=true), BUTTON_B → CHANGE_PRESET(2).
        // Press BUTTON_A → toggle on (ENTER latched). Press BUTTON_B → swap. Toggle should
        // release ENTER (not stay latched in the new set).
        val BUTTON_B = InputAddress(InputSource.BUTTON_DIAMOND, "button_b")
        val toggleActivator = activatorWith(ActivatorType.FULL_PRESS, ENTER, toggle = true)
        compiledConfig.value = configWithTwoSets(
            defaultSetId = 1L,
            setA = 1L to listOf(
                BUTTON_A to listOf(toggleActivator),
                BUTTON_B to listOf(
                    CompiledActivator(
                        activatorId = 0L,
                        type = ActivatorType.FULL_PRESS,
                        bindings = listOf(changePresetTo(2L)),
                    )
                ),
            ),
            setB = 2L to listOf(BUTTON_A to activator(ActivatorType.FULL_PRESS, ESCAPE)),
        )

        subject.handleDigital(BUTTON_A, isDown = true)
        subject.handleDigital(BUTTON_A, isDown = false)  // latched on; physical release does nothing
        assertTrue(subject.isToggledOn(toggleActivator.activatorId))

        subject.handleDigital(BUTTON_B, isDown = true)

        verify(exactly = 1) { emitter.emitRelease(ENTER) }
        assertFalse(
            "Toggle latch from the old set must be cleared after CHANGE_PRESET",
            subject.isToggledOn(toggleActivator.activatorId),
        )
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun activator(
        type: ActivatorType,
        vararg bindings: BindingOutput,
        longPressTimeMs: Long = CompiledActivatorSettings.DEFAULT_LONG_PRESS_TIME_MS,
        doubleTapTimeMs: Long = CompiledActivatorSettings.DEFAULT_DOUBLE_TAP_TIME_MS,
    ) = listOf(
        CompiledActivator(
            activatorId = 0L,
            type = type,
            bindings = bindings.toList(),
            settings = CompiledActivatorSettings(
                longPressTimeMs = longPressTimeMs,
                doubleTapTimeMs = doubleTapTimeMs,
            ),
        ),
    )

    /** 3.3 helper: a single CHORDED_PRESS activator with a configured partner. */
    private fun chordActivator(binding: BindingOutput, partner: InputAddress): CompiledActivator =
        CompiledActivator(
            activatorId = 0L,
            type = ActivatorType.CHORDED_PRESS,
            bindings = listOf(binding),
            settings = CompiledActivatorSettings(
                chordPartnerSource = partner.source,
                chordPartnerKey = partner.inputKey,
            ),
        )

    /**
     * 3.3 helper: single-activator config with a chosen universal setting toggled on.
     * Other settings stay at default so each test exercises exactly one knob.
     */
    private fun activatorWith(
        type: ActivatorType,
        binding: BindingOutput,
        toggle: Boolean = false,
        holdToRepeat: Boolean = false,
        repeatRateMs: Long = 150L,
        fireStartDelayMs: Long = 0L,
        fireEndDelayMs: Long = 0L,
        cycleBindings: Boolean = false,
    ): CompiledActivator = CompiledActivator(
        activatorId = 0L,
        type = type,
        bindings = listOf(binding),
        settings = CompiledActivatorSettings(
            toggle = toggle,
            holdToRepeat = holdToRepeat,
            repeatRateMs = repeatRateMs,
            fireStartDelayMs = fireStartDelayMs,
            fireEndDelayMs = fireEndDelayMs,
            cycleBindings = cycleBindings,
        ),
    )

    private fun configWith(vararg entries: Pair<InputAddress, List<CompiledActivator>>): CompiledConfig =
        configWithSet(setId = 1L, *entries)

    /**
     * Build a [CompiledConfig] with a single action set at [setId], holding the
     * supplied address→activators mapping. [setId] becomes the snapshot's
     * `defaultActionSetId`, so evaluator lookups land on these inputs by default.
     */
    private fun configWithSet(
        setId: Long,
        vararg entries: Pair<InputAddress, List<CompiledActivator>>,
    ): CompiledConfig {
        val inputs = entries.associate { (addr, activators) ->
            addr to CompiledInput(groupInputId = 0L, activators = activators)
        }
        return CompiledConfig(
            defaultActionSetId = setId,
            sets = mapOf(setId to CompiledActionSet(setId, inputs)),
        )
    }

    /**
     * Build a [CompiledConfig] with two action sets, both populated. [defaultSetId]
     * picks which becomes the snapshot's default. Used by Brick 4.2 set-switching tests.
     */
    private fun configWithTwoSets(
        defaultSetId: Long,
        setA: Pair<Long, List<Pair<InputAddress, List<CompiledActivator>>>>,
        setB: Pair<Long, List<Pair<InputAddress, List<CompiledActivator>>>>,
    ): CompiledConfig {
        fun build(setId: Long, entries: List<Pair<InputAddress, List<CompiledActivator>>>) =
            CompiledActionSet(
                setId,
                entries.associate { (addr, activators) ->
                    addr to CompiledInput(groupInputId = 0L, activators = activators)
                },
            )
        return CompiledConfig(
            defaultActionSetId = defaultSetId,
            sets = mapOf(
                setA.first to build(setA.first, setA.second),
                setB.first to build(setB.first, setB.second),
            ),
        )
    }
}
