package com.mapo.service.input.modes

import com.mapo.data.model.steam.BindingMode

/**
 * Runtime handler for a binding_group's [BindingMode] — the foundation laid by Brick 6.1
 * for the per-input-source mode catalog. A [BindingGroup][com.mapo.data.model.steam.BindingGroup]
 * picks one mode; the mode dictates which sub-input keys are valid and what default
 * settings the group should ship with.
 *
 * **What 6.1 implements:** the contract + the two purely-digital modes
 * ([SingleButtonMode], [ButtonPadMode]) that match today's behavior. Their sub-inputs
 * map 1:1 onto physical events the accessibility service already produces, so the
 * compile/dispatch paths don't need to translate anything; the mode object's job is
 * purely to validate the schema and report defaults.
 *
 * **What later bricks add:** richer modes ([BindingMode.DPAD]'s 4/8-way quadrant gating,
 * [BindingMode.TRIGGER]'s click threshold, [BindingMode.JOYSTICK_MOVE]'s deadzones and
 * response curves, etc.) extend this same interface with an `evaluate(...)` hook that
 * translates analog source state into per-sub-input events the activator engine consumes.
 * That hook isn't on the interface yet because the digital modes don't need it; it's
 * added when 6.3 (Trigger) brings the first analog mode online.
 *
 * **Forward-compat: [StubMode].** Modes not yet built fall through to a permissive
 * stub (`accepts()` returns true for any sub-input key) so existing seeded data — most
 * of which is in `DPAD`, `LEFT_TRIGGER`, etc. groups — doesn't get dropped by compile
 * validation. Once every mode has a real handler (target: end of Phase 6), the stub
 * goes away and validation becomes globally strict.
 */
sealed interface SourceMode {
    /** The data-model enum this runtime handler is bound to. */
    val mode: BindingMode

    /**
     * Sub-input keys this mode defines. The Steam Input vocabulary
     * (`button_a`, `dpad_north`, `click`, `edge`, `touch_menu_button_3`, …) is what
     * group_input rows carry in `inputKey`. Empty for [StubMode] — see [accepts].
     */
    fun validInputs(): Set<String>

    /**
     * The settingsJson blob a fresh [BindingGroup][com.mapo.data.model.steam.BindingGroup]
     * should be seeded with when its mode is this one. Empty `{}` for the digital modes;
     * later analog modes carry typed default deadzones, sensitivities, etc.
     */
    fun defaultSettingsJson(): String = "{}"

    /**
     * Whether [inputKey] is a recognized sub-input under this mode. Default
     * implementation is `inputKey in validInputs()`; [StubMode] overrides to return
     * true unconditionally so unimplemented modes don't drop their existing data.
     */
    fun accepts(inputKey: String): Boolean = inputKey in validInputs()
}

/**
 * One-click input source — bumpers, triggers (digital threshold only until [BindingMode.TRIGGER]
 * lands), stick clicks, start/select buttons. Exactly one sub-input: `click`.
 */
object SingleButtonMode : SourceMode {
    override val mode: BindingMode = BindingMode.SINGLE_BUTTON
    override fun validInputs(): Set<String> = INPUTS
    private val INPUTS = setOf("click")
}

/**
 * Four-button face cluster (the [InputSource.BUTTON_DIAMOND][com.mapo.data.model.steam.InputSource.BUTTON_DIAMOND]
 * source on every controller we target). Sub-inputs: `button_a`, `button_b`, `button_x`,
 * `button_y`. There's no shared "click" — each face button is its own sub-input.
 */
object ButtonPadMode : SourceMode {
    override val mode: BindingMode = BindingMode.BUTTON_PAD
    override fun validInputs(): Set<String> = INPUTS
    private val INPUTS = setOf("button_a", "button_b", "button_x", "button_y")
}

/**
 * 4-direction directional pad. Sub-inputs: `dpad_north`, `dpad_south`, `dpad_east`,
 * `dpad_west`, `click`. The four direction keys flow from physical
 * `KEYCODE_DPAD_UP/DOWN/LEFT/RIGHT` via the accessibility service. `click` is the
 * stick-click sub-input used when an analog stick is interpreted as a dpad — inert
 * on digital-only dpads but reserved here for parity with Steam Input.
 *
 * **Settings (data-model only until analog input lands).** The `dpad_layout` setting
 * is part of the shape because analog Dpad mode (Steam parity) uses it to gate the
 * stick → direction mapping:
 *  - `4_way` — strict N/S/E/W; diagonals snap to one cardinal.
 *  - `8_way` — diagonals emit two adjacent direction inputs simultaneously.
 *  - `cross_gate` — physical-cross template (4-way with deadzone wedges).
 *  - `analog_emulation` — sticks emulated as a continuous analog dpad.
 *
 * With Phase 6 motion-capture tabled, the settings have no runtime effect today —
 * the digital `KEYCODE_DPAD_*` path produces single-direction sub-input events and
 * the layout selection doesn't gate anything. The schema is laid down so the
 * eventual analog refactor doesn't need a settings-shape migration.
 */
object DpadMode : SourceMode {
    override val mode: BindingMode = BindingMode.DPAD
    override fun validInputs(): Set<String> = INPUTS
    /** Steam-default 4-way layout; runtime-inert in 6.3 (no analog source yet). */
    override fun defaultSettingsJson(): String = """{"dpad_layout":"4_way"}"""
    private val INPUTS = setOf("dpad_north", "dpad_south", "dpad_east", "dpad_west", "click")
}

/**
 * Analog-trigger source (LEFT_TRIGGER, RIGHT_TRIGGER). One sub-input: `click`.
 * Physical key path: hardware threshold on the trigger pull surfaces as
 * `KEYCODE_BUTTON_L2` / `KEYCODE_BUTTON_R2`, which the accessibility service routes
 * to `InputAddress(LEFT_TRIGGER, "click")` / `(RIGHT_TRIGGER, "click")` today.
 *
 * **Settings shape (data-model only until motion capture returns).**
 *  - `click_threshold` — analog pull magnitude (0..1) at which the click sub-input
 *    fires. Steam-default 0.95. Inert in 6.4 because we have no analog source feeding
 *    it; the digital hardware threshold (whatever the device decides) is what we get.
 *  - Future settings (response curve, soft-press threshold, output range remap) will
 *    join this JSON when analog input is plugged back in.
 *
 * **Soft_Press activator status.** Steam Input pairs Trigger mode with the
 * `Soft_Press` activator type — fires when the analog pull crosses a soft threshold
 * before the click threshold. The activator type exists in our enum
 * ([ActivatorType.SOFT_PRESS][com.mapo.data.model.steam.ActivatorType.SOFT_PRESS])
 * but the evaluator currently skips it (digital triggers don't surface the
 * "below-click" pull state). Becomes active when the motion-capture refactor lands.
 */
object TriggerMode : SourceMode {
    override val mode: BindingMode = BindingMode.TRIGGER
    override fun validInputs(): Set<String> = INPUTS
    /** Steam-default click threshold at 95% pull; runtime-inert in 6.4 (digital only). */
    override fun defaultSettingsJson(): String = """{"click_threshold":0.95}"""
    private val INPUTS = setOf("click")
}

/**
 * Placeholder handler for a [BindingMode] whose runtime hasn't landed yet. Reports an
 * empty [validInputs] but [accepts] always returns true so the compile path doesn't drop
 * existing seeded data while we phase the rest of the modes in (6.2 onward). Disappears
 * when every enum value has a real handler.
 */
data class StubMode(override val mode: BindingMode) : SourceMode {
    override fun validInputs(): Set<String> = emptySet()
    override fun accepts(inputKey: String): Boolean = true
}

/**
 * Resolve the runtime [SourceMode] handler for a [BindingMode] enum value. Implemented
 * modes return their singleton handler; everything else falls through to [StubMode] —
 * which is what makes 6.1 a non-breaking foundation rather than a hard cutover.
 */
fun BindingMode.handler(): SourceMode = when (this) {
    BindingMode.SINGLE_BUTTON -> SingleButtonMode
    BindingMode.BUTTON_PAD -> ButtonPadMode
    BindingMode.DPAD -> DpadMode
    BindingMode.TRIGGER -> TriggerMode
    BindingMode.JOYSTICK_MOVE,
    BindingMode.JOYSTICK_CAMERA,
    BindingMode.MOUSE_JOYSTICK,
    BindingMode.ABSOLUTE_MOUSE,
    BindingMode.SCROLL_WHEEL,
    BindingMode.TWO_D_SCROLL,
    BindingMode.REFERENCE,
    BindingMode.RADIAL_MENU,
    BindingMode.TOUCH_MENU -> StubMode(this)
}
