package com.mappo.data.model

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * RemapTarget serialization round-trips through SQLite and config snapshots —
 * any drift between encode/decode is a data-loss hazard. Pure Kotlin, no
 * Android framework needed.
 */
class RemapTargetTest {

    @Test
    fun encode_unbound_emitsNone() {
        assertEquals("none", RemapTarget.Unbound.encode())
    }

    @Test
    fun encode_gamepad_prefixes() {
        assertEquals("gamepad:BUTTON_A", RemapTarget.Gamepad("BUTTON_A").encode())
    }

    @Test
    fun encode_keyboard_prefixes() {
        assertEquals("keyboard:ENTER", RemapTarget.Keyboard("ENTER").encode())
    }

    @Test
    fun encode_mouse_prefixes() {
        assertEquals("mouse:LEFT", RemapTarget.Mouse("LEFT").encode())
    }

    @Test
    fun decode_none_returnsUnbound() {
        assertEquals(RemapTarget.Unbound, RemapTarget.decode("none"))
    }

    @Test
    fun decode_gamepadPrefix_returnsGamepad() {
        assertEquals(RemapTarget.Gamepad("BUTTON_A"), RemapTarget.decode("gamepad:BUTTON_A"))
    }

    @Test
    fun decode_keyboardPrefix_returnsKeyboard() {
        assertEquals(RemapTarget.Keyboard("ENTER"), RemapTarget.decode("keyboard:ENTER"))
    }

    @Test
    fun decode_mousePrefix_returnsMouse() {
        assertEquals(RemapTarget.Mouse("LEFT"), RemapTarget.decode("mouse:LEFT"))
    }

    @Test
    fun decode_unknownFormat_fallsBackToUnbound() {
        assertEquals(RemapTarget.Unbound, RemapTarget.decode(""))
        assertEquals(RemapTarget.Unbound, RemapTarget.decode("garbage"))
        assertEquals(RemapTarget.Unbound, RemapTarget.decode("invalid:value"))
    }

    @Test
    fun roundTrip_preservesEachVariant() {
        val cases = listOf(
            RemapTarget.Unbound,
            RemapTarget.Gamepad("BUTTON_X"),
            RemapTarget.Keyboard("SPACE"),
            RemapTarget.Mouse("RIGHT"),
        )
        cases.forEach { target ->
            assertEquals(target, RemapTarget.decode(target.encode()))
        }
    }

    @Test
    fun decode_emptyValueAfterPrefix_preservesEmptyString() {
        // No specific guarantee documented for empty-payload encoding; we just
        // pin current behavior so changes are deliberate.
        assertEquals(RemapTarget.Keyboard(""), RemapTarget.decode("keyboard:"))
        assertEquals(RemapTarget.Gamepad(""), RemapTarget.decode("gamepad:"))
    }

    @Test
    fun displayLabel_eachVariant() {
        assertEquals("(Device default)", RemapTarget.Unbound.displayLabel())
        assertEquals("GP: BUTTON_A", RemapTarget.Gamepad("BUTTON_A").displayLabel())
        assertEquals("KB: ENTER", RemapTarget.Keyboard("ENTER").displayLabel())
        assertEquals("MS: LEFT", RemapTarget.Mouse("LEFT").displayLabel())
    }
}
