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
        assertFalse(ButtonPadMode.accepts("dpad_up"))
    }

    @Test
    fun defaultSettings_isEmptyJsonForDigitalModes() {
        // Single Button and Button Pad are purely digital — no deadzones / curves /
        // sensitivities to seed.
        assertEquals("{}", SingleButtonMode.defaultSettingsJson())
        assertEquals("{}", ButtonPadMode.defaultSettingsJson())
    }

    // ── Brick 6.3: Dpad mode ─────────────────────────────────────────────────

    @Test
    fun dpad_validInputs_areFourDirectionsPlusClick() {
        // Steam Input Dpad mode exposes N/S/E/W direction sub-inputs plus stick-click.
        // The four direction keys come from physical KEYCODE_DPAD_* via the accessibility
        // service; click is reserved for analog-stick-as-dpad use (inert until motion
        // capture lands).
        assertEquals(
            setOf("dpad_up", "dpad_down", "dpad_right", "dpad_left", "click"),
            DpadMode.validInputs(),
        )
    }

    @Test
    fun dpad_acceptsItsKeys_rejectsOthers() {
        assertTrue(DpadMode.accepts("dpad_up"))
        assertTrue(DpadMode.accepts("dpad_down"))
        assertTrue(DpadMode.accepts("dpad_right"))
        assertTrue(DpadMode.accepts("dpad_left"))
        assertTrue(DpadMode.accepts("click"))
        assertFalse(DpadMode.accepts("button_a"))
        assertFalse(DpadMode.accepts("edge"))
        assertFalse(DpadMode.accepts(""))
    }

    @Test
    fun dpad_defaultSettingsJson_includesFourWayLayout() {
        // Brick K added analog-evaluate to DpadMode (stick-as-dpad), so the
        // defaults now carry inner_deadzone / outer_deadzone alongside the
        // original dpad_layout. The 4_way default is the most common choice
        // and the historical Steam default.
        val json = DpadMode.defaultSettingsJson()
        assertTrue(
            "Expected dpad_layout key in defaults; got: $json",
            json.contains("dpad_layout"),
        )
        assertTrue(
            "Expected 4_way default value; got: $json",
            json.contains("4_way"),
        )
        assertTrue(
            "Expected inner_deadzone key in defaults; got: $json",
            json.contains("inner_deadzone"),
        )
    }

    @Test
    fun handlerRegistry_returnsDpadModeForDpad() {
        assertSame(DpadMode, BindingMode.DPAD.handler())
    }

    // ── Brick 6.4: Trigger mode ──────────────────────────────────────────────

    @Test
    fun trigger_validInputs_areFullPullAndSoftPull() {
        // Phase 7 Brick A: sub-inputs renamed to Steam-verbatim. Triggers carry
        // "full_pull" (hardware threshold) and "soft_pull" (analog soft-pull, via
        // TriggerMode.evaluate's hysteresis edge detection). Both are bindable
        // rows in the UI (L2/R2 Full Pull, L2/R2 Soft Pull).
        assertEquals(setOf("full_pull", "soft_pull"), TriggerMode.validInputs())
    }

    @Test
    fun trigger_acceptsFullPullAndSoftPull_rejectsOthers() {
        assertTrue(TriggerMode.accepts("full_pull"))
        assertTrue(TriggerMode.accepts("soft_pull"))
        assertFalse(TriggerMode.accepts("click"))  // "click" no longer valid on trigger source post-rename
        assertFalse(TriggerMode.accepts("edge"))
        assertFalse(TriggerMode.accepts("button_a"))
        assertFalse(TriggerMode.accepts("dpad_up"))
    }

    @Test
    fun trigger_defaultSettingsJson_includesClickThreshold() {
        // Steam-default click threshold (0.95 of pull). Inert in 6.4 because we have
        // no analog source feeding the comparison; laid down so the eventual motion-
        // capture refactor doesn't need a settings-shape migration.
        val json = TriggerMode.defaultSettingsJson()
        assertTrue(
            "Expected click_threshold key in defaults; got: $json",
            json.contains("click_threshold"),
        )
        assertTrue(
            "Expected 0.95 default value; got: $json",
            json.contains("0.95"),
        )
    }

    @Test
    fun handlerRegistry_returnsTriggerModeForTrigger() {
        assertSame(TriggerMode, BindingMode.TRIGGER.handler())
    }

    @Test
    fun stubMode_acceptsAnyInputKey() {
        // Forward-compat: modes whose runtime hasn't landed (DPAD, TRIGGER, etc.)
        // should not have their existing seeded data dropped by Brick 6.1's compile
        // validation. StubMode is the permissive escape hatch until 6.2+.
        val stub = StubMode(BindingMode.DPAD)
        assertTrue(stub.accepts("dpad_up"))
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
        val implemented = setOf(
            BindingMode.DEVICE_DEFAULT,
            BindingMode.NONE,
            BindingMode.SINGLE_BUTTON,
            BindingMode.BUTTON_PAD,
            BindingMode.DPAD,
            BindingMode.TRIGGER,
            BindingMode.JOYSTICK_MOUSE,
        )
        for (mode in BindingMode.values()) {
            if (mode in implemented) continue
            val handler = mode.handler()
            assertTrue(
                "Expected StubMode for $mode until its runtime lands (got $handler)",
                handler is StubMode,
            )
            assertEquals(mode, handler.mode)
        }
    }

    // ── Unbound mode (post-Brick-4 follow-up) ────────────────────────────────

    @Test
    fun unbound_acceptsNothing_andHasNoValidInputs() {
        // UNBOUND means "Mapo does not intercept this source." Compile drops any
        // sub-inputs registered under a binding group in this mode, so the
        // activator engine never sees a configured input and physical events
        // pass through.
        assertEquals(emptySet<String>(), DeviceDefaultMode.validInputs())
        assertFalse(DeviceDefaultMode.accepts("click"))
        assertFalse(DeviceDefaultMode.accepts("dpad_up"))
        assertFalse(DeviceDefaultMode.accepts("button_a"))
        assertFalse(DeviceDefaultMode.accepts(""))
    }

    @Test
    fun unbound_isNotInMotionCaptureSet() {
        // Critical contract — defaulting analog sources to UNBOUND only achieves
        // the "no Shizuku enumeration / no setup-friction on a fresh profile"
        // goal if the gating predicate doesn't treat UNBOUND as analog.
        assertFalse(BindingMode.DEVICE_DEFAULT.requiresMotionCapture())
    }

    @Test
    fun handlerRegistry_returnsDeviceDefaultModeForUnbound() {
        assertSame(DeviceDefaultMode, BindingMode.DEVICE_DEFAULT.handler())
    }
}
