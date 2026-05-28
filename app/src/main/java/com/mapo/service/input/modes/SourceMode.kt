package com.mapo.service.input.modes

import com.mapo.data.model.steam.BindingMode
import com.mapo.data.model.steam.InputSource
import com.mapo.service.input.AnalogEvent
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.sqrt
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
 * **What later bricks add:** richer modes ([BindingMode.TRIGGER]'s click threshold,
 * [BindingMode.SCROLL_WHEEL]'s wheel cadence, [BindingMode.JOYSTICK_MOVE]'s XInput
 * analog passthrough, etc.) extend this same interface with an `evaluate(...)` hook that
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
 * scroll rather than synthetic-digital edges (D2). Modes set a per-source
 * pixel/second velocity each time they evaluate; the production
 * implementation ([com.mapo.service.input.MouseEmitterImpl]) runs an
 * integration loop while any source's velocity is non-zero and drives
 * `dispatcher.injectMouseMove(dx, dy)` at roughly 120 Hz. When all sources
 * return to zero the loop exits — event-driven everywhere else, time-stepped
 * only while a continuous output is actually live.
 *
 * The contract intentionally does NOT take instantaneous `(dx, dy)` deltas
 * from modes: a mode that fires once on stick deflection and never again
 * (because the kernel goes silent at constant deflection) would freeze the
 * cursor. The velocity-slot model lets the integration loop re-apply the
 * latest known stick deflection across the silent window between source
 * events.
 */
interface MouseEmitter {
    /**
     * Set this source's contribution to cursor motion. `vxPxPerSec` and
     * `vyPxPerSec` are in screen-pixels per second; passing `0f, 0f` removes
     * the source from the active set (returning to deadzone center). Both LJ
     * and RJ can drive the cursor concurrently — their contributions sum.
     */
    fun setStickVelocity(source: InputSource, vxPxPerSec: Float, vyPxPerSec: Float)

    /**
     * Clear all per-source velocity contributions. Called from
     * `InputEvaluator.flushAnalog` on profile / action-set switch so a
     * deflected stick doesn't leak motion across set boundaries.
     */
    fun clearAllVelocities()

    companion object {
        /** No-op sink for digital modes and for tests. */
        val NOOP: MouseEmitter = object : MouseEmitter {
            override fun setStickVelocity(source: InputSource, vxPxPerSec: Float, vyPxPerSec: Float) {}
            override fun clearAllVelocities() {}
        }
    }
}

/**
 * Pass-through: Mapo does not intercept this source. No sub-inputs are accepted,
 * so the compile step drops any rows under a binding group in this mode and the
 * activator engine never sees a configured input for the source. **Crucially,
 * the runtime returns false from `onKeyEvent` for inputs under a
 * `DEVICE_DEFAULT` source** — Android's hardware-native input flows untouched
 * to the foreground app (e.g. physical face button A still produces native A
 * to the launched game).
 *
 * This is the default mode for analog-capable sources (sticks, dpad, triggers)
 * on freshly-seeded profiles. The user must explicitly pick an analog mode for
 * Mapo to start interpreting the source, which is also what gates the
 * [ShizukuMotionCoordinator][com.mapo.service.shizuku.ShizukuMotionCoordinator]'s
 * `/dev/input` enumeration — keeping `:shizuku-service` idle (battery) on
 * profiles that haven't opted in.
 *
 * **Distinct from [NoneMode]** ([BindingMode.NONE]) which intercepts and
 * silences. See [feedback_none_vs_device_default_distinction.md].
 */
object DeviceDefaultMode : SourceMode {
    override val mode: BindingMode = BindingMode.DEVICE_DEFAULT
    override fun validInputs(): Set<String> = emptySet()
}

/**
 * Silence: Mapo intercepts this source and emits nothing. The accessibility
 * service consumes the event (returns true from `onKeyEvent`); no
 * `BindingOutput` dispatches. The hardware-native event is suppressed —
 * the foreground app sees nothing for this input.
 *
 * **Distinct from [DeviceDefaultMode]** ([BindingMode.DEVICE_DEFAULT]) which
 * lets the hardware-native event pass through. Steam Input parity for the
 * "None" mode dropdown option.
 */
object NoneMode : SourceMode {
    override val mode: BindingMode = BindingMode.NONE
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
 * 4-direction directional pad. Sub-inputs: `dpad_up`, `dpad_down`, `dpad_left`,
 * `dpad_right`, `click`. The four direction keys flow from physical
 * `KEYCODE_DPAD_UP/DOWN/LEFT/RIGHT` via the accessibility service for the [DPAD]
 * source. When this mode is selected on a *joystick* source (LEFT_JOYSTICK /
 * RIGHT_JOYSTICK), [evaluate] quantizes the analog (x, y) into the same
 * sub-input vocabulary — WASD-style movement from the stick.
 *
 * `click` is the stick-click sub-input — pass-through to the activator engine.
 *
 * **Settings (Steam-defaults-tuned for "movement" feel):**
 *  - `dpad_layout` (`"4_way"` / `"8_way"`, default `"4_way"`) — quantization for
 *    analog evaluation. `4_way` picks the dominant axis (|x| vs |y|); only one
 *    direction at a time. `8_way` allows two adjacent directions simultaneously
 *    (a NE push emits `dpad_up` + `dpad_right`). Inert when the mode is
 *    attached to the digital DPAD source — physical `KEYCODE_DPAD_*` always
 *    produces a single direction per press.
 *  - `inner_deadzone` (0..1, default 0.20) — radial deadband for analog
 *    evaluation. Below this magnitude no direction emits. Higher than cursor
 *    modes' 0.10 because a spurious dpad fire is jarring vs. cursor noise
 *    being negligible.
 *  - `outer_deadzone` (0..1, default 0.05) — release-side hysteresis floor for
 *    analog evaluation. Once any direction is latched, the radial magnitude
 *    must drop below `(inner_deadzone - outer_deadzone)` for the directions
 *    to release. Same shape as [TriggerSettings.softHysteresis]; prevents
 *    direction-flutter at the rim of the deadband.
 *
 * **Why DPAD source is skipped in [evaluate].** Controllers vary: some report
 * the physical dpad as `BTN_DPAD_*` key events only, some as `ABS_HAT0X/Y`
 * axis events only, some as both. The accessibility service path through
 * `onKeyEvent` is canonical for the digital DPAD source — it always fires
 * cleanly on devices that report key events. To avoid double-firing
 * `dpad_up` on controllers that report both, [evaluate] short-circuits
 * for [InputSource.DPAD] and lets the digital path own the emit. Revisit if
 * a target device ships HAT-only with no key events for the dpad.
 *
 * **8-way axial threshold.** A diagonal push (e.g. x=0.7, y=-0.7) trivially
 * passes inner_deadzone, so both `dpad_up` and `dpad_right` need to fire.
 * The per-axis floor is the inner_deadzone projected onto the diagonal —
 * `inner_deadzone * sin(45°) ≈ inner_deadzone * 0.707`. With the 0.20
 * default this is ≈0.141, so (0.7, -0.15) emits Up+Right but (0.7, -0.05)
 * emits only Right. Matches typical Steam-Input feel for low-angle pushes.
 */
object DpadMode : SourceMode {
    override val mode: BindingMode = BindingMode.DPAD
    override fun validInputs(): Set<String> = INPUTS
    override fun defaultSettingsJson(): String = DpadSettings.DEFAULT_JSON
    private val INPUTS = setOf("dpad_up", "dpad_down", "dpad_left", "dpad_right", "click")

    override fun evaluate(
        reading: AnalogEvent,
        ctx: ModeContext,
        digitalEmit: (subInput: String, isDown: Boolean) -> Unit,
        mouse: MouseEmitter,
    ) {
        // The digital DPAD source emits dpad_* sub-inputs via the accessibility
        // service's KEYCODE_DPAD_* path. Skip analog evaluation here to avoid
        // double-fire on controllers that report both BTN_DPAD_* and ABS_HAT0*.
        if (reading.source == InputSource.DPAD) return

        val settings = DpadSettings.parse(ctx.settingsJson)
        val priorUp = ctx.priorLatched["dpad_up"] == true
        val priorDown = ctx.priorLatched["dpad_down"] == true
        val priorRight = ctx.priorLatched["dpad_right"] == true
        val priorLeft = ctx.priorLatched["dpad_left"] == true

        val mag = sqrt(reading.x * reading.x + reading.y * reading.y).coerceIn(0f, 1f)
        val anyPrior = priorUp || priorDown || priorRight || priorLeft
        val releaseFloor = (settings.innerDeadzone - settings.outerDeadzone).coerceAtLeast(0f)
        val active = if (anyPrior) mag >= releaseFloor else mag >= settings.innerDeadzone

        // y > 0 is screen-down (matches AnalogEvent's documented convention) — so
        // pushing the stick UP gives y < 0 and emits dpad_up. WASD-natural.
        val wantUp: Boolean
        val wantDown: Boolean
        val wantRight: Boolean
        val wantLeft: Boolean
        if (!active) {
            wantUp = false; wantDown = false; wantRight = false; wantLeft = false
        } else if (settings.dpadLayout == DpadSettings.LAYOUT_8_WAY) {
            val axial = settings.innerDeadzone * AXIAL_PROJECTION_45
            wantUp = reading.y < -axial
            wantDown = reading.y > axial
            wantRight = reading.x > axial
            wantLeft = reading.x < -axial
        } else {
            // 4_way: dominant-axis quantization. Ties (|x| == |y| exactly) fall to
            // the vertical branch — arbitrary but stable.
            if (abs(reading.x) > abs(reading.y)) {
                wantUp = false; wantDown = false
                wantRight = reading.x > 0f; wantLeft = reading.x < 0f
            } else {
                wantUp = reading.y < 0f; wantDown = reading.y > 0f
                wantRight = false; wantLeft = false
            }
        }

        if (wantUp != priorUp) digitalEmit("dpad_up", wantUp)
        if (wantDown != priorDown) digitalEmit("dpad_down", wantDown)
        if (wantRight != priorRight) digitalEmit("dpad_right", wantRight)
        if (wantLeft != priorLeft) digitalEmit("dpad_left", wantLeft)
    }

    /** sin(45°) — per-axis floor projection for 8-way diagonal emit. */
    private const val AXIAL_PROJECTION_45 = 0.7071068f
}

/**
 * Parsed [DpadMode] settings. Tolerant of missing keys; falls back to Steam-shaped
 * defaults so a binding_group seeded before this brick (e.g. one that only had
 * `dpad_layout` in its JSON, or `{}` from a prior StubMode default) keeps
 * working without a migration.
 */
internal data class DpadSettings(
    val innerDeadzone: Float,
    val outerDeadzone: Float,
    val dpadLayout: String,
) {
    companion object {
        const val DEFAULT_INNER_DEADZONE = 0.20f
        const val DEFAULT_OUTER_DEADZONE = 0.05f
        const val LAYOUT_4_WAY = "4_way"
        const val LAYOUT_8_WAY = "8_way"
        const val DEFAULT_LAYOUT = LAYOUT_4_WAY

        val DEFAULT_JSON =
            """{"inner_deadzone":$DEFAULT_INNER_DEADZONE,"outer_deadzone":$DEFAULT_OUTER_DEADZONE,"dpad_layout":"$DEFAULT_LAYOUT"}"""

        fun parse(json: String): DpadSettings {
            if (json.isBlank()) return defaults()
            return try {
                val obj = JSONObject(json)
                val layoutRaw = obj.optString("dpad_layout", DEFAULT_LAYOUT)
                val layout = if (layoutRaw == LAYOUT_4_WAY || layoutRaw == LAYOUT_8_WAY) layoutRaw else DEFAULT_LAYOUT
                DpadSettings(
                    innerDeadzone = obj.optDouble("inner_deadzone", DEFAULT_INNER_DEADZONE.toDouble())
                        .toFloat().coerceIn(0f, 0.95f),
                    outerDeadzone = obj.optDouble("outer_deadzone", DEFAULT_OUTER_DEADZONE.toDouble())
                        .toFloat().coerceIn(0f, 0.95f),
                    dpadLayout = layout,
                )
            } catch (_: JSONException) {
                defaults()
            }
        }

        private fun defaults() = DpadSettings(
            innerDeadzone = DEFAULT_INNER_DEADZONE,
            outerDeadzone = DEFAULT_OUTER_DEADZONE,
            dpadLayout = DEFAULT_LAYOUT,
        )
    }
}

/**
 * Analog-trigger source (LEFT_TRIGGER, RIGHT_TRIGGER). Two sub-inputs:
 *  - `full_pull` — the hardware threshold sub-input. Fires via the accessibility
 *    service's `KEYCODE_BUTTON_L2` / `KEYCODE_BUTTON_R2` digital edge. Any
 *    activator type (FULL_PRESS, LONG_PRESS, DOUBLE_PRESS, RELEASE_PRESS,
 *    CHORDED_PRESS) is valid on this row. Steam-verbatim name; UI label "L2/R2 Full Pull".
 *  - `soft_pull` — the analog soft-pull sub-input. Fires via [evaluate] on
 *    analog readings from the Shizuku motion stream when the magnitude
 *    crosses `soft_threshold` (with `soft_hysteresis` on release). Any
 *    activator type is valid here too — most users pick FULL_PRESS so the
 *    soft pull fires their chosen command once per pull. UI label "L2/R2 Soft Pull".
 *
 * **Why a separate sub-input and not a SOFT_PRESS activator type on `full_pull`?**
 * Steam Input exposes BOTH a SOFT_PRESS activator and a Soft Pull trigger slot,
 * which is the same behavior expressed two ways. Mapo unifies on the sub-input
 * model — one place for the user to express "fire when the trigger is in the
 * soft range" — and retires SOFT_PRESS as a picker option (it stays in the
 * enum solely so VDF import can translate Steam's `SOFT_PRESS` activator into
 * a Mapo activator on the `"soft_pull"` sub-input).
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
    private val INPUTS = setOf(FULL_PULL_SUB_INPUT, SOFT_PULL_SUB_INPUT)

    /** Sub-input key for the digital full-pull row. Steam-verbatim. */
    const val FULL_PULL_SUB_INPUT: String = "full_pull"

    /** Sub-input key for the analog soft-pull row. Steam-verbatim. */
    const val SOFT_PULL_SUB_INPUT: String = "soft_pull"

    override fun evaluate(
        reading: AnalogEvent,
        ctx: ModeContext,
        digitalEmit: (subInput: String, isDown: Boolean) -> Unit,
        mouse: MouseEmitter,
    ) {
        val settings = TriggerSettings.parse(ctx.settingsJson)
        val magnitude = reading.x  // triggers are 0..1 on x; y is always 0
        val priorLatched = ctx.priorLatched[SOFT_PULL_SUB_INPUT] == true
        val nowLatched = if (priorLatched) {
            // Stay latched until magnitude drops below the lower hysteresis band.
            magnitude >= (settings.softThreshold - settings.softHysteresis)
        } else {
            // Latch when magnitude crosses the threshold upward.
            magnitude >= settings.softThreshold
        }
        if (nowLatched != priorLatched) {
            digitalEmit(SOFT_PULL_SUB_INPUT, nowLatched)
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
 * Stick-to-cursor analog mode. Steam's "Joystick Mouse" — joystick input,
 * mouse cursor output. The user feels like they're driving a joystick; the
 * game receives mouse motion. Sub-inputs: `click` (stick click), `outer_ring`
 * (Outer Ring Command — fires at rim threshold). `touch` (capacitive) is
 * Steam-canonical for this mode but Mapo's target hardware lacks
 * capacitive sticks, so it's omitted from the local picker.
 *
 * **Settings — Steam-shaped defaults, tuned for cursor precision:**
 *  - `deadzone` (0..1, default 0.10) — radial inner deadzone applied
 *    in normalized stick units.
 *  - `sensitivity` (px/sec at full deflection, default 800) — velocity at
 *    the outer edge of stick travel. Cursor-friendly default; FPS camera
 *    work can pick the "camera" preset which uses
 *    [StickToMouseSettings.JOYSTICK_CAMERA_DEFAULTS] as its base.
 *  - `exponent` (response curve power, default 1.6) — `>1` makes near-center
 *    deflection slower (precision), `<1` makes it faster (snap). Steam
 *    Input's "Sensitivity Curve" maps roughly to this.
 *  - `invert_y` (bool, default false) — flips the y axis; FPS conventions
 *    vary by player.
 *
 * **Camera tuning preset.** Phase 6's `JoystickCameraMode` collapsed into this
 * mode in Phase 7 — same primitive (velocity into [MouseEmitter]), just
 * different default settings. Users who want camera feel pick the "camera"
 * preset in the Cog menu (Phase 7 Brick F); the underlying math is the same.
 *
 * **What gets emitted.** Each [evaluate] call samples the stick's normalized
 * (x, y), applies radial deadzone + curve + sensitivity, and writes the
 * resulting (vx, vy) pixels-per-second into the [MouseEmitter] for this
 * source. The emitter's integration loop keeps the cursor moving while the
 * stick is held.
 */
object JoystickMouseMode : SourceMode {
    override val mode: BindingMode = BindingMode.JOYSTICK_MOUSE
    override fun validInputs(): Set<String> = INPUTS
    override fun defaultSettingsJson(): String = StickToMouseSettings.MOUSE_JOYSTICK_DEFAULT_JSON
    private val INPUTS = setOf("click", "outer_ring")

    override fun evaluate(
        reading: AnalogEvent,
        ctx: ModeContext,
        digitalEmit: (subInput: String, isDown: Boolean) -> Unit,
        mouse: MouseEmitter,
    ) {
        val settings = StickToMouseSettings.parse(ctx.settingsJson, StickToMouseSettings.MOUSE_JOYSTICK_DEFAULTS)
        val (vx, vy) = settings.toVelocity(reading.x, reading.y)
        mouse.setStickVelocity(reading.source, vx, vy)
    }
}

/**
 * Parsed settings + math for [JoystickMouseMode]. Tolerant of missing keys;
 * falls back to the caller-supplied defaults. [MOUSE_JOYSTICK_DEFAULTS] is
 * the cursor-tuned preset (Phase 7 Brick A default); [JOYSTICK_CAMERA_DEFAULTS]
 * is the camera-tuned preset preserved from Phase 6's removed `JoystickCameraMode`.
 * The Cog menu (Phase 7 Brick F) will let users pick between presets.
 *
 * **toVelocity math:**
 *  1. Magnitude `m = sqrt(x² + y²)`, clamped to [0, 1].
 *  2. If `m <= deadzone` → return (0, 0). Cursor halts cleanly at rest.
 *  3. Rescale `m' = (m - deadzone) / (1 - deadzone)` so a stick just past
 *     the deadzone produces non-zero velocity (avoids dead-band stutter).
 *  4. Apply curve: `m'' = m'^exponent`.
 *  5. Direction is the original `(x, y) / m` unit vector; scale to
 *     `m'' * sensitivity` pixels/sec on each axis.
 *  6. `invert_y` flips the y output sign (UI flag, user preference).
 */
internal data class StickToMouseSettings(
    val deadzone: Float,
    val sensitivity: Float,
    val exponent: Float,
    val invertY: Boolean,
) {
    fun toVelocity(x: Float, y: Float): Pair<Float, Float> {
        val magnitude = sqrt(x * x + y * y).coerceIn(0f, 1f)
        if (magnitude <= deadzone) return 0f to 0f
        val rescaled = ((magnitude - deadzone) / (1f - deadzone)).coerceIn(0f, 1f)
        val shaped = rescaled.toDouble().pow(exponent.toDouble()).toFloat()
        val outputMag = shaped * sensitivity
        // Direction from raw (x, y); divide by raw magnitude (not rescaled) so
        // direction is preserved across the deadzone rescale.
        val ux = x / magnitude
        val uy = y / magnitude
        val vx = ux * outputMag
        val vy = uy * outputMag * if (invertY) -1f else 1f
        return vx to vy
    }

    companion object {
        const val DEFAULT_DEADZONE_MOUSE = 0.10f
        const val DEFAULT_SENSITIVITY_MOUSE = 800f
        const val DEFAULT_EXPONENT_MOUSE = 1.6f

        const val DEFAULT_DEADZONE_CAMERA = 0.15f
        const val DEFAULT_SENSITIVITY_CAMERA = 1400f
        const val DEFAULT_EXPONENT_CAMERA = 2.0f

        val MOUSE_JOYSTICK_DEFAULTS = StickToMouseSettings(
            deadzone = DEFAULT_DEADZONE_MOUSE,
            sensitivity = DEFAULT_SENSITIVITY_MOUSE,
            exponent = DEFAULT_EXPONENT_MOUSE,
            invertY = false,
        )
        val JOYSTICK_CAMERA_DEFAULTS = StickToMouseSettings(
            deadzone = DEFAULT_DEADZONE_CAMERA,
            sensitivity = DEFAULT_SENSITIVITY_CAMERA,
            exponent = DEFAULT_EXPONENT_CAMERA,
            invertY = false,
        )
        val MOUSE_JOYSTICK_DEFAULT_JSON =
            """{"deadzone":$DEFAULT_DEADZONE_MOUSE,"sensitivity":$DEFAULT_SENSITIVITY_MOUSE,"exponent":$DEFAULT_EXPONENT_MOUSE,"invert_y":false}"""
        val JOYSTICK_CAMERA_DEFAULT_JSON =
            """{"deadzone":$DEFAULT_DEADZONE_CAMERA,"sensitivity":$DEFAULT_SENSITIVITY_CAMERA,"exponent":$DEFAULT_EXPONENT_CAMERA,"invert_y":false}"""

        fun parse(json: String, defaults: StickToMouseSettings): StickToMouseSettings {
            if (json.isBlank()) return defaults
            return try {
                val obj = JSONObject(json)
                StickToMouseSettings(
                    deadzone = obj.optDouble("deadzone", defaults.deadzone.toDouble()).toFloat()
                        .coerceIn(0f, 0.95f),
                    sensitivity = obj.optDouble("sensitivity", defaults.sensitivity.toDouble()).toFloat()
                        .coerceAtLeast(0f),
                    exponent = obj.optDouble("exponent", defaults.exponent.toDouble()).toFloat()
                        .coerceIn(0.1f, 10f),
                    invertY = obj.optBoolean("invert_y", defaults.invertY),
                )
            } catch (_: JSONException) {
                defaults
            }
        }
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
 * Resolve the runtime [SourceMode] handler for a [BindingMode] enum value.
 * Implemented modes return their singleton handler; everything else falls
 * through to [StubMode] — which keeps the compile path non-strict for modes
 * whose runtime ships in later Phase 7 bricks (Mouse Region / Flick Stick /
 * Gyro modes / Joystick Move) and out-of-scope modes (Scroll Wheel, Hotbar /
 * Radial / Touch Menu).
 */
fun BindingMode.handler(): SourceMode = when (this) {
    BindingMode.DEVICE_DEFAULT -> DeviceDefaultMode
    BindingMode.NONE -> NoneMode
    BindingMode.SINGLE_BUTTON -> SingleButtonMode
    BindingMode.BUTTON_PAD -> ButtonPadMode
    BindingMode.DPAD -> DpadMode
    BindingMode.TRIGGER -> TriggerMode
    BindingMode.JOYSTICK_MOUSE -> JoystickMouseMode
    BindingMode.JOYSTICK_MOVE,
    BindingMode.FLICK_STICK,
    BindingMode.MOUSE_REGION,
    BindingMode.SCROLL_WHEEL,
    BindingMode.REFERENCE,
    BindingMode.GYRO_TO_MOUSE,
    BindingMode.GYRO_TO_JOYSTICK_CAMERA,
    BindingMode.GYRO_TO_JOYSTICK_DEFLECTION,
    BindingMode.DIRECTIONAL_SWIPE,
    BindingMode.RADIAL_MENU,
    BindingMode.TOUCH_MENU,
    BindingMode.HOTBAR_MENU -> StubMode(this)
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
 * Modes whose runtime behavior depends on analog input capture (Brick F's
 * gating predicate). When at least one source in the resolved-active set/layer
 * has a mode in this set, the
 * [ShizukuMotionCoordinator][com.mapo.service.shizuku.ShizukuMotionCoordinator]
 * enables the Shizuku UserService's `/dev/input` reader; otherwise the service
 * stays idle so CPU + battery are unaffected outside the active gameplay
 * window.
 *
 * **What's in:** the analog stick / mouse / scroll modes whose `evaluate()`
 * hook needs a motion stream, plus [BindingMode.TRIGGER] (added in Brick 5
 * for Soft_Press — the soft threshold can only be detected from the analog
 * pull magnitude, not the hardware click edge), plus [BindingMode.DPAD]
 * (added in Brick K — DpadMode's analog `evaluate()` quantizes a joystick's
 * (x, y) into synthetic N/S/E/W edges; the physical DPAD source skips the
 * analog path and uses `KEYCODE_DPAD_*` through the accessibility service).
 * **What's out:** purely-digital modes (SINGLE_BUTTON, BUTTON_PAD, REFERENCE,
 * UNBOUND) and RADIAL_MENU / TOUCH_MENU (open question, parked digital for now).
 *
 * **Refinement note.** Adding TRIGGER and DPAD unconditionally is correct
 * but loose: a binding_group in TRIGGER mode with only a FULL_PRESS
 * activator (no SOFT_PRESS) doesn't actually need motion capture; a
 * binding_group in DPAD mode attached to the physical DPAD source doesn't
 * either. A future refinement could tighten the predicate by inspecting the
 * binding_group's source + activator types — but that's a bigger
 * gating-coordinator change, deferred until the cost is felt.
 */
val ANALOG_MODES_REQUIRING_MOTION_CAPTURE: Set<BindingMode> = setOf(
    BindingMode.TRIGGER,
    BindingMode.DPAD,
    BindingMode.JOYSTICK_MOVE,
    BindingMode.JOYSTICK_MOUSE,
    BindingMode.FLICK_STICK,
    BindingMode.MOUSE_REGION,
    BindingMode.SCROLL_WHEEL,
    // Gyro modes use the SensorManager pipeline (Phase 7 Brick D), not Shizuku
    // motion — they don't need the /dev/input reader and so are intentionally
    // omitted from this set. The gating predicate for the gyro pipeline lives
    // separately (see Phase 7 Brick D).
)

/** Convenience predicate over the analog-modes set; see [ANALOG_MODES_REQUIRING_MOTION_CAPTURE]. */
fun BindingMode.requiresMotionCapture(): Boolean = this in ANALOG_MODES_REQUIRING_MOTION_CAPTURE

/**
 * Source-and-mode-aware sub-input vocabulary lookup. Sub-inputs vary by both
 * the physical source and the selected mode — e.g. face-buttons-in-Directional-
 * Pad mode binds A/B/X/Y, while joystick-in-Directional-Pad mode binds
 * Up/Down/Left/Right. The mode-only [SourceMode.validInputs] is still defined
 * on each handler for convenience / tests / defaults, but **the compile path
 * and picker UI should query this function** for correctness.
 *
 * Sourced from [reference_steam_sub_inputs_per_mode.md]. Pairs not covered
 * here fall through to the mode handler's default `validInputs()`.
 *
 * `touch` (capacitive-stick sub-input on Steam joystick modes) is intentionally
 * omitted — Mapo's target hardware (AYN Thor / Odin 2 Mini / Anbernic / Retroid)
 * lacks capacitive sticks. VDF import of a binding under `touch` lands as a
 * stored-but-inert binding (no runtime fire on Mapo's devices).
 */
fun validInputsFor(source: InputSource, mode: BindingMode): Set<String> = when (source) {
    InputSource.BUTTON_DIAMOND -> when (mode) {
        BindingMode.BUTTON_PAD, BindingMode.DPAD -> FACE_BUTTONS
        BindingMode.JOYSTICK_MOVE -> emptySet()
        BindingMode.RADIAL_MENU -> emptySet()  // menu sub-inputs are touch_menu_button_<N>; deferred
        else -> mode.handler().validInputs()
    }
    InputSource.DPAD -> when (mode) {
        BindingMode.DPAD, BindingMode.BUTTON_PAD -> DPAD_DIRECTIONS
        BindingMode.JOYSTICK_MOVE -> emptySet()
        BindingMode.RADIAL_MENU, BindingMode.HOTBAR_MENU -> emptySet()
        else -> mode.handler().validInputs()
    }
    InputSource.LEFT_TRIGGER, InputSource.RIGHT_TRIGGER -> when (mode) {
        BindingMode.TRIGGER -> setOf(TriggerMode.FULL_PULL_SUB_INPUT, TriggerMode.SOFT_PULL_SUB_INPUT)
        BindingMode.SINGLE_BUTTON -> setOf(TriggerMode.FULL_PULL_SUB_INPUT)
        else -> mode.handler().validInputs()
    }
    InputSource.LEFT_JOYSTICK, InputSource.RIGHT_JOYSTICK -> when (mode) {
        BindingMode.JOYSTICK_MOVE,
        BindingMode.JOYSTICK_MOUSE,
        BindingMode.FLICK_STICK,
        BindingMode.MOUSE_REGION -> JOYSTICK_CLICK_OUTER_RING
        BindingMode.DPAD -> DPAD_DIRECTIONS + JOYSTICK_CLICK_OUTER_RING
        BindingMode.SCROLL_WHEEL -> JOYSTICK_CLICK_OUTER_RING  // + scroll_clockwise/etc. when SCROLL_WHEEL runtime lands
        BindingMode.RADIAL_MENU, BindingMode.TOUCH_MENU, BindingMode.HOTBAR_MENU -> emptySet()
        else -> mode.handler().validInputs()
    }
    InputSource.LEFT_BUMPER, InputSource.RIGHT_BUMPER -> when (mode) {
        BindingMode.SINGLE_BUTTON -> setOf("click")
        else -> mode.handler().validInputs()
    }
    InputSource.GYRO -> when (mode) {
        BindingMode.DPAD -> DPAD_DIRECTIONS
        BindingMode.DIRECTIONAL_SWIPE -> setOf("dpad_up", "dpad_down")  // direction labels TBD; placeholder
        BindingMode.GYRO_TO_MOUSE,
        BindingMode.GYRO_TO_JOYSTICK_CAMERA,
        BindingMode.GYRO_TO_JOYSTICK_DEFLECTION,
        BindingMode.MOUSE_REGION -> emptySet()
        BindingMode.RADIAL_MENU, BindingMode.TOUCH_MENU, BindingMode.HOTBAR_MENU -> emptySet()
        else -> mode.handler().validInputs()
    }
    // SWITCH_* and TRACKPAD sources: fall through to the mode handler's default.
    // Switches don't get a picker (catalog returns emptyList) so this branch
    // rarely runs; trackpads are out of scope for Mapo's target hardware.
    else -> mode.handler().validInputs()
}

private val FACE_BUTTONS = setOf("button_a", "button_b", "button_x", "button_y")
private val DPAD_DIRECTIONS = setOf("dpad_up", "dpad_down", "dpad_left", "dpad_right")
private val JOYSTICK_CLICK_OUTER_RING = setOf("click", "outer_ring")

/**
 * Source-aware compile-path acceptance check. Equivalent to
 * `validInputsFor(source, mode).contains(inputKey)`. Replaces the old mode-only
 * [SourceMode.accepts] for callers that have source context available.
 */
fun acceptsFor(source: InputSource, mode: BindingMode, inputKey: String): Boolean =
    inputKey in validInputsFor(source, mode)

/**
 * Catalog of which [BindingMode]s the user can pick for each physical
 * [InputSource] via the Remap Controls subheader mode dropdown.
 *
 * Authoritative list per `reference_steam_source_mode_dropdowns.md` — Mapo
 * follows Steam's per-source dropdown verbatim, plus universal
 * [BindingMode.DEVICE_DEFAULT] and [BindingMode.NONE] availability
 * across every source that exposes a picker (deliberate Mapo divergence —
 * Steam's "None" silences; Mapo's [Device Default] is pass-through to
 * hardware-native, which Steam has no analog for).
 *
 * Empty list for sources without a behavioral dropdown (switches, trackpads
 * on Mapo's target hardware) — the picker UI hides itself for empty lists.
 *
 * Some modes returned here have stub runtimes (FLICK_STICK / MOUSE_REGION /
 * gyro modes / scroll / menus). Selecting one persists the mode + sub-input
 * bindings; the activator engine routes through [StubMode] until the
 * matching runtime lands in a later brick.
 */
object SourceModeCatalog {
    fun modesValidFor(source: InputSource): List<BindingMode> = when (source) {
        InputSource.BUTTON_DIAMOND -> listOf(
            BindingMode.DEVICE_DEFAULT,
            BindingMode.NONE,
            BindingMode.BUTTON_PAD,
            BindingMode.DPAD,
            BindingMode.JOYSTICK_MOVE,
            BindingMode.RADIAL_MENU,
        )
        InputSource.DPAD -> listOf(
            BindingMode.DEVICE_DEFAULT,
            BindingMode.NONE,
            BindingMode.JOYSTICK_MOVE,
            BindingMode.DPAD,
            BindingMode.BUTTON_PAD,
            BindingMode.RADIAL_MENU,
            BindingMode.HOTBAR_MENU,
        )
        InputSource.LEFT_BUMPER, InputSource.RIGHT_BUMPER -> listOf(
            BindingMode.DEVICE_DEFAULT,
            BindingMode.NONE,
            BindingMode.SINGLE_BUTTON,
        )
        InputSource.LEFT_TRIGGER, InputSource.RIGHT_TRIGGER -> listOf(
            BindingMode.DEVICE_DEFAULT,
            BindingMode.NONE,
            BindingMode.TRIGGER,
            BindingMode.SINGLE_BUTTON,
        )
        InputSource.LEFT_JOYSTICK, InputSource.RIGHT_JOYSTICK -> listOf(
            BindingMode.DEVICE_DEFAULT,
            BindingMode.NONE,
            BindingMode.JOYSTICK_MOUSE,
            BindingMode.FLICK_STICK,
            BindingMode.MOUSE_REGION,
            BindingMode.JOYSTICK_MOVE,
            BindingMode.DPAD,
            BindingMode.SCROLL_WHEEL,
            BindingMode.RADIAL_MENU,
            BindingMode.TOUCH_MENU,
            BindingMode.HOTBAR_MENU,
        )
        InputSource.GYRO -> listOf(
            BindingMode.DEVICE_DEFAULT,
            BindingMode.NONE,
            BindingMode.GYRO_TO_MOUSE,
            BindingMode.GYRO_TO_JOYSTICK_CAMERA,
            BindingMode.GYRO_TO_JOYSTICK_DEFLECTION,
            BindingMode.MOUSE_REGION,
            BindingMode.DPAD,
            BindingMode.DIRECTIONAL_SWIPE,
            BindingMode.RADIAL_MENU,
            BindingMode.TOUCH_MENU,
            BindingMode.HOTBAR_MENU,
        )
        // Switches (Start/Select/Back paddles) and trackpads — no dropdown.
        // Switches per Steam can't be mode-shifted; trackpads aren't on Mapo's
        // target hardware. The picker UI hides itself for empty lists.
        else -> emptyList()
    }
}
