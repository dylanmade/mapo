package com.mapo.data.io.vdf

import com.mapo.data.model.steam.ActivatorType
import com.mapo.data.model.steam.BindingMode
import com.mapo.data.model.steam.ControllerType
import com.mapo.data.model.steam.InputSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Token-table tests — the audit surface for every VDF→Mapo mapping decision. */
class VdfMappingsTest {

    @Test
    fun controllerTypes() {
        assertEquals(ControllerType.STEAM_DECK, VdfMappings.controllerType("controller_neptune"))
        assertEquals(ControllerType.XBOX_ONE, VdfMappings.controllerType("controller_xboxone"))
        assertEquals(ControllerType.PS4, VdfMappings.controllerType("controller_ps4"))
        assertEquals(ControllerType.GENERIC_ANDROID, VdfMappings.controllerType("controller_unknown"))
        assertEquals(ControllerType.GENERIC_ANDROID, VdfMappings.controllerType(null))
    }

    @Test
    fun modes() {
        assertEquals(BindingMode.BUTTON_PAD, VdfMappings.bindingMode("four_buttons"))
        assertEquals(BindingMode.DPAD, VdfMappings.bindingMode("dpad"))
        assertEquals(BindingMode.TRIGGER, VdfMappings.bindingMode("trigger"))
        assertEquals(BindingMode.JOYSTICK_MOVE, VdfMappings.bindingMode("joystick_move"))
        assertEquals(BindingMode.JOYSTICK_MOUSE, VdfMappings.bindingMode("joystick_camera"))
        assertEquals(BindingMode.MOUSE_REGION, VdfMappings.bindingMode("absolute_mouse"))
        assertEquals(BindingMode.FLICK_STICK, VdfMappings.bindingMode("flickstick"))
        // Unmodeled tokens return null so the importer warns rather than guesses.
        assertNull(VdfMappings.bindingMode("switches"))
        assertNull(VdfMappings.bindingMode("mouse_joystick"))
        assertNull(VdfMappings.bindingMode("2dscroll"))
    }

    @Test
    fun inputSources() {
        assertEquals(InputSource.LEFT_JOYSTICK, VdfMappings.inputSource("joystick"))
        assertEquals(InputSource.RIGHT_JOYSTICK, VdfMappings.inputSource("right_joystick"))
        assertEquals(InputSource.BUTTON_DIAMOND, VdfMappings.inputSource("button_diamond"))
        assertEquals(InputSource.GYRO, VdfMappings.inputSource("gyro"))
        assertNull(VdfMappings.inputSource("switch")) // bundled cluster — no 1:1 source
    }

    @Test
    fun subInputDpadRename() {
        assertEquals("dpad_up", VdfMappings.subInputKey("dpad_north"))
        assertEquals("dpad_down", VdfMappings.subInputKey("dpad_south"))
        assertEquals("dpad_right", VdfMappings.subInputKey("dpad_east"))
        assertEquals("dpad_left", VdfMappings.subInputKey("dpad_west"))
        assertEquals("button_a", VdfMappings.subInputKey("button_a"))
        assertEquals("click", VdfMappings.subInputKey("click"))
    }

    @Test
    fun activatorTypes() {
        assertEquals(ActivatorType.FULL_PRESS, VdfMappings.activatorType("Full_Press"))
        assertEquals(ActivatorType.LONG_PRESS, VdfMappings.activatorType("Long_Press"))
        assertEquals(ActivatorType.SOFT_PRESS, VdfMappings.activatorType("Soft_Press"))
        assertNull(VdfMappings.activatorType("Bogus_Press"))
    }

    @Test
    fun keyCodeRenames() {
        assertEquals("ENTER", VdfMappings.keyCode("RETURN"))
        assertEquals("DPAD_UP", VdfMappings.keyCode("UP_ARROW"))
        assertEquals("SHIFT_LEFT", VdfMappings.keyCode("LEFT_SHIFT"))
        assertEquals("CTRL_LEFT", VdfMappings.keyCode("LEFT_CONTROL"))
        assertEquals("SPACE", VdfMappings.keyCode("SPACE"))
        assertEquals("F5", VdfMappings.keyCode("F5"))
        // No Mapo code exists → null, importer keeps verbatim + warns.
        assertNull(VdfMappings.keyCode("KEYPAD_ASTERISK"))
    }

    @Test
    fun gamepadButtons() {
        assertEquals("BUTTON_A", VdfMappings.xinputButton("A"))
        assertEquals("BUTTON_L1", VdfMappings.xinputButton("SHOULDER_LEFT"))
        assertEquals("AXIS_R2", VdfMappings.xinputButton("TRIGGER_RIGHT"))
        assertEquals("BUTTON_THUMBL", VdfMappings.xinputButton("JOYSTICK_LEFT")) // stick click = L3
        assertEquals("DPAD_UP", VdfMappings.xinputButton("DPAD_UP"))
        assertNull(VdfMappings.xinputButton("BOGUS"))
    }

    @Test
    fun mouseButtonsAndWheel() {
        assertEquals("MOUSE_LEFT", VdfMappings.mouseButton("LEFT"))
        assertEquals("MOUSE_MIDDLE", VdfMappings.mouseButton("MIDDLE"))
        assertEquals("SCROLL_UP", VdfMappings.mouseWheel("SCROLL_UP"))
        assertEquals("SCROLL_DOWN", VdfMappings.mouseWheel("SCROLL_DOWN"))
    }
}
