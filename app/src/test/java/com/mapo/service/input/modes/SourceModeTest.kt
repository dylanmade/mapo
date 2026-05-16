package com.mapo.service.input.modes

import com.mapo.data.model.steam.BindingMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Brick 6.1: contract tests for the [SourceMode] runtime handlers. Only the two
 * implemented modes (Single Button, Button Pad) and the [StubMode] fall-through path
 * exist this brick — these tests guard the registry shape so a later brick adding
 * (say) `DpadMode` knows to slot it in via `BindingMode.handler()`.
 */
class SourceModeTest {

    @Test
    fun singleButton_validInputs_isJustClick() {
        assertEquals(setOf("click"), SingleButtonMode.validInputs())
    }

    @Test
    fun singleButton_acceptsClick_rejectsAnythingElse() {
        assertTrue(SingleButtonMode.accepts("click"))
        assertFalse(SingleButtonMode.accepts("edge"))
        assertFalse(SingleButtonMode.accepts("button_a"))
        assertFalse(SingleButtonMode.accepts(""))
    }

    @Test
    fun buttonPad_validInputs_areTheFourFaceButtons() {
        assertEquals(
            setOf("button_a", "button_b", "button_x", "button_y"),
            ButtonPadMode.validInputs(),
        )
    }

    @Test
    fun buttonPad_rejectsDpadAndClickKeys() {
        // The face-button cluster has no shared `click` sub-input — each face button
        // is its own addressable key.
        assertTrue(ButtonPadMode.accepts("button_a"))
        assertTrue(ButtonPadMode.accepts("button_y"))
        assertFalse(ButtonPadMode.accepts("click"))
        assertFalse(ButtonPadMode.accepts("dpad_north"))
    }

    @Test
    fun defaultSettings_isEmptyJsonForDigitalModes() {
        // Single Button and Button Pad are purely digital — no deadzones / curves /
        // sensitivities to seed. Analog modes (6.3+) will override this.
        assertEquals("{}", SingleButtonMode.defaultSettingsJson())
        assertEquals("{}", ButtonPadMode.defaultSettingsJson())
    }

    @Test
    fun stubMode_acceptsAnyInputKey() {
        // Forward-compat: modes whose runtime hasn't landed (DPAD, TRIGGER, etc.)
        // should not have their existing seeded data dropped by Brick 6.1's compile
        // validation. StubMode is the permissive escape hatch until 6.2+.
        val stub = StubMode(BindingMode.DPAD)
        assertTrue(stub.accepts("dpad_north"))
        assertTrue(stub.accepts("anything_goes"))
        assertTrue(stub.accepts(""))
        assertEquals(emptySet<String>(), stub.validInputs())
    }

    @Test
    fun handlerRegistry_returnsTheRightSingletonForImplementedModes() {
        // Singleton identity matters: the evaluator may use object identity to short-
        // circuit per-mode setup someday.
        assertSame(SingleButtonMode, BindingMode.SINGLE_BUTTON.handler())
        assertSame(ButtonPadMode, BindingMode.BUTTON_PAD.handler())
    }

    @Test
    fun handlerRegistry_returnsStubForUnimplementedModes() {
        for (mode in BindingMode.values()) {
            if (mode == BindingMode.SINGLE_BUTTON || mode == BindingMode.BUTTON_PAD) continue
            val handler = mode.handler()
            assertTrue(
                "Expected StubMode for $mode until its runtime lands (got $handler)",
                handler is StubMode,
            )
            assertEquals(mode, handler.mode)
        }
    }
}
