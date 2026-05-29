package com.mapo.service.input

import com.mapo.data.model.RemapTarget
import com.mapo.data.model.steam.BindingOutput
import com.mapo.data.model.steam.InputSource
import io.mockk.confirmVerified
import io.mockk.mockk
import io.mockk.verify
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class OutputEmitterTest {

    private lateinit var dispatcher: InputDispatcher
    private lateinit var subject: OutputEmitter

    @Before
    fun setUp() {
        dispatcher = mockk(relaxed = true)
        subject = OutputEmitter(dispatcher)
    }

    // ── Press: held outputs (return true) ─────────────────────────────────────

    @Test
    fun pressKeyPress_callsInjectKeyDown_returnsTrue() {
        val held = subject.emitPress(BindingOutput.KeyPress("ENTER"))

        assertTrue("KeyPress has a matching release edge", held)
        verify(exactly = 1) { dispatcher.injectKeyDown("ENTER") }
    }

    @Test
    fun pressXInputButton_callsInjectKeyDown_returnsTrue() {
        val held = subject.emitPress(BindingOutput.XInputButton("BUTTON_A"))

        assertTrue("XInputButton has a matching release edge", held)
        verify(exactly = 1) { dispatcher.injectKeyDown("BUTTON_A") }
    }

    // ── Press: fire-and-done outputs (return false) ───────────────────────────

    @Test
    fun pressMouseButton_dispatchesAsClick_returnsFalse() {
        val held = subject.emitPress(BindingOutput.MouseButton("MOUSE_LEFT"))

        assertFalse("Mouse click is fire-and-done; no release tracking", held)
        verify(exactly = 1) { dispatcher.dispatchTargetAsClick(RemapTarget.Mouse("MOUSE_LEFT")) }
    }

    @Test
    fun pressMouseWheel_dispatchesAsClick_returnsFalse() {
        val held = subject.emitPress(BindingOutput.MouseWheel("SCROLL_DOWN"))

        assertFalse(held)
        verify(exactly = 1) { dispatcher.dispatchTargetAsClick(RemapTarget.Mouse("SCROLL_DOWN")) }
    }

    @Test
    fun pressUnbound_returnsFalse_callsNothing() {
        val held = subject.emitPress(BindingOutput.Unbound)

        assertFalse(held)
        confirmVerified(dispatcher)
    }

    // ── Press: stub outputs (log and drop) ────────────────────────────────────

    @Test
    fun pressGameAction_returnsFalse_callsNothing() {
        val held = subject.emitPress(BindingOutput.GameAction(setName = "default", actionName = "jump"))

        assertFalse("GameAction is a Phase 4 stub — no injection", held)
        confirmVerified(dispatcher)
    }

    @Test
    fun pressControllerAction_returnsFalse_callsNothing() {
        val held = subject.emitPress(BindingOutput.ControllerAction(verb = "CHANGE_PRESET", args = listOf("2")))

        assertFalse(held)
        confirmVerified(dispatcher)
    }

    // ── Release ───────────────────────────────────────────────────────────────

    @Test
    fun releaseKeyPress_callsInjectKeyUp() {
        subject.emitRelease(BindingOutput.KeyPress("ENTER"))
        verify(exactly = 1) { dispatcher.injectKeyUp("ENTER") }
    }

    @Test
    fun releaseXInputButton_callsInjectKeyUp() {
        subject.emitRelease(BindingOutput.XInputButton("BUTTON_A"))
        verify(exactly = 1) { dispatcher.injectKeyUp("BUTTON_A") }
    }

    @Test
    fun releaseMouseButton_isNoOp() {
        // Mouse clicks are fire-and-done at press time; release has nothing to do.
        subject.emitRelease(BindingOutput.MouseButton("MOUSE_LEFT"))
        confirmVerified(dispatcher)
    }

    @Test
    fun releaseUnbound_isNoOp() {
        subject.emitRelease(BindingOutput.Unbound)
        confirmVerified(dispatcher)
    }
}
