package com.mappo.data.model.steam

import com.mappo.data.model.RemapTarget
import org.junit.Assert.assertEquals
import org.junit.Test

class BindingOutputStickTest {

    @Test
    fun xInputStick_entityRoundTrips() {
        val o = BindingOutput.XInputStick("LEFT", "UP")
        val (type, args) = o.toEntity()
        assertEquals(BindingOutputType.XINPUT_STICK, type)
        assertEquals(o, BindingOutput.fromEntity(type, args))
        assertEquals(o, BindingOutput.decode(o.encode()))
    }

    @Test
    fun gamepadToken_bridgesToXInputStick() {
        // Stick tokens parse to XInputStick; plain button names stay XInputButton.
        assertEquals(
            BindingOutput.XInputStick("RIGHT", "LEFT"),
            BindingOutput.fromRemapTarget(RemapTarget.Gamepad("RSTICK_LEFT")),
        )
        assertEquals(
            BindingOutput.XInputButton("BUTTON_A"),
            BindingOutput.fromRemapTarget(RemapTarget.Gamepad("BUTTON_A")),
        )
    }

    @Test
    fun xInputStick_roundTripsThroughRemapTarget() {
        val o = BindingOutput.XInputStick("LEFT", "RIGHT")
        assertEquals(o, BindingOutput.fromRemapTarget(o.toRemapTarget()))
    }

    @Test
    fun displayLabel_isHumanReadable() {
        assertEquals("GP: Left Stick Up", BindingOutput.XInputStick("LEFT", "UP").displayLabel())
        assertEquals("GP: Right Stick Down", BindingOutput.XInputStick("RIGHT", "DOWN").displayLabel())
    }
}
