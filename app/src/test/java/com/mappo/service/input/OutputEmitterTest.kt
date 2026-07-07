package com.mappo.service.input

import com.mappo.data.model.RemapTarget
import com.mappo.data.model.steam.BindingOutput
import com.mappo.data.model.steam.InputSource
import io.mockk.confirmVerified
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class OutputEmitterTest {

    private lateinit var dispatcher: InputDispatcher
    private lateinit var gamepad: com.mappo.service.shizuku.ShizukuGamepadInjector
    private lateinit var subject: OutputEmitter

    @Before
    fun setUp() {
        dispatcher = mockk(relaxed = true)
        gamepad = mockk(relaxed = true)
        subject = OutputEmitter(dispatcher, gamepad)
    }

    // ── Press: held outputs (return true) ─────────────────────────────────────

    @Test
    fun pressKeyPress_callsInjectKeyDown_returnsTrue() {
        val held = subject.emitPress(BindingOutput.KeyPress("ENTER"))

        assertTrue("KeyPress has a matching release edge", held)
        verify(exactly = 1) { dispatcher.injectKeyDown("ENTER") }
    }

    @Test
    fun pressXInputButton_routesToVirtualGamepad_whenMvgAvailable() {
        // MVG ready → "gamepad B" must drive the real virtual-gamepad button
        // (BTN_B = 0x131), NOT a SOURCE_KEYBOARD key event (which GameNative read
        // as a menu/back button instead of in-game gamepad B).
        every { gamepad.setButton(0x131, true) } returns true
        val held = subject.emitPress(BindingOutput.XInputButton("BUTTON_B"))

        assertTrue("XInputButton has a matching release edge", held)
        verify(exactly = 1) { gamepad.setButton(0x131, true) }
        verify(exactly = 0) { dispatcher.injectKeyDown(any()) }
    }

    @Test
    fun pressXInputButton_fallsBackToKeyInject_whenMvgUnavailable() {
        // No Shizuku → setButton returns false → fall back to key inject so digital
        // remap still does something.
        every { gamepad.setButton(any(), any()) } returns false
        val held = subject.emitPress(BindingOutput.XInputButton("BUTTON_A"))

        assertTrue(held)
        verify(exactly = 1) { dispatcher.injectKeyDown("BUTTON_A") }
    }

    @Test
    fun pressXInputStick_emitsNetAxis_upIsNegativeY_returnsTrue() {
        val held = subject.emitPress(BindingOutput.XInputStick("LEFT", "UP"))
        assertTrue("XInputStick is held (released on up)", held)
        // +x right, +y down → UP is (0, -1).
        verify { gamepad.setLeftStickOutput(0f, -1f) }
    }

    @Test
    fun xInputStick_accumulatesDiagonal_andReleasesBackToCenter() {
        subject.emitPress(BindingOutput.XInputStick("LEFT", "UP"))
        subject.emitPress(BindingOutput.XInputStick("LEFT", "RIGHT"))
        verify { gamepad.setLeftStickOutput(1f, -1f) } // up-right diagonal
        subject.emitRelease(BindingOutput.XInputStick("LEFT", "UP"))
        verify { gamepad.setLeftStickOutput(1f, 0f) } // only right remains
        subject.emitRelease(BindingOutput.XInputStick("LEFT", "RIGHT"))
        verify { gamepad.setLeftStickOutput(0f, 0f) } // centered
    }

    @Test
    fun pressXInputButton_axisL2_drivesAnalogTriggerNotDigitalButton() {
        // AXIS_L2/R2 are the analog triggers (ABS_Z/RZ). A digital BTN_TL2/TR2 press is
        // invisible to games reading the trigger axis, so these must drive the trigger
        // OUTPUT to full — not setButton.
        val held = subject.emitPress(BindingOutput.XInputButton("AXIS_L2"))
        assertTrue(held)
        verify(exactly = 1) { gamepad.setLeftTriggerOutput(1f) }
        verify(exactly = 0) { gamepad.setButton(any(), any()) }
        verify(exactly = 0) { dispatcher.injectKeyDown(any()) }
    }

    @Test
    fun xInputButton_axisR2_releasesTriggerBackToZero() {
        subject.emitPress(BindingOutput.XInputButton("AXIS_R2"))
        verify { gamepad.setRightTriggerOutput(1f) }
        subject.emitRelease(BindingOutput.XInputButton("AXIS_R2"))
        verify { gamepad.setRightTriggerOutput(0f) }
    }

    @Test
    fun pressXInputButton_dpadUp_drivesHatNotButton() {
        // DPAD_* are the hat axis (ABS_HAT0X/Y), not buttons. Up = hat (0, -1).
        val held = subject.emitPress(BindingOutput.XInputButton("DPAD_UP"))
        assertTrue(held)
        verify(exactly = 1) { gamepad.setHatOutput(0, -1) }
        verify(exactly = 0) { gamepad.setButton(any(), any()) }
        verify(exactly = 0) { dispatcher.injectKeyDown(any()) }
    }

    @Test
    fun xInputButton_dpad_accumulatesDiagonal_andReleasesToCenter() {
        subject.emitPress(BindingOutput.XInputButton("DPAD_UP"))
        subject.emitPress(BindingOutput.XInputButton("DPAD_RIGHT"))
        verify { gamepad.setHatOutput(1, -1) } // up-right
        subject.emitRelease(BindingOutput.XInputButton("DPAD_UP"))
        verify { gamepad.setHatOutput(1, 0) } // only right
        subject.emitRelease(BindingOutput.XInputButton("DPAD_RIGHT"))
        verify { gamepad.setHatOutput(0, 0) } // centered
    }

    @Test
    fun xInputStick_opposingDirectionsCancel() {
        subject.emitPress(BindingOutput.XInputStick("RIGHT", "UP"))
        subject.emitPress(BindingOutput.XInputStick("RIGHT", "DOWN"))
        verify { gamepad.setRightStickOutput(0f, 0f) } // up + down → neutral Y
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
    fun releaseXInputButton_routesToVirtualGamepad_whenMvgAvailable() {
        every { gamepad.setButton(0x131, false) } returns true
        subject.emitRelease(BindingOutput.XInputButton("BUTTON_B"))
        verify(exactly = 1) { gamepad.setButton(0x131, false) }
        verify(exactly = 0) { dispatcher.injectKeyUp(any()) }
    }

    @Test
    fun releaseXInputButton_fallsBackToKeyInject_whenMvgUnavailable() {
        every { gamepad.setButton(any(), any()) } returns false
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
