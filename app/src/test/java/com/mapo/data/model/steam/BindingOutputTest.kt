package com.mapo.data.model.steam

import com.mapo.data.model.RemapTarget
import org.junit.Assert.assertEquals
import org.junit.Test

class BindingOutputTest {

    @Test
    fun toEntity_thenFromEntity_roundTripsEveryVariant() {
        val cases = listOf(
            BindingOutput.Unbound,
            BindingOutput.KeyPress("ENTER"),
            BindingOutput.XInputButton("BUTTON_A"),
            BindingOutput.MouseButton("MOUSE_LEFT"),
            BindingOutput.MouseWheel("SCROLL_UP"),
            BindingOutput.GameAction("Gameplay", "Jump"),
            BindingOutput.ControllerAction("CHANGE_PRESET", listOf("1", "1")),
            BindingOutput.ModeShift(InputSource.RIGHT_TRACKPAD, 42L),
        )

        for (original in cases) {
            val (type, args) = original.toEntity()
            val roundTripped = BindingOutput.fromEntity(type, args)
            assertEquals("Round-trip failed for $original", original, roundTripped)
        }
    }

    @Test
    fun fromRemapTarget_unbound_mapsToUnbound() {
        assertEquals(BindingOutput.Unbound, BindingOutput.fromRemapTarget(RemapTarget.Unbound))
    }

    @Test
    fun fromRemapTarget_keyboard_mapsToKeyPress() {
        assertEquals(
            BindingOutput.KeyPress("ESCAPE"),
            BindingOutput.fromRemapTarget(RemapTarget.Keyboard("ESCAPE")),
        )
    }

    @Test
    fun fromRemapTarget_mouseClick_mapsToMouseButton() {
        assertEquals(
            BindingOutput.MouseButton("MOUSE_LEFT"),
            BindingOutput.fromRemapTarget(RemapTarget.Mouse("MOUSE_LEFT")),
        )
    }

    @Test
    fun fromRemapTarget_mouseScroll_mapsToMouseWheel() {
        assertEquals(
            BindingOutput.MouseWheel("SCROLL_UP"),
            BindingOutput.fromRemapTarget(RemapTarget.Mouse("SCROLL_UP")),
        )
        assertEquals(
            BindingOutput.MouseWheel("SCROLL_DOWN"),
            BindingOutput.fromRemapTarget(RemapTarget.Mouse("SCROLL_DOWN")),
        )
    }

    @Test
    fun fromRemapTarget_gamepad_mapsToXInputButton() {
        assertEquals(
            BindingOutput.XInputButton("BUTTON_A"),
            BindingOutput.fromRemapTarget(RemapTarget.Gamepad("BUTTON_A")),
        )
    }
}
