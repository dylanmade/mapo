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
 *
 * **Naming convention.** Steam Input modes are named `<input> <output>` — left word
 * = input feel, right word = output format. So `JOYSTICK_MOUSE` means a joystick
 * input that outputs as mouse motion; `JOYSTICK_MOVE` means a joystick input that
 * outputs as XInput axis (the game receives a stick); `MOUSE_REGION` is the
 * trackpad-style absolute-region mapping. See [feedback_steam_mode_naming_convention.md].
 *
 * **DEVICE_DEFAULT vs NONE.** Two distinct no-output sentinels:
 *  - [DEVICE_DEFAULT] — Mapo does NOT intercept; Android's hardware-native input
 *    flows untouched to the foreground app. Mapo-specific concept.
 *  - [NONE] — Mapo intercepts and silences. Direct Steam Input parity.
 *
 * See `feedback_none_vs_device_default_distinction.md`.
 */
enum class BindingMode {
    /**
     * Mapo does not intercept this source — physical events pass through to the
     * foreground app untouched, no activators fire. The default for
     * analog-capable sources on freshly-seeded profiles so user-facing behavior
     * starts at "Mapo does nothing" and the user opts in mode-by-mode.
     *
     * Mapo-specific. No Steam Input analog (Steam IS the controller driver, so
     * "pass through to deeper driver" doesn't apply there).
     */
    DEVICE_DEFAULT,

    /**
     * Mapo intercepts this source but emits nothing. Direct Steam Input parity
     * for the "None" mode dropdown option — distinct from [DEVICE_DEFAULT]'s
     * pass-through because here Mapo actively consumes the event to silence it.
     */
    NONE,

    SINGLE_BUTTON,
    DPAD,
    BUTTON_PAD,
    TRIGGER,

    /**
     * Stick input → XInput stick output. Steam's "Joystick Move" mode — the
     * game receives a virtual analog stick. Mapo runtime ships in Phase 7
     * Brick C via a `/dev/uinput` virtual XInput gamepad.
     */
    JOYSTICK_MOVE,

    /**
     * Stick input → mouse cursor output. Steam's "Joystick Mouse" mode — what
     * Mapo previously (mis-)called `MOUSE_JOYSTICK`. The cursor-feel modes
     * (formerly JOYSTICK_CAMERA) collapse into this single mode with a
     * settings preset selecting the response curve.
     */
    JOYSTICK_MOUSE,

    /**
     * Gyro-augmented flick-aim mode for joystick sources. Stick deflection
     * triggers a fast rotation; sustained deflection allows fine-tuning;
     * gyro tracks during the snap for natural aim feel. Runtime ships in
     * Phase 7 Brick E.
     */
    FLICK_STICK,

    /**
     * Stick deflection % maps to cursor position % of a screen region. Stick
     * at full-up → cursor at top-center of region. Steam's "Mouse Region"
     * mode — supersedes the previous Mapo-specific `ABSOLUTE_MOUSE` (which
     * was a partial implementation of this same concept). Runtime ships in
     * Phase 7 Brick C (joystick) + D (gyro feed).
     */
    MOUSE_REGION,

    SCROLL_WHEEL,
    REFERENCE,

    /**
     * Gyro orientation → mouse motion. Phase 7 Brick D runtime.
     */
    GYRO_TO_MOUSE,

    /**
     * Gyro angular velocity → XInput stick output, camera-tuned. Phase 7 Brick D runtime.
     */
    GYRO_TO_JOYSTICK_CAMERA,

    /**
     * Gyro orientation → XInput stick deflection (semantics TBD). Phase 7 Brick D runtime.
     */
    GYRO_TO_JOYSTICK_DEFLECTION,

    /**
     * Gesture mode — gyro or trackpad swipe in a cardinal direction fires
     * synthetic edges. Phase 7 stub; runtime ships post-Phase-8.
     */
    DIRECTIONAL_SWIPE,

    RADIAL_MENU,
    TOUCH_MENU,

    /**
     * Hotbar menu — Steam's third menu type alongside Radial and Touch. Phase 9
     * data scaffold; runtime later.
     */
    HOTBAR_MENU,
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

/**
 * Source-aware display name. Falls back to [displayName] when the (source, mode)
 * pair doesn't have a context-specific label. Use this from the picker layer
 * so triggers in `TRIGGER` mode read as "Trigger (Analog)" and triggers in
 * `SINGLE_BUTTON` mode read as "Trigger (Digital)" — the same enum values
 * have different colloquial meanings on different sources.
 */
fun BindingMode.displayNameFor(source: InputSource): String = when {
    source == InputSource.LEFT_TRIGGER || source == InputSource.RIGHT_TRIGGER -> when (this) {
        BindingMode.TRIGGER -> "Trigger (Analog)"
        BindingMode.SINGLE_BUTTON -> "Trigger (Digital)"
        else -> displayName()
    }
    else -> displayName()
}

/**
 * User-facing display name for this mode. Context-agnostic — sources with
 * source-specific labels (e.g. trigger source showing `SINGLE_BUTTON` as
 * `Trigger (Digital)`) should call [displayNameFor] instead.
 */
fun BindingMode.displayName(): String = when (this) {
    BindingMode.DEVICE_DEFAULT -> "[Device Default]"
    BindingMode.NONE -> "None"
    BindingMode.SINGLE_BUTTON -> "Single Button"
    BindingMode.BUTTON_PAD -> "Button Pad"
    BindingMode.DPAD -> "Directional Pad"
    BindingMode.TRIGGER -> "Trigger"
    BindingMode.JOYSTICK_MOVE -> "Joystick"
    BindingMode.JOYSTICK_MOUSE -> "Joystick Mouse"
    BindingMode.FLICK_STICK -> "Flick Stick"
    BindingMode.MOUSE_REGION -> "Mouse Region"
    BindingMode.SCROLL_WHEEL -> "Scroll Wheel"
    BindingMode.REFERENCE -> "Reference"
    BindingMode.GYRO_TO_MOUSE -> "Gyro to Mouse"
    BindingMode.GYRO_TO_JOYSTICK_CAMERA -> "Gyro to Joystick Camera"
    BindingMode.GYRO_TO_JOYSTICK_DEFLECTION -> "Gyro to Joystick Deflection"
    BindingMode.DIRECTIONAL_SWIPE -> "Directional Swipe"
    BindingMode.RADIAL_MENU -> "Radial Menu"
    BindingMode.TOUCH_MENU -> "Touch Menu"
    BindingMode.HOTBAR_MENU -> "Hotbar Menu"
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
