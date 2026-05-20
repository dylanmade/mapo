package com.mapo.data.model.steam

/**
 * Identifies the physical controller layout a [ControllerProfile] targets.
 * Steam Input VDF uses tokens like `controller_neptune`; we keep our own typed set
 * and translate at the VDF import boundary.
 */
enum class ControllerType {
    GENERIC_ANDROID,
    STEAM_DECK,
    XBOX_ELITE,
    XBOX_ONE,
    PS4,
    PS5,
    NINTENDO_SWITCH_PRO,
}

/**
 * The mode a [BindingGroup] interprets its source in. Determines which sub-inputs
 * are valid and how analog/digital state translates into [GroupInput] events.
 * Phase 1 ships the schema; mode runtime behavior lands in Phase 6.
 */
enum class BindingMode {
    SINGLE_BUTTON,
    DPAD,
    BUTTON_PAD,
    JOYSTICK_MOVE,
    JOYSTICK_CAMERA,
    MOUSE_JOYSTICK,
    ABSOLUTE_MOUSE,
    TRIGGER,
    SCROLL_WHEEL,
    TWO_D_SCROLL,
    REFERENCE,
    RADIAL_MENU,
    TOUCH_MENU,
}

/**
 * Activator types per Steam Input. Universal settings (toggle, delays, haptics,
 * cycle_binding) plus type-specific settings live in [Activator.settingsJson].
 */
enum class ActivatorType {
    FULL_PRESS,
    LONG_PRESS,
    DOUBLE_PRESS,
    START_PRESS,
    RELEASE_PRESS,
    SOFT_PRESS,
    CHORDED_PRESS,
}

/**
 * Output category for a [Binding]. Args interpretation depends on this value.
 * Concrete output types (KEY_PRESS, MOUSE_*, XINPUT_BUTTON) are fully runtime-supported.
 * GAME_ACTION + CONTROLLER_ACTION + MODE_SHIFT are placeholders until later phases.
 */
enum class BindingOutputType {
    UNBOUND,
    KEY_PRESS,
    XINPUT_BUTTON,
    MOUSE_BUTTON,
    MOUSE_WHEEL,
    GAME_ACTION,
    CONTROLLER_ACTION,
    MODE_SHIFT,
}

/**
 * Physical input source on the controller. A [PresetBinding] assigns one binding group
 * per (action_set, input_source, state) tuple. Not every device exposes every source;
 * Generic Android in particular has no trackpads, paddles, or gyro.
 */
enum class InputSource {
    BUTTON_DIAMOND,
    DPAD,
    LEFT_BUMPER,
    RIGHT_BUMPER,
    LEFT_TRIGGER,
    RIGHT_TRIGGER,
    LEFT_JOYSTICK,
    RIGHT_JOYSTICK,
    LEFT_TRACKPAD,
    RIGHT_TRACKPAD,
    SWITCH_START,
    SWITCH_SELECT,
    SWITCH_BACK_LEFT,
    SWITCH_BACK_RIGHT,
    GYRO,
}

fun BindingMode.displayName(): String = when (this) {
    BindingMode.SINGLE_BUTTON -> "Single Button"
    BindingMode.BUTTON_PAD -> "Button Pad"
    BindingMode.DPAD -> "D-Pad"
    BindingMode.JOYSTICK_MOVE -> "Joystick Move"
    BindingMode.JOYSTICK_CAMERA -> "Joystick Camera"
    BindingMode.MOUSE_JOYSTICK -> "Mouse Joystick"
    BindingMode.ABSOLUTE_MOUSE -> "Absolute Mouse"
    BindingMode.TRIGGER -> "Trigger"
    BindingMode.SCROLL_WHEEL -> "Scroll Wheel"
    BindingMode.TWO_D_SCROLL -> "2D Scroll"
    BindingMode.REFERENCE -> "Reference"
    BindingMode.RADIAL_MENU -> "Radial Menu"
    BindingMode.TOUCH_MENU -> "Touch Menu"
}

fun InputSource.displayName(): String = when (this) {
    InputSource.BUTTON_DIAMOND -> "Face Buttons"
    InputSource.DPAD -> "D-Pad"
    InputSource.LEFT_BUMPER -> "Left Bumper"
    InputSource.RIGHT_BUMPER -> "Right Bumper"
    InputSource.LEFT_TRIGGER -> "Left Trigger"
    InputSource.RIGHT_TRIGGER -> "Right Trigger"
    InputSource.LEFT_JOYSTICK -> "Left Joystick"
    InputSource.RIGHT_JOYSTICK -> "Right Joystick"
    InputSource.LEFT_TRACKPAD -> "Left Trackpad"
    InputSource.RIGHT_TRACKPAD -> "Right Trackpad"
    InputSource.SWITCH_START -> "Start"
    InputSource.SWITCH_SELECT -> "Select"
    InputSource.SWITCH_BACK_LEFT -> "Back Paddle (L)"
    InputSource.SWITCH_BACK_RIGHT -> "Back Paddle (R)"
    InputSource.GYRO -> "Gyro"
}
