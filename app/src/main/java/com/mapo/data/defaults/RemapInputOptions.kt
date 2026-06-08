package com.mapo.data.defaults

import com.mapo.data.model.DeviceButton
import com.mapo.data.model.RemapTarget
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.toImmutableList

data class InputOption(val label: String, val target: RemapTarget)

object RemapInputOptions {

    val gamepadOptions: ImmutableList<InputOption> = buildList {
        DeviceButton.entries.forEach { btn -> add(InputOption(btn.displayName, RemapTarget.Gamepad(btn.name))) }
        // Stick directions emit analog axis output on the virtual gamepad (requires Shizuku).
        // Tokens parse back to BindingOutput.XInputStick via fromRemapTarget.
        listOf(
            "LSTICK_UP" to "Left Stick Up", "LSTICK_DOWN" to "Left Stick Down",
            "LSTICK_LEFT" to "Left Stick Left", "LSTICK_RIGHT" to "Left Stick Right",
            "RSTICK_UP" to "Right Stick Up", "RSTICK_DOWN" to "Right Stick Down",
            "RSTICK_LEFT" to "Right Stick Left", "RSTICK_RIGHT" to "Right Stick Right",
        ).forEach { (token, label) -> add(InputOption(label, RemapTarget.Gamepad(token))) }
    }.toImmutableList()

    val keyboardOptions: ImmutableList<InputOption> = listOf(
        "ESCAPE", "F1", "F2", "F3", "F4", "F5", "F6", "F7", "F8", "F9", "F10", "F11", "F12",
        "GRAVE", "1", "2", "3", "4", "5", "6", "7", "8", "9", "0", "MINUS", "EQUALS", "BACKSPACE",
        "TAB", "Q", "W", "E", "R", "T", "Y", "U", "I", "O", "P", "LEFT_BRACKET", "RIGHT_BRACKET", "BACKSLASH",
        "CAPS_LOCK", "A", "S", "D", "F", "G", "H", "J", "K", "L", "SEMICOLON", "APOSTROPHE", "ENTER",
        "SHIFT_LEFT", "Z", "X", "C", "V", "B", "N", "M", "COMMA", "PERIOD", "SLASH", "SHIFT_RIGHT",
        "CTRL_LEFT", "META_LEFT", "ALT_LEFT", "SPACE", "ALT_RIGHT", "MENU", "CTRL_RIGHT",
        "SYSRQ", "SCROLL_LOCK", "BREAK", "INSERT", "MOVE_HOME", "PAGE_UP",
        "FORWARD_DEL", "MOVE_END", "PAGE_DOWN", "DPAD_UP", "DPAD_DOWN", "DPAD_LEFT", "DPAD_RIGHT"
    ).map { code -> InputOption(code, RemapTarget.Keyboard(code)) }.toImmutableList()

    val mouseOptions: ImmutableList<InputOption> = listOf(
        "MOUSE_LEFT", "MOUSE_MIDDLE", "MOUSE_RIGHT",
        "SCROLL_UP", "SCROLL_DOWN", "MOUSE_BACK", "MOUSE_FORWARD"
    ).map { code -> InputOption(code, RemapTarget.Mouse(code)) }.toImmutableList()
}
