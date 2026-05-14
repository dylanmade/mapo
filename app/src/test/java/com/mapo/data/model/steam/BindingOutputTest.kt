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

    // ── Brick 4.5: encode/decode for picker round-trip ────────────────────────

    @Test
    fun encode_thenDecode_roundTripsEveryVariant() {
        val cases = listOf(
            BindingOutput.Unbound,
            BindingOutput.KeyPress("ENTER"),
            BindingOutput.XInputButton("BUTTON_A"),
            BindingOutput.MouseButton("MOUSE_LEFT"),
            BindingOutput.MouseWheel("SCROLL_UP"),
            BindingOutput.GameAction("Gameplay", "Jump"),
            BindingOutput.ControllerAction("CHANGE_PRESET", listOf("42")),
            BindingOutput.ModeShift(InputSource.RIGHT_TRACKPAD, 42L),
        )

        for (original in cases) {
            val encoded = original.encode()
            val decoded = BindingOutput.decode(encoded)
            assertEquals("Encode/decode failed for $original (encoded='$encoded')", original, decoded)
        }
    }

    @Test
    fun decode_unknownDiscriminator_fallsBackToUnbound() {
        assertEquals(BindingOutput.Unbound, BindingOutput.decode("NOT_A_TYPE|whatever"))
    }

    @Test
    fun decode_malformedInput_fallsBackToUnbound() {
        // No pipe → can't distinguish type from args; defensive fallback.
        assertEquals(BindingOutput.Unbound, BindingOutput.decode("garbage"))
    }

    @Test
    fun changePresetDisplayLabel_resolvesSetTitleFromConfig() {
        val output = BindingOutput.ControllerAction("CHANGE_PRESET", listOf("20"))
        val config = ControllerConfig(
            controllerProfile = ControllerProfile(
                id = 1L, profileId = 1L,
                controllerType = ControllerType.GENERIC_ANDROID, name = "Default",
            ),
            actionSets = listOf(
                ActionSetGraph(
                    actionSet = ActionSet(id = 10L, controllerProfileId = 1L, name = "gameplay", title = "Gameplay"),
                    layers = emptyList(), preset = emptyList(),
                ),
                ActionSetGraph(
                    actionSet = ActionSet(id = 20L, controllerProfileId = 1L, name = "menu", title = "Menu"),
                    layers = emptyList(), preset = emptyList(),
                ),
            ),
        )

        assertEquals("Switch to: Menu", output.displayLabel(config))
    }

    @Test
    fun changePresetDisplayLabel_fallsBackToSetIdWhenNotInConfig() {
        val output = BindingOutput.ControllerAction("CHANGE_PRESET", listOf("999"))
        val config = ControllerConfig(
            controllerProfile = ControllerProfile(
                id = 1L, profileId = 1L,
                controllerType = ControllerType.GENERIC_ANDROID, name = "Default",
            ),
            actionSets = emptyList(),
        )

        // Set not in config → fallback to numeric form (stale binding, e.g. after delete).
        assertEquals("Switch to: Set #999", output.displayLabel(config))
        assertEquals("Switch to: Set #999", output.displayLabel(null))
    }

    @Test
    fun controllerAction_nonChangePreset_keepsVerbLabel() {
        // Future verbs (add_layer, remove_layer) shouldn't display as "Switch to: ...".
        val output = BindingOutput.ControllerAction("add_layer", listOf("3"))
        assertEquals("Verb: add_layer", output.displayLabel())
    }
}
