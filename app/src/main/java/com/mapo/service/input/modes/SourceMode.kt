package com.mapo.service.input.modes

import com.mapo.data.model.steam.BindingMode
import com.mapo.data.model.steam.InputSource
import com.mapo.service.input.AnalogEvent
import org.json.JSONException
import org.json.JSONObject

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

    /**
     * Brick 5 — D1 evaluation hook. Translates a normalized [AnalogEvent] for this
     * source into per-sub-input synthetic edges and (later — Brick 7) continuous
     * mouse output. Called per motion-event sample by
     * [InputEvaluator.handleMotion][com.mapo.service.input.InputEvaluator.handleMotion]
     * once a source's mode is resolved.
     *
     *  - [reading] — the source's current x/y/timestamp (pre-deadzone; modes
     *    apply deadzones themselves via [AnalogEvent.withDeadzone] or
     *    per-mode settings).
     *  - [ctx] — settings JSON + the prior latched-state map for this source's
     *    virtual sub-inputs, so modes can do edge detection without holding
     *    state themselves (modes are singletons).
     *  - [digitalEmit] — emit a synthetic press/release edge on a virtual sub-
     *    input. The dispatcher routes the edge into the activator engine; the
     *    exact mapping is mode-aware (e.g. Trigger's "soft_press" key resolves
     *    to SOFT_PRESS-type activators on the source's `"click"` sub-input).
     *  - [mouse] — continuous cursor / scroll output sink for the modes that
     *    need it (Brick 7's Mouse-Joystick / Joystick-Camera). Digital-only
     *    modes ignore it.
     *
     * Default: no-op. Digital modes (Single Button / Button Pad / Dpad), the
     * pass-through [UnboundMode], and [StubMode] all stay on the default.
     */
    fun evaluate(
        reading: AnalogEvent,
        ctx: ModeContext,
        digitalEmit: (subInput: String, isDown: Boolean) -> Unit,
        mouse: MouseEmitter,
    ) {
        // default no-op
    }
}

/**
 * Per-call context handed to [SourceMode.evaluate]. Carries the settings JSON
 * for the source's binding_group and the prior latched-state map for any
 * synthetic sub-inputs the mode emits — modes are singletons, so per-source
 * state lives upstream in the dispatcher and is passed in here.
 *
 * [activeLayerIds] is included so future modes can resolve overlay settings
 * (Brick 6+); Brick 5 doesn't read it.
 */
data class ModeContext(
    val source: InputSource,
    val settingsJson: String,
    val priorLatched: Map<String, Boolean>,
    val activeLayerIds: List<Long>,
)

/**
 * Continuous-output sink for analog modes whose output is mouse motion or
 * scroll rather than synthetic-digital edges (D2). Brick 7 fleshes this out
 * with `moveCursorBy(dx, dy)` / scroll deltas; Brick 5 only locks the shape
 * so [SourceMode.evaluate]'s signature doesn't churn between bricks.
 */
interface MouseEmitter {
    companion object {
        /** No-op sink for digital modes and for tests; Brick 7 supplies the real one. */
        val NOOP: MouseEmitter = object : MouseEmitter {}
    }
}

/**
 * Pass-through: Mapo does not intercept this source. No sub-inputs are accepted,
 * so the compile step drops any rows under a binding group in this mode and the
 * activator engine never sees a configured input for the source — physical events
 * flow to the foreground app untouched.
 *
 * This is the default mode for analog-capable sources (sticks, dpad, triggers)
 * on freshly-seeded profiles. The user must explicitly pick an analog mode for
 * Mapo to start interpreting the source, which is also what gates the
 * [MotionCaptureCoordinator][com.mapo.service.input.capture.MotionCaptureCoordinator]'s
 * focused-overlay attach — keeping the overlay's side effects (IME / back / app-
 * switcher gesture suspension) out of profiles that haven't opted in.
 */
object UnboundMode : SourceMode {
    override val mode: BindingMode = BindingMode.UNBOUND
    override fun validInputs(): Set<String> = emptySet()
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
 * Analog-trigger source (LEFT_TRIGGER, RIGHT_TRIGGER). Two sub-inputs:
 *  - `click` — the hardware threshold sub-input. Fires via the accessibility
 *    service's `KEYCODE_BUTTON_L2` / `KEYCODE_BUTTON_R2` digital edge. Any
 *    activator type (FULL_PRESS, LONG_PRESS, DOUBLE_PRESS, RELEASE_PRESS,
 *    CHORDED_PRESS) is valid on this row. UI label: "L2/R2 Full Pull".
 *  - `soft_press` — the analog soft-pull sub-input. Fires via [evaluate] on
 *    motion events captured by the focused overlay when the analog magnitude
 *    crosses `soft_threshold` (with `soft_hysteresis` on release). Any
 *    activator type is valid here too — most users pick FULL_PRESS so the
 *    soft pull fires their chosen command once per pull. UI label: "L2/R2 Soft Pull".
 *
 * **Why a separate sub-input and not a SOFT_PRESS activator type on `click`?**
 * Steam Input exposes BOTH a SOFT_PRESS activator and a Soft Pull trigger slot,
 * which is the same behavior expressed two ways. Mapo unifies on the sub-input
 * model — one place for the user to express "fire when the trigger is in the
 * soft range" — and retires SOFT_PRESS as a picker option (it stays in the
 * enum solely so VDF import can translate Steam's `SOFT_PRESS` activator into
 * a Mapo activator on the `"soft_press"` sub-input).
 *
 * **Settings:**
 *  - `click_threshold` — Steam-default 0.95. Documented; inert on Android
 *    today because the hardware decides where the digital click edge sits.
 *  - `soft_threshold` — Steam-default 0.10. Analog magnitude at which a
 *    soft-pull press fires.
 *  - `soft_hysteresis` — Steam-default 0.05. Dead-band width on release;
 *    soft-pull releases when magnitude drops below
 *    `(soft_threshold - soft_hysteresis)`. Prevents wobbly-finger flutter at
 *    the threshold edge.
 */
object TriggerMode : SourceMode {
    override val mode: BindingMode = BindingMode.TRIGGER
    override fun validInputs(): Set<String> = INPUTS
    override fun defaultSettingsJson(): String =
        """{"click_threshold":0.95,"soft_threshold":0.10,"soft_hysteresis":0.05}"""
    private val INPUTS = setOf("click", SOFT_PRESS_SUB_INPUT)

    /** Sub-input key for the analog soft-pull row. */
    const val SOFT_PRESS_SUB_INPUT: String = "soft_press"

    override fun evaluate(
        reading: AnalogEvent,
        ctx: ModeContext,
        digitalEmit: (subInput: String, isDown: Boolean) -> Unit,
        mouse: MouseEmitter,
    ) {
        val settings = TriggerSettings.parse(ctx.settingsJson)
        val magnitude = reading.x  // triggers are 0..1 on x; y is always 0
        val priorLatched = ctx.priorLatched[SOFT_PRESS_SUB_INPUT] == true
        val nowLatched = if (priorLatched) {
            // Stay latched until magnitude drops below the lower hysteresis band.
            magnitude >= (settings.softThreshold - settings.softHysteresis)
        } else {
            // Latch when magnitude crosses the threshold upward.
            magnitude >= settings.softThreshold
        }
        if (nowLatched != priorLatched) {
            digitalEmit(SOFT_PRESS_SUB_INPUT, nowLatched)
        }
    }
}

/**
 * Parsed [TriggerMode] settings. Tolerant of missing keys (falls back to Steam
 * defaults), so a binding_group seeded before this brick — which only had
 * `click_threshold` in its JSON — keeps working without a migration.
 */
internal data class TriggerSettings(
    val clickThreshold: Float,
    val softThreshold: Float,
    val softHysteresis: Float,
) {
    companion object {
        const val DEFAULT_CLICK_THRESHOLD = 0.95f
        const val DEFAULT_SOFT_THRESHOLD = 0.10f
        const val DEFAULT_SOFT_HYSTERESIS = 0.05f

        fun parse(json: String): TriggerSettings {
            if (json.isBlank()) return defaults()
            return try {
                val obj = JSONObject(json)
                TriggerSettings(
                    clickThreshold = obj.optDouble("click_threshold", DEFAULT_CLICK_THRESHOLD.toDouble()).toFloat(),
                    softThreshold = obj.optDouble("soft_threshold", DEFAULT_SOFT_THRESHOLD.toDouble()).toFloat(),
                    softHysteresis = obj.optDouble("soft_hysteresis", DEFAULT_SOFT_HYSTERESIS.toDouble()).toFloat(),
                )
            } catch (_: JSONException) {
                defaults()
            }
        }

        private fun defaults() = TriggerSettings(
            clickThreshold = DEFAULT_CLICK_THRESHOLD,
            softThreshold = DEFAULT_SOFT_THRESHOLD,
            softHysteresis = DEFAULT_SOFT_HYSTERESIS,
        )
    }
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
    BindingMode.UNBOUND -> UnboundMode
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

/**
 * Catalog of which [BindingMode]s the user can pick for each physical [InputSource]
 * via the Remap Controls subheader mode dropdown (Phase 6 Brick 1).
 *
 * Returns an empty list for sources that don't expose a mode choice (a single-button
 * bumper has only one sensible mode, so we hide the picker rather than show a
 * one-item dropdown). Sources with multiple valid modes show the dropdown.
 *
 * Modes that aren't yet implemented at runtime still appear here — selecting one
 * persists the mode but routes through [StubMode] at evaluation time until the
 * matching analog handler lands. The picker is the wire; the handler ships per
 * later brick.
 */
/**
 * Modes whose runtime behavior depends on analog `MotionEvent` capture
 * (Brick 4's gating predicate). When at least one source in the
 * resolved-active set/layer has a mode in this set, the
 * [MotionCaptureCoordinator][com.mapo.service.input.capture.MotionCaptureCoordinator]
 * attaches the focused capture overlay; otherwise it stays detached so
 * IME / back gesture / app-switcher continue working normally outside the
 * active gameplay window.
 *
 * **What's in:** the analog stick / mouse / scroll modes whose `evaluate()`
 * hook needs a motion stream, plus [BindingMode.TRIGGER] (added in Brick 5
 * for Soft_Press — the soft threshold can only be detected from the analog
 * pull magnitude, not the hardware click edge). **What's out:** digital
 * modes (SINGLE_BUTTON, BUTTON_PAD, DPAD, REFERENCE, UNBOUND) and
 * RADIAL_MENU / TOUCH_MENU (open question, parked digital for now).
 *
 * **Refinement note.** Adding TRIGGER unconditionally is correct but loose:
 * a binding_group in TRIGGER mode with only a FULL_PRESS activator (no
 * SOFT_PRESS) doesn't actually need motion capture. A future refinement
 * could tighten the predicate to inspect activator types — but that's a
 * bigger gating-coordinator change, deferred until the cost is felt.
 */
val ANALOG_MODES_REQUIRING_MOTION_CAPTURE: Set<BindingMode> = setOf(
    BindingMode.TRIGGER,
    BindingMode.JOYSTICK_MOVE,
    BindingMode.JOYSTICK_CAMERA,
    BindingMode.MOUSE_JOYSTICK,
    BindingMode.ABSOLUTE_MOUSE,
    BindingMode.SCROLL_WHEEL,
    BindingMode.TWO_D_SCROLL,
)

/** Convenience predicate over the analog-modes set; see [ANALOG_MODES_REQUIRING_MOTION_CAPTURE]. */
fun BindingMode.requiresMotionCapture(): Boolean = this in ANALOG_MODES_REQUIRING_MOTION_CAPTURE

object SourceModeCatalog {
    fun modesValidFor(source: InputSource): List<BindingMode> = when (source) {
        InputSource.BUTTON_DIAMOND -> listOf(BindingMode.BUTTON_PAD)
        InputSource.DPAD -> listOf(
            BindingMode.UNBOUND,
            BindingMode.DPAD,
            BindingMode.JOYSTICK_MOVE,
            BindingMode.MOUSE_JOYSTICK,
            BindingMode.SCROLL_WHEEL,
        )
        InputSource.LEFT_BUMPER, InputSource.RIGHT_BUMPER -> listOf(BindingMode.SINGLE_BUTTON)
        InputSource.LEFT_TRIGGER, InputSource.RIGHT_TRIGGER -> listOf(
            BindingMode.UNBOUND,
            BindingMode.TRIGGER,
            BindingMode.SINGLE_BUTTON,
        )
        InputSource.LEFT_JOYSTICK, InputSource.RIGHT_JOYSTICK -> listOf(
            BindingMode.UNBOUND,
            BindingMode.JOYSTICK_MOVE,
            BindingMode.JOYSTICK_CAMERA,
            BindingMode.MOUSE_JOYSTICK,
            BindingMode.DPAD,
            BindingMode.SCROLL_WHEEL,
            BindingMode.ABSOLUTE_MOUSE,
        )
        else -> emptyList()
    }
}
