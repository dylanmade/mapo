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
    BindingMode.DPAD,
    BindingMode.JOYSTICK_MOVE,
    BindingMode.JOYSTICK_CAMERA,
    BindingMode.MOUSE_JOYSTICK,
    BindingMode.ABSOLUTE_MOUSE,
    BindingMode.TRIGGER,
    BindingMode.SCROLL_WHEEL,
    BindingMode.TWO_D_SCROLL,
    BindingMode.REFERENCE,
    BindingMode.RADIAL_MENU,
    BindingMode.TOUCH_MENU -> StubMode(this)
}
