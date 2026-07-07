package com.mappo.data.io.vdf

import com.mappo.data.model.steam.ActivatorType
import com.mappo.data.model.steam.BindingMode
import com.mappo.data.model.steam.BindingOutput
import com.mappo.data.model.steam.ControllerType
import com.mappo.data.model.steam.InputSource

/**
 * The VDF-token → Mappo-type translation tables. Pure, side-effect-free, and unit-
 * tested in isolation so the single place every "what does this Steam token mean"
 * decision lives is auditable. Tokens were mined from 62 real community configs
 * (`~/Downloads/vdf_examples/`); see `project_vdf_parser_landed`.
 *
 * Each mapper returns null on an unrecognized token; [VdfImporter] turns a null
 * into an [ImportWarning] rather than guessing — a silent wrong mapping is worse
 * than a flagged gap.
 */
internal object VdfMappings {

    // ── Controller type ──────────────────────────────────────────────────────
    fun controllerType(token: String?): ControllerType = when (token?.lowercase()) {
        "controller_neptune" -> ControllerType.STEAM_DECK
        "controller_xboxelite" -> ControllerType.XBOX_ELITE
        "controller_xboxone", "controller_xbox360" -> ControllerType.XBOX_ONE
        "controller_ps4" -> ControllerType.PS4
        "controller_ps5" -> ControllerType.PS5
        "controller_switch_pro", "controller_switch" -> ControllerType.NINTENDO_SWITCH_PRO
        else -> ControllerType.GENERIC_ANDROID
    }

    // ── Source mode (`"mode"` token) ─────────────────────────────────────────
    // The `<input> <output>` naming rule governs these; see
    // feedback_steam_mode_naming_convention. Tokens with no faithful Mappo mode
    // return null (the importer warns + degrades to NONE so nothing mis-fires).
    fun bindingMode(token: String?): BindingMode? = when (token?.lowercase()) {
        "four_buttons" -> BindingMode.BUTTON_PAD
        "dpad" -> BindingMode.DPAD
        "single_button" -> BindingMode.SINGLE_BUTTON
        "trigger" -> BindingMode.TRIGGER
        "joystick_move" -> BindingMode.JOYSTICK_MOVE
        // Steam "Joystick Mouse" + the camera-tuned variant collapse into Mappo's
        // single cursor-output stick mode (SteamEnums KDoc).
        "joystick_mouse", "joystick_camera" -> BindingMode.JOYSTICK_MOUSE
        "flickstick" -> BindingMode.FLICK_STICK
        // absolute_mouse is the legacy spelling MOUSE_REGION supersedes (SteamEnums KDoc).
        "mouse_region", "absolute_mouse" -> BindingMode.MOUSE_REGION
        "scrollwheel" -> BindingMode.SCROLL_WHEEL
        "radial_menu" -> BindingMode.RADIAL_MENU
        "touch_menu" -> BindingMode.TOUCH_MENU
        "reference" -> BindingMode.REFERENCE
        // No faithful Mappo mode yet:
        //  - switches      → one VDF group fans out to many Mappo single-button sources
        //  - mouse_joystick → mouse-feel→joystick output (trackpad mode); unmodeled
        //  - 2dscroll       → 2-axis scroll; unmodeled
        else -> null
    }

    // ── Input source (`group_source_bindings` source token) ──────────────────
    fun inputSource(token: String): InputSource? = when (token.lowercase()) {
        "button_diamond" -> InputSource.BUTTON_DIAMOND
        "dpad" -> InputSource.DPAD
        "left_bumper" -> InputSource.LEFT_BUMPER
        "right_bumper" -> InputSource.RIGHT_BUMPER
        "left_trigger" -> InputSource.LEFT_TRIGGER
        "right_trigger" -> InputSource.RIGHT_TRIGGER
        "joystick", "left_joystick" -> InputSource.LEFT_JOYSTICK
        "right_joystick" -> InputSource.RIGHT_JOYSTICK
        "left_trackpad" -> InputSource.LEFT_TRACKPAD
        "right_trackpad" -> InputSource.RIGHT_TRACKPAD
        "gyro" -> InputSource.GYRO
        // "switch" is the bundled switch cluster — Mappo splits it into START /
        // SELECT / BACK_LEFT / BACK_RIGHT, which the switches-mode handler (a later
        // brick) must fan out. No single InputSource maps cleanly.
        else -> null
    }

    // ── Sub-input key (group input name) ─────────────────────────────────────
    // Most pass through; the dpad cardinals are renamed N/S/E/W → up/down/left/right.
    fun subInputKey(vdfInputName: String): String = when (vdfInputName.lowercase()) {
        "dpad_north" -> "dpad_up"
        "dpad_south" -> "dpad_down"
        "dpad_east" -> "dpad_right"
        "dpad_west" -> "dpad_left"
        else -> vdfInputName.lowercase()
    }

    // ── Activator type (`Full_Press` / `Soft_Press` / …) ─────────────────────
    // Steam writes both the long form (`Full_Press`) and a bare short form (`release`,
    // `start`, …) depending on context; accept both.
    fun activatorType(token: String): ActivatorType? = when (token.lowercase()) {
        "full_press", "full" -> ActivatorType.FULL_PRESS
        "long_press", "long" -> ActivatorType.LONG_PRESS
        "double_press", "double" -> ActivatorType.DOUBLE_PRESS
        "start_press", "start" -> ActivatorType.START_PRESS
        "release_press", "release" -> ActivatorType.RELEASE_PRESS
        "chorded_press", "chorded", "chord" -> ActivatorType.CHORDED_PRESS
        // Soft_Press is reserved for VDF import only (feedback_soft_press_unified_to_soft_pull):
        // on a trigger it's unified onto the soft_pull sub-input by VdfImporter; this enum
        // value lets non-trigger occurrences round-trip rather than vanish.
        "soft_press" -> ActivatorType.SOFT_PRESS
        else -> null
    }

    // ── Keyboard key (`key_press <token>`) ───────────────────────────────────
    // VDF spells some keys differently from Mappo's RemapInputOptions vocabulary.
    private val keyRenames: Map<String, String> = mapOf(
        "RETURN" to "ENTER",
        "LEFT_ARROW" to "DPAD_LEFT", // Android KEYCODE_DPAD_* are the arrow keys
        "RIGHT_ARROW" to "DPAD_RIGHT",
        "UP_ARROW" to "DPAD_UP",
        "DOWN_ARROW" to "DPAD_DOWN",
        "LEFT_SHIFT" to "SHIFT_LEFT",
        "RIGHT_SHIFT" to "SHIFT_RIGHT",
        "LEFT_CONTROL" to "CTRL_LEFT",
        "RIGHT_CONTROL" to "CTRL_RIGHT",
        "LEFT_ALT" to "ALT_LEFT",
        "RIGHT_ALT" to "ALT_RIGHT",
        "FORWARD_DELETE" to "FORWARD_DEL",
        "DELETE" to "FORWARD_DEL",
        "HOME" to "MOVE_HOME",
        "END" to "MOVE_END",
    )

    /** Mappo's keyboard-code vocabulary (mirrors RemapInputOptions). Used to detect
     *  keys that translate to no Mappo code so the importer can warn. */
    private val mappoKeyCodes: Set<String> = setOf(
        "A", "B", "C", "D", "E", "F", "G", "H", "I", "J", "K", "L", "M", "N", "O", "P", "Q",
        "R", "S", "T", "U", "V", "W", "X", "Y", "Z",
        "0", "1", "2", "3", "4", "5", "6", "7", "8", "9",
        "F1", "F2", "F3", "F4", "F5", "F6", "F7", "F8", "F9", "F10", "F11", "F12",
        "ENTER", "ESCAPE", "SPACE", "TAB", "BACKSPACE", "INSERT", "FORWARD_DEL",
        "DPAD_UP", "DPAD_DOWN", "DPAD_LEFT", "DPAD_RIGHT",
        "SHIFT_LEFT", "SHIFT_RIGHT", "CTRL_LEFT", "CTRL_RIGHT", "ALT_LEFT", "ALT_RIGHT",
        "META_LEFT", "CAPS_LOCK", "PAGE_UP", "PAGE_DOWN", "MOVE_HOME", "MOVE_END",
        "MENU", "BREAK", "SCROLL_LOCK", "SYSRQ",
        "COMMA", "PERIOD", "MINUS", "EQUALS", "GRAVE", "APOSTROPHE", "SEMICOLON",
        "SLASH", "BACKSLASH", "LEFT_BRACKET", "RIGHT_BRACKET",
    )

    /** Maps a VDF `key_press` token to a Mappo key code. Returns null when no Mappo
     *  code exists (e.g. KEYPAD_*), so the importer warns instead of binding a dud. */
    fun keyCode(vdfKey: String): String? {
        val renamed = keyRenames[vdfKey] ?: vdfKey
        return renamed.takeIf { it in mappoKeyCodes }
    }

    // ── Gamepad button (`xinput_button <token>`) ─────────────────────────────
    private val xinputButtons: Map<String, String> = mapOf(
        "A" to "BUTTON_A", "B" to "BUTTON_B", "X" to "BUTTON_X", "Y" to "BUTTON_Y",
        "SHOULDER_LEFT" to "BUTTON_L1", "SHOULDER_RIGHT" to "BUTTON_R1",
        "TRIGGER_LEFT" to "AXIS_L2", "TRIGGER_RIGHT" to "AXIS_R2",
        "JOYSTICK_LEFT" to "BUTTON_THUMBL", "JOYSTICK_RIGHT" to "BUTTON_THUMBR",
        "START" to "BUTTON_START", "SELECT" to "BUTTON_SELECT",
        "DPAD_UP" to "DPAD_UP", "DPAD_DOWN" to "DPAD_DOWN",
        "DPAD_LEFT" to "DPAD_LEFT", "DPAD_RIGHT" to "DPAD_RIGHT",
    )

    fun xinputButton(token: String): String? = xinputButtons[token.uppercase()]

    // ── Mouse button (`mouse_button <token>`) ────────────────────────────────
    fun mouseButton(token: String): String? = when (token.uppercase()) {
        "LEFT" -> "MOUSE_LEFT"
        "RIGHT" -> "MOUSE_RIGHT"
        "MIDDLE" -> "MOUSE_MIDDLE"
        "BACK" -> "MOUSE_BACK"
        "FORWARD" -> "MOUSE_FORWARD"
        else -> null
    }

    // ── Mouse wheel (`mouse_wheel <token>`) ──────────────────────────────────
    fun mouseWheel(token: String): String? = when (token.uppercase()) {
        "SCROLL_UP", "UP" -> "SCROLL_UP"
        "SCROLL_DOWN", "DOWN" -> "SCROLL_DOWN"
        else -> null
    }
}
