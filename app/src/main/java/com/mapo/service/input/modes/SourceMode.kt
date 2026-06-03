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
 *
 * [gamepad] is the virtual XInput gamepad sink for analog modes that output as
 * XInput axes (Joystick Move, Mouse Region's gamepad-driven NONE-mode silence).
 * Lives on the context rather than as a separate evaluate() parameter to
 * keep the SourceMode interface signature stable (every mode override would
 * otherwise need to thread the param); modes that don't care about the
 * gamepad just ignore the field. Defaults to NOOP so test fixtures don't
 * have to wire one.
 */
data class ModeContext(
    val source: InputSource,
    val settingsJson: String,
    val priorLatched: Map<String, Boolean>,
    val activeLayerIds: List<Long>,
    val gamepad: GamepadEmitter = GamepadEmitter.NOOP,
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
     * Mouse Region (Brick C.4) absolute-position path. `xFrac` / `yFrac` are
     * fractional screen coordinates in `[0..1]`, where `(0.5, 0.5)` is screen
     * center. The emitter pushes one delta-to-target inject to the dispatcher
     * — no integration loop, because absolute targeting wants the cursor to
     * stay put when the stick is held still (which is what happens when the
     * kernel goes silent at constant deflection — the desired Mouse Region
     * behavior, unlike velocity modes).
     *
     * Multi-source coexistence: last-call-wins per-source. If LJ and RJ are
     * both in Mouse Region simultaneously, whichever source emits most
     * recently wins. A pathological config Mapo doesn't try to deconflict.
     */
    fun setStickAbsoluteTarget(source: InputSource, xFrac: Float, yFrac: Float)

    /**
     * Clear this source's absolute-target slot (called when the stick returns
     * to the radial deadzone). The cursor stays at its current position; we
     * just stop pushing absolute targets for this source until the user
     * deflects again. Pair with [setStickAbsoluteTarget].
     */
    fun clearStickAbsoluteTarget(source: InputSource)

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
            override fun setStickAbsoluteTarget(source: InputSource, xFrac: Float, yFrac: Float) {}
            override fun clearStickAbsoluteTarget(source: InputSource) {}
            override fun clearAllVelocities() {}
        }
    }
}

/**
 * **Brick C** — continuous-output sink for analog modes that emit as virtual
 * XInput gamepad state instead of mouse motion (Joystick Move) or synthetic
 * digital edges (Dpad). The production implementation
 * ([com.mapo.service.shizuku.ShizukuGamepadInjector]) maintains a cached
 * full-gamepad-state snapshot and pushes it to the `:shizuku-service` UID-2000
 * UserService over AIDL each time a contributing source updates its slice.
 *
 * Modes call into this from [SourceMode.evaluate] (accessed via
 * [ModeContext.gamepad]) — typically once per AnalogEvent, passing the
 * source's normalized contribution.
 *
 * Stick conventions:
 *  - x in -1.0..+1.0, +x = stick pushed right
 *  - y in -1.0..+1.0, +y = stick pushed *down* (matches AnalogEvent's convention).
 *    Mode handlers that need "game-up = positive" (e.g. for an FPS movement stick)
 *    apply their own inversion before calling.
 */
/**
 * Per-source virtual-gamepad façade. **All set methods are source-keyed**
 * so multiple sources (e.g. a physical stick + gyro-driven Camera) can
 * contribute to the same virtual axis at the same time; the implementation
 * sums per-source contributions and clamps the result before pushing
 * through to the kernel uinput device.
 *
 * Example: with `LEFT_JOYSTICK` in JoystickMove mode and `GYRO` in
 * Joystick Deflection mode both writing the left stick, both contributions
 * are added together — the physical stick and gyro tilt move the character
 * cooperatively rather than overwriting each other.
 *
 * Add semantics:
 *  - **Sticks**: per-source `(x, y)` summed; each axis clamped to `[-1, +1]`.
 *  - **Triggers**: per-source values summed; clamped to `[0, +1]`.
 *  - **Hat**: per-source `(x, y)` integer contributions summed; each axis
 *    clamped to `{-1, 0, +1}`.
 *
 * Stick conventions:
 *  - x in -1.0..+1.0, +x = stick pushed right
 *  - y in -1.0..+1.0, +y = stick pushed *down* (matches AnalogEvent's convention).
 *    Mode handlers that need "game-up = positive" (e.g. for an FPS movement stick)
 *    apply their own inversion before calling.
 *
 * **Button** state is not per-source — buttons are owned by the physical
 * source they map to and never have multi-source overlap, so the simpler
 * direct-write API stays.
 */
interface GamepadEmitter {
    fun setLeftStick(source: InputSource, x: Float, y: Float)
    fun setRightStick(source: InputSource, x: Float, y: Float)
    /** Trigger value 0.0..1.0; clamped + mapped to 0..255 by the implementation. */
    fun setLeftTrigger(source: InputSource, v: Float)
    fun setRightTrigger(source: InputSource, v: Float)
    /** Dpad hat axis — pass -1, 0, or 1 per axis. */
    fun setDpadHat(source: InputSource, x: Int, y: Int)
    /**
     * Reset a source's contribution to zero across every axis the source
     * could be driving. Called from [InputEvaluator.flushAnalog] + when a
     * source transitions out of a gamepad-emitting mode so its residual
     * contribution doesn't leak into the summed output.
     */
    fun clearSource(source: InputSource)
    /** Press or release a gamepad button. `btnCode` is a `UinputGamepad.Buttons.*` int. */
    fun setButton(btnCode: Int, pressed: Boolean)

    companion object {
        /** No-op sink for digital modes and for tests. */
        val NOOP: GamepadEmitter = object : GamepadEmitter {
            override fun setLeftStick(source: InputSource, x: Float, y: Float) {}
            override fun setRightStick(source: InputSource, x: Float, y: Float) {}
            override fun setLeftTrigger(source: InputSource, v: Float) {}
            override fun setRightTrigger(source: InputSource, v: Float) {}
            override fun setDpadHat(source: InputSource, x: Int, y: Int) {}
            override fun clearSource(source: InputSource) {}
            override fun setButton(btnCode: Int, pressed: Boolean) {}
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

    // Per-source integrated tilt angle (radians) + last-seen timestamp (ms),
    // used for GYRO source only. Joystick sources pass instantaneous
    // deflection values through and don't touch this map. Same shape and
    // reset discipline as [GyroToJoystickDeflectionMode.integratedAngle]
    // — see that KDoc for the drift / orientation-vector-as-future-fix
    // rationale.
    //
    // Layout: floatArrayOf(angleX, angleY, lastTimestampMs)
    private val gyroIntegratedAngle = mutableMapOf<InputSource, FloatArray>()

    /**
     * Public reset hook called from [InputEvaluator.flushAnalog] at profile
     * / action-set boundaries so accumulated gyro tilt doesn't leak across
     * configurations. Joystick paths are stateless and unaffected.
     */
    fun resetState() {
        gyroIntegratedAngle.clear()
    }

    /** Test seam — current integrated gyro tilt for the given source. */
    @androidx.annotation.VisibleForTesting
    internal fun integratedGyroAngleFor(source: InputSource): Pair<Float, Float>? =
        gyroIntegratedAngle[source]?.let { it[0] to it[1] }

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
        // Map (reading.x, reading.y) into the stick-deflection space [-1, +1]
        // that the rest of the math assumes:
        //  - Joystick sources: passthrough — readings are already normalized.
        //  - GYRO source: integrate rate to angle (rad), then scale by
        //    tilt_sensitivity so e.g. 0.2 rad of tilt (~11.5°) saturates.
        //    Same primitive as [GyroToJoystickDeflectionMode] so tilt-and-
        //    hold-as-dpad has the same lean-to-walk feel.
        val (effX, effY) = if (reading.source == InputSource.GYRO) {
            val (angleX, angleY) = accumulateGyro(reading)
            val s = settings.tiltSensitivity
            (angleX * s).coerceIn(-1f, 1f) to (angleY * s).coerceIn(-1f, 1f)
        } else {
            reading.x to reading.y
        }
        val priorUp = ctx.priorLatched["dpad_up"] == true
        val priorDown = ctx.priorLatched["dpad_down"] == true
        val priorRight = ctx.priorLatched["dpad_right"] == true
        val priorLeft = ctx.priorLatched["dpad_left"] == true

        val mag = sqrt(effX * effX + effY * effY).coerceIn(0f, 1f)
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
            wantUp = effY < -axial
            wantDown = effY > axial
            wantRight = effX > axial
            wantLeft = effX < -axial
        } else {
            // 4_way: dominant-axis quantization. Ties (|x| == |y| exactly) fall to
            // the vertical branch — arbitrary but stable.
            if (abs(effX) > abs(effY)) {
                wantUp = false; wantDown = false
                wantRight = effX > 0f; wantLeft = effX < 0f
            } else {
                wantUp = effY < 0f; wantDown = effY > 0f
                wantRight = false; wantLeft = false
            }
        }

        if (wantUp != priorUp) digitalEmit("dpad_up", wantUp)
        if (wantDown != priorDown) digitalEmit("dpad_down", wantDown)
        if (wantRight != priorRight) digitalEmit("dpad_right", wantRight)
        if (wantLeft != priorLeft) digitalEmit("dpad_left", wantLeft)
    }

    private fun accumulateGyro(reading: AnalogEvent): Pair<Float, Float> {
        val source = reading.source
        val state = gyroIntegratedAngle[source]
        if (state == null) {
            // First event after reset — seed the timestamp and return zero so
            // we don't dump a multi-second integral from the difference
            // between epoch 0 and the sensor's monotonic clock.
            gyroIntegratedAngle[source] = floatArrayOf(0f, 0f, reading.timestampMs.toFloat())
            return 0f to 0f
        }
        val dt = ((reading.timestampMs.toFloat() - state[2]) / 1000f).coerceIn(0f, MAX_GYRO_DT_SEC)
        // Anti-drift per-axis deadzone — same rationale as
        // [GyroToJoystickDeflectionMode.accumulate]. Pre-integration noise
        // gate keeps accumulated tilt from drifting into a phantom
        // direction emit when the device is stationary.
        val effX = if (abs(reading.x) < GYRO_ANTI_DRIFT_DEADZONE) 0f else reading.x
        val effY = if (abs(reading.y) < GYRO_ANTI_DRIFT_DEADZONE) 0f else reading.y
        state[0] += effX * dt
        state[1] += effY * dt
        state[2] = reading.timestampMs.toFloat()
        return state[0] to state[1]
    }

    /** sin(45°) — per-axis floor projection for 8-way diagonal emit. */
    private const val AXIAL_PROJECTION_45 = 0.7071068f

    // Pause-resume guard for the gyro integrator — see
    // [GyroToJoystickDeflectionMode.MAX_DT_SEC] for the same rationale.
    private const val MAX_GYRO_DT_SEC = 0.1f

    // Per-axis noise gate for gyro source — see
    // [GyroToJoystickDeflectionMode.ANTI_DRIFT_DEADZONE].
    private const val GYRO_ANTI_DRIFT_DEADZONE = 0.05f
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
    val tiltSensitivity: Float,
) {
    companion object {
        const val DEFAULT_INNER_DEADZONE = 0.20f
        const val DEFAULT_OUTER_DEADZONE = 0.05f
        const val LAYOUT_4_WAY = "4_way"
        const val LAYOUT_8_WAY = "8_way"
        const val DEFAULT_LAYOUT = LAYOUT_4_WAY
        // GYRO-only: scales integrated tilt angle (radians) into stick-
        // deflection-equivalent units before the deadzone check. 5.0 means
        // ~0.2 rad (≈11.5°) of tilt clamps to full direction-active range.
        // Same value as [GyroToStickSettings.DEFAULT_DEFLECTION_SENSITIVITY]
        // so dpad-on-gyro and deflection feel symmetric. Ignored for
        // joystick sources.
        const val DEFAULT_TILT_SENSITIVITY = 5.0f

        val DEFAULT_JSON =
            """{"inner_deadzone":$DEFAULT_INNER_DEADZONE,"outer_deadzone":$DEFAULT_OUTER_DEADZONE,"dpad_layout":"$DEFAULT_LAYOUT","tilt_sensitivity":$DEFAULT_TILT_SENSITIVITY}"""

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
                    tiltSensitivity = obj.optDouble("tilt_sensitivity", DEFAULT_TILT_SENSITIVITY.toDouble())
                        .toFloat().coerceAtLeast(0f),
                )
            } catch (_: JSONException) {
                defaults()
            }
        }

        private fun defaults() = DpadSettings(
            innerDeadzone = DEFAULT_INNER_DEADZONE,
            outerDeadzone = DEFAULT_OUTER_DEADZONE,
            dpadLayout = DEFAULT_LAYOUT,
            tiltSensitivity = DEFAULT_TILT_SENSITIVITY,
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
 * **Brick C — Joystick Move.** Stick deflection → XInput analog axis output via
 * the virtual gamepad uinput device. Game-facing behavior: the stick reads as a
 * real Xbox-controller left/right stick — same path a USB Xbox controller takes
 * through the kernel.
 *
 * Source → axis mapping (per Android gamepad convention):
 *  - `LEFT_JOYSTICK`  → ABS_X / ABS_Y    (game-side "left stick")
 *  - `RIGHT_JOYSTICK` → ABS_Z / ABS_RZ   (game-side "right stick")
 *  - Other sources    → no-op
 *
 * Coordinate convention: input `(x, y)` is normalized -1..+1 with `+y = down`
 * (per [AnalogEvent]'s contract, matching the kernel reading direction). XInput's
 * native convention is also `+y = down` for the raw axis values, so we pass
 * through unmodified. Games that interpret "up" as positive (e.g. for a movement
 * stick) handle that themselves at their input layer — Mapo just reports the
 * physical stick direction.
 *
 * Sub-inputs ("click", "outer_ring") are unchanged from [JoystickMouseMode] and
 * stay on the digital-edge path; the stick's analog motion goes through the
 * gamepad emitter instead of the mouse emitter.
 */
object JoystickMoveMode : SourceMode {
    override val mode: BindingMode = BindingMode.JOYSTICK_MOVE
    override fun validInputs(): Set<String> = INPUTS
    override fun defaultSettingsJson(): String = StickToAxisSettings.DEFAULT_JSON
    private val INPUTS = setOf("click", "outer_ring")

    override fun evaluate(
        reading: AnalogEvent,
        ctx: ModeContext,
        digitalEmit: (subInput: String, isDown: Boolean) -> Unit,
        mouse: MouseEmitter,
    ) {
        val settings = StickToAxisSettings.parse(ctx.settingsJson)
        val (ax, ay) = settings.toAxis(reading.x, reading.y)
        when (reading.source) {
            InputSource.LEFT_JOYSTICK -> ctx.gamepad.setLeftStick(reading.source, ax, ay)
            InputSource.RIGHT_JOYSTICK -> ctx.gamepad.setRightStick(reading.source, ax, ay)
            else -> Unit  // Joystick Move on non-stick sources is a no-op
        }
    }
}

/**
 * **Brick C.4 — Mouse Region.** Stick deflection → absolute cursor position
 * within a configured screen rectangle. Steam-faithful absolute-positioning
 * mode: center-stick = no movement (cursor stays put), full-right = cursor at
 * right edge of region, full-up = cursor at top edge, etc. Inside the radial
 * deadzone we issue no inject at all — the cursor remains wherever it was,
 * which is the desired behavior for "I want to keep aiming where I was" UX
 * (Steam's Mouse Region target use case: aiming in roguelikes / strategy
 * games where the cursor needs to live within a screen sub-region).
 *
 * **Coordinate convention.** Region is defined in fractional screen
 * coordinates so the same settings JSON travels across devices with different
 * display sizes. Center = (0.5, 0.5); a 50%-of-screen region is half_width =
 * half_height = 0.25 (extends 0.25 in each direction from the center). The
 * [InputAccessibilityService.injectMouseMoveAbsoluteFraction] step does the
 * pixel conversion + delta computation against the live cursor position.
 *
 * **Why no integration loop** (unlike [JoystickMouseMode]). Mouse Region is
 * absolute-positioned: when the kernel goes silent at constant stick
 * deflection, the cursor should *stay* at the corresponding region position,
 * not keep moving. So one inject per AnalogEvent is exactly right — no
 * periodic re-application needed. Constant-stick = no events = cursor parked
 * at the right spot. Stick moves again = next event repositions.
 *
 * **Multi-source.** LJ and RJ can each independently drive Mouse Region.
 * Last-source-wins per event ordering, which is a non-pathological config
 * Steam doesn't try to deconflict either.
 *
 * **Source filter.** Only joystick sources are mapped here. Mouse Region on a
 * gyro source ships with the gyro pipeline (task #254, post-Brick-C); this
 * mode is a no-op on `InputSource.GYRO` until then.
 */
object MouseRegionMode : SourceMode {
    override val mode: BindingMode = BindingMode.MOUSE_REGION
    override fun validInputs(): Set<String> = INPUTS
    override fun defaultSettingsJson(): String = MouseRegionSettings.DEFAULT_JSON
    private val INPUTS = setOf("click", "outer_ring")

    override fun evaluate(
        reading: AnalogEvent,
        ctx: ModeContext,
        digitalEmit: (subInput: String, isDown: Boolean) -> Unit,
        mouse: MouseEmitter,
    ) {
        // Only joystick sources for now. Gyro path ships with task #254.
        when (reading.source) {
            InputSource.LEFT_JOYSTICK, InputSource.RIGHT_JOYSTICK -> Unit
            else -> return
        }
        val settings = MouseRegionSettings.parse(ctx.settingsJson)
        val magnitude = sqrt(reading.x * reading.x + reading.y * reading.y)
        if (magnitude < settings.deadzone) {
            mouse.clearStickAbsoluteTarget(reading.source)
            return
        }
        val (xFrac, yFrac) = settings.toTargetFraction(reading.x, reading.y)
        mouse.setStickAbsoluteTarget(reading.source, xFrac, yFrac)
    }
}

/**
 * Parsed settings for [MouseRegionMode]. Tolerant of missing keys; defaults
 * to a center-of-screen 50%×50% region with a 10% radial deadzone.
 *
 *  - `deadzone` — radial threshold below which no inject fires. Default 0.10
 *    (looser than the dpad-mode 0.20 because spurious cursor twitches are
 *    less jarring than spurious direction edges).
 *  - `region_center_x` / `region_center_y` — fractional screen coords for
 *    the region's center. Default `(0.5, 0.5)`.
 *  - `region_half_width` / `region_half_height` — fractional extents from
 *    center. Default `0.25` each → a 50%-of-screen square at screen center.
 *  - `invert_y` — flips y; preserved across the deadzone rescale (direction
 *    is taken from raw `(x, y)`).
 */
internal data class MouseRegionSettings(
    val deadzone: Float,
    val regionCenterX: Float,
    val regionCenterY: Float,
    val regionHalfWidth: Float,
    val regionHalfHeight: Float,
    val invertY: Boolean,
) {
    /**
     * Map a stick deflection in `(-1..+1, -1..+1)` to a fractional screen
     * position. Clamped to `[0, 1]` so out-of-range region settings can't
     * teleport the cursor off-screen.
     */
    fun toTargetFraction(x: Float, y: Float): Pair<Float, Float> {
        val sy = if (invertY) -y else y
        val xFrac = (regionCenterX + x * regionHalfWidth).coerceIn(0f, 1f)
        val yFrac = (regionCenterY + sy * regionHalfHeight).coerceIn(0f, 1f)
        return xFrac to yFrac
    }

    companion object {
        const val DEFAULT_DEADZONE = 0.10f
        const val DEFAULT_REGION_CENTER_X = 0.5f
        const val DEFAULT_REGION_CENTER_Y = 0.5f
        // Full-screen region by default — full stick deflection reaches the
        // screen edges. Users who want a smaller region (precision aim in a
        // sub-rectangle) configure smaller half-widths via the Cog menu
        // (Brick F). The pre-2026-05-29 default was 0.25 (50% of screen),
        // which surprised users by not reaching the edges; bumped per
        // device-test feedback.
        const val DEFAULT_REGION_HALF_WIDTH = 0.5f
        const val DEFAULT_REGION_HALF_HEIGHT = 0.5f

        val DEFAULTS = MouseRegionSettings(
            deadzone = DEFAULT_DEADZONE,
            regionCenterX = DEFAULT_REGION_CENTER_X,
            regionCenterY = DEFAULT_REGION_CENTER_Y,
            regionHalfWidth = DEFAULT_REGION_HALF_WIDTH,
            regionHalfHeight = DEFAULT_REGION_HALF_HEIGHT,
            invertY = false,
        )
        val DEFAULT_JSON = """{"deadzone":$DEFAULT_DEADZONE,"region_center_x":$DEFAULT_REGION_CENTER_X,"region_center_y":$DEFAULT_REGION_CENTER_Y,"region_half_width":$DEFAULT_REGION_HALF_WIDTH,"region_half_height":$DEFAULT_REGION_HALF_HEIGHT,"invert_y":false}"""

        fun parse(json: String): MouseRegionSettings {
            if (json.isBlank()) return DEFAULTS
            return try {
                val obj = JSONObject(json)
                MouseRegionSettings(
                    deadzone = obj.optDouble("deadzone", DEFAULT_DEADZONE.toDouble()).toFloat()
                        .coerceIn(0f, 0.95f),
                    regionCenterX = obj.optDouble("region_center_x", DEFAULT_REGION_CENTER_X.toDouble()).toFloat()
                        .coerceIn(0f, 1f),
                    regionCenterY = obj.optDouble("region_center_y", DEFAULT_REGION_CENTER_Y.toDouble()).toFloat()
                        .coerceIn(0f, 1f),
                    regionHalfWidth = obj.optDouble("region_half_width", DEFAULT_REGION_HALF_WIDTH.toDouble()).toFloat()
                        .coerceIn(0f, 0.5f),
                    regionHalfHeight = obj.optDouble("region_half_height", DEFAULT_REGION_HALF_HEIGHT.toDouble()).toFloat()
                        .coerceIn(0f, 0.5f),
                    invertY = obj.optBoolean("invert_y", false),
                )
            } catch (_: JSONException) {
                DEFAULTS
            }
        }
    }
}

/**
 * **Brick D.3 — Gyro → Mouse.** Device rotation rate (rad/sec) → cursor velocity
 * (px/sec) via [MouseEmitter]. Steam's "Gyro to Mouse": yaw rate drives the
 * cursor on screen-X, pitch rate drives screen-Y. Roll (z) is dropped at the
 * evaluator entry point ([InputEvaluator.handleGyroReading]).
 *
 * **Input contract.** [InputEvaluator.handleGyroReading] packs the gyro into
 * an [AnalogEvent] with `source = GYRO`, `x = yaw rad/sec`, `y = pitch rad/sec`.
 * Values are raw angular velocity (not normalized -1..+1 like sticks); this
 * mode reads them as physical rad/sec.
 *
 * **Why velocity, not displacement.** The [MouseEmitter] integration loop is
 * the same machinery [JoystickMouseMode] uses — it re-applies the latest
 * velocity each ~8ms step. For gyro, sensor events arrive at ~50-200 Hz
 * (`SENSOR_DELAY_GAME`), faster than the loop's 8ms cadence, so each event
 * refreshes the velocity before the next step fires. The time-integrated
 * cursor motion equals `∫(yaw_rate × sensitivity) dt = rotation_angle × sensitivity`
 * — which is the physically-correct "pixels per radian of rotation" mapping.
 *
 * **Stops cleanly.** When the user holds the device still, the sensor keeps
 * emitting events near zero. The deadzone filter forces `(vx, vy) = (0, 0)`
 * below threshold, so the [MouseEmitter] slot zeroes out and the integration
 * loop exits — no creeping cursor from sensor noise.
 *
 * **Source filter.** Skips events that aren't `InputSource.GYRO` (defensive —
 * this mode is gyro-source-only in the picker, but a future config edit could
 * route a stick into it via the catalog and we don't want a stick's normalized
 * `(x, y)` interpreted as rad/sec — would result in absurd cursor velocity).
 *
 * Sub-inputs: none. Gyro modes are typically chord-gated via activator layers
 * (a bumper toggles the layer that swaps the gyro source from DEVICE_DEFAULT
 * to GYRO_TO_MOUSE). A future "click" / "gyro_button" sub-input could land
 * here if Mapo grows a built-in gyro-button concept, but Steam doesn't expose
 * one on this mode either.
 */
object GyroToMouseMode : SourceMode {
    override val mode: BindingMode = BindingMode.GYRO_TO_MOUSE
    override fun validInputs(): Set<String> = emptySet()
    override fun defaultSettingsJson(): String = GyroToMouseSettings.DEFAULT_JSON

    override fun evaluate(
        reading: AnalogEvent,
        ctx: ModeContext,
        digitalEmit: (subInput: String, isDown: Boolean) -> Unit,
        mouse: MouseEmitter,
    ) {
        if (reading.source != InputSource.GYRO) return
        val settings = GyroToMouseSettings.parse(ctx.settingsJson)
        val (vx, vy) = settings.toVelocity(reading.x, reading.y)
        mouse.setStickVelocity(reading.source, vx, vy)
    }
}

/**
 * Parsed settings + math for [GyroToMouseMode]. Tolerant of missing keys;
 * falls back to [DEFAULTS]. Steam's gyro UI exposes per-axis sensitivity and
 * a single threshold (deadzone) — Mapo mirrors that shape.
 *
 * **toVelocity math:**
 *  1. Per-axis deadzone gate — `|rate| < deadzone` → that axis contributes 0.
 *     Per-axis (not radial) because a hand-still device emits independent
 *     low-magnitude noise on each axis; a radial threshold would let
 *     `(yaw=0.04, pitch=0.04)` slip through (magnitude ≈ 0.057 > 0.05)
 *     producing creeping cursor motion. Per-axis nukes both independently.
 *  2. Multiply by per-axis sensitivity → px/sec.
 *  3. Apply `invert_x` / `invert_y` sign flips.
 *
 * **Sensitivity units.** `sensitivity_x` is `px/sec output` per `rad/sec input`,
 * equivalent to `px per rad of rotation` over any sustained motion. The
 * 400 default means a slow 0.5 rad/sec yaw produces 200 px/sec cursor motion;
 * a 1-radian rotation (~57°) sweeps 400 pixels at any speed.
 *
 * **Defaults are starting points.** Real-world tuning needs on-device feel
 * (different MEMS gyros have different noise floors / scale factors). The
 * Cog menu — Phase 7 Brick F — will surface per-axis sliders. Users on
 * landscape handhelds typically want symmetric x/y at first; FPS users
 * separately tune yaw > pitch later.
 */
internal data class GyroToMouseSettings(
    val sensitivityX: Float,
    val sensitivityY: Float,
    val deadzone: Float,
    val invertX: Boolean,
    val invertY: Boolean,
) {
    /**
     * Convert (yaw, pitch) in rad/sec to (vx, vy) in px/sec.
     * @param yawRadPerSec rotation rate around the device's Y axis (turn left/right)
     * @param pitchRadPerSec rotation rate around the device's X axis (tip up/down)
     */
    fun toVelocity(yawRadPerSec: Float, pitchRadPerSec: Float): Pair<Float, Float> {
        val yaw = if (abs(yawRadPerSec) < deadzone) 0f else yawRadPerSec
        val pitch = if (abs(pitchRadPerSec) < deadzone) 0f else pitchRadPerSec
        // Built-in -1 sign correction on both axes. Verified empirically on
        // AYN Thor 2026-05-31: with raw passthrough, rolling the device right
        // sent the cursor left and pitching forward sent the cursor down —
        // both opposite of player intent. The fix lives here (mode-local)
        // rather than in `handleGyroReading` so the sibling stick modes
        // (Gyro to Joystick Camera / Deflection), whose game-side stick→
        // camera processing already lands on the correct screen direction,
        // are unaffected. `invert_x` / `invert_y` continue to layer on top
        // as user-preference toggles.
        val vx = -yaw * sensitivityX * if (invertX) -1f else 1f
        val vy = -pitch * sensitivityY * if (invertY) -1f else 1f
        return vx to vy
    }

    companion object {
        const val DEFAULT_SENSITIVITY_X = 400f
        const val DEFAULT_SENSITIVITY_Y = 400f
        // 0.05 rad/sec ≈ 2.9°/sec. Above typical MEMS gyro noise floor while
        // still catching deliberate slow motion. Steam Input's analog "gyro
        // threshold" defaults are in a similar ballpark.
        const val DEFAULT_DEADZONE = 0.05f

        val DEFAULTS = GyroToMouseSettings(
            sensitivityX = DEFAULT_SENSITIVITY_X,
            sensitivityY = DEFAULT_SENSITIVITY_Y,
            deadzone = DEFAULT_DEADZONE,
            invertX = false,
            invertY = false,
        )
        val DEFAULT_JSON =
            """{"sensitivity_x":$DEFAULT_SENSITIVITY_X,"sensitivity_y":$DEFAULT_SENSITIVITY_Y,"deadzone":$DEFAULT_DEADZONE,"invert_x":false,"invert_y":false}"""

        fun parse(json: String): GyroToMouseSettings {
            if (json.isBlank()) return DEFAULTS
            return try {
                val obj = JSONObject(json)
                GyroToMouseSettings(
                    sensitivityX = obj.optDouble("sensitivity_x", DEFAULT_SENSITIVITY_X.toDouble()).toFloat()
                        .coerceAtLeast(0f),
                    sensitivityY = obj.optDouble("sensitivity_y", DEFAULT_SENSITIVITY_Y.toDouble()).toFloat()
                        .coerceAtLeast(0f),
                    // 5 rad/sec ≈ 286°/sec — past any deliberate hand motion;
                    // clamp protects against pathological JSON.
                    deadzone = obj.optDouble("deadzone", DEFAULT_DEADZONE.toDouble()).toFloat()
                        .coerceIn(0f, 5f),
                    invertX = obj.optBoolean("invert_x", false),
                    invertY = obj.optBoolean("invert_y", false),
                )
            } catch (_: JSONException) {
                DEFAULTS
            }
        }
    }
}

/**
 * **Brick D.4 — Gyro → Joystick Camera.** Device angular velocity (rad/sec)
 * → virtual right-stick deflection [-1, +1] via the gamepad uinput device.
 * The game reads it as a real Xbox-controller right stick driven by the
 * user tilting the handheld. Canonical "gyro as aim" pattern Steam Deck
 * users prefer over Gyro-to-Mouse for FPS games — the game's stick→camera
 * curve applies its own acceleration / sensitivity, so motion feels native
 * to the title rather than imposed by Mapo.
 *
 * **Why right stick.** FPS / TPS conventions across native gamepad games:
 * right stick = look/camera, left stick = movement. Sending camera input
 * via the right stick lets Wine / GameNative read it as the same axis the
 * game already maps to look.
 *
 * **Rate-based (not angle-integrated).** Like Gyro-to-Mouse: instantaneous
 * angular velocity → instantaneous stick deflection. When the user stops
 * rotating, rate → 0, stick centers, camera stops. This matches Steam
 * Input's "Gyro to Joystick Camera" behavior — the alternative
 * (angle-integrated) is what [GyroToJoystickDeflectionMode] tunes for, but
 * for camera the rate model is the right pick.
 *
 * **Source filter.** Skips events whose `source != GYRO` (defensive — the
 * picker catalog restricts this mode to the gyro source, but a config edit
 * could route a stick reading here). A stick's normalized [-1, +1]
 * interpreted as rad/sec would produce ~negligible deflection through the
 * sensitivity multiplier; the source filter just makes the no-op
 * explicit.
 */
object GyroToJoystickCameraMode : SourceMode {
    override val mode: BindingMode = BindingMode.GYRO_TO_JOYSTICK_CAMERA
    override fun validInputs(): Set<String> = emptySet()
    override fun defaultSettingsJson(): String = GyroToStickSettings.CAMERA_DEFAULT_JSON

    override fun evaluate(
        reading: AnalogEvent,
        ctx: ModeContext,
        digitalEmit: (subInput: String, isDown: Boolean) -> Unit,
        mouse: MouseEmitter,
    ) {
        if (reading.source != InputSource.GYRO) return
        val settings = GyroToStickSettings.parse(ctx.settingsJson, GyroToStickSettings.CAMERA_DEFAULTS)
        val (rawAx, rawAy) = settings.toAxis(reading.x, reading.y)
        // Built-in -1 sign correction on both axes — same pattern + reason as
        // [GyroToMouseSettings.toVelocity] (2026-05-31 user-verified on AYN
        // Thor: raw passthrough aimed the right stick opposite of player
        // intent on both axes). The fix lives here (mode-local) rather than
        // in the shared [GyroToStickSettings.toAxis] so Deflection's
        // tilt-as-movement direction stays untouched.
        // `invert_x` / `invert_y` settings continue to layer on top as
        // user-preference toggles applied inside toAxis().
        ctx.gamepad.setRightStick(reading.source, -rawAx, -rawAy)
    }
}

/**
 * **Brick D.4 (refined 2026-06-01) — Gyro → Joystick Deflection.** Device
 * orientation → virtual *left*-stick deflection. The mode treats the device
 * itself like a physical analog stick: tilt the device slightly = stick
 * deflects slightly; tilt significantly = stick deflects fully.
 *
 * **Tilt-based, not rate-based.** Reads absolute device orientation
 * (`reading.tiltRollRad` / `reading.tiltPitchRad`, sourced from
 * `TYPE_GAME_ROTATION_VECTOR` via [GyroSensorStream]) and subtracts a
 * captured reference. Tilt-from-reference angle → stick deflection
 * magnitude. Holding the device tilted at 30° gives a sustained stick
 * deflection, unlike rate-based modes where holding still = no input.
 * Critically also doesn't accumulate noise (the rotation-vector sensor is
 * already gyro+accel fused and drift-free), so a stationary device
 * produces a stationary stick.
 *
 * **Auto-calibration.** Reference orientation captured on the first event
 * after [resetState] — i.e. at gyro-pipeline activation. The user's
 * natural holding angle becomes "neutral." Reset across profile / set
 * boundaries via [InputEvaluator.flushAnalog].
 *
 * **Per-axis sensitivity** lives in [GyroToStickSettings] (reused from the
 * earlier rate-based version). With the default of 5.0, ~12° of tilt
 * (~0.2 rad) saturates the stick — small lean = full walking speed.
 *
 * **Companion to Camera, but different primitive.** Camera stays rate-based
 * because FPS aim feels right as "rotate-to-look-around"; Deflection is
 * tilt-based because movement feels right as "lean-to-walk." Both still
 * route through the per-source gamepad cache so physical sticks can
 * contribute alongside gyro additively.
 *
 * Same defensive source filter as the camera variant — see the KDoc on
 * [GyroToJoystickCameraMode].
 */
object GyroToJoystickDeflectionMode : SourceMode {
    override val mode: BindingMode = BindingMode.GYRO_TO_JOYSTICK_DEFLECTION
    override fun validInputs(): Set<String> = emptySet()
    override fun defaultSettingsJson(): String = GyroToStickSettings.DEFLECTION_DEFAULT_JSON

    // Per-source reference orientation (roll, pitch) in radians, captured
    // on the first event after [resetState]. Subsequent events compute
    // delta from this reference to derive tilt-from-rest. The gyro
    // coroutine is the only reader/writer — no synchronization needed.
    //
    // Layout: floatArrayOf(refRoll, refPitch)
    private val referenceOrientation = mutableMapOf<InputSource, FloatArray>()

    /**
     * Test seam + public reset hook. Called from
     * [InputEvaluator.flushAnalog] at profile / action-set boundaries so
     * the next event re-captures a fresh reference.
     */
    fun resetState() {
        referenceOrientation.clear()
    }

    /** Test seam — the captured reference for the given source. */
    @androidx.annotation.VisibleForTesting
    internal fun referenceFor(source: InputSource): Pair<Float, Float>? =
        referenceOrientation[source]?.let { it[0] to it[1] }

    override fun evaluate(
        reading: AnalogEvent,
        ctx: ModeContext,
        digitalEmit: (subInput: String, isDown: Boolean) -> Unit,
        mouse: MouseEmitter,
    ) {
        if (reading.source != InputSource.GYRO) return
        val settings = GyroToStickSettings.parse(ctx.settingsJson, GyroToStickSettings.DEFLECTION_DEFAULTS)
        val ref = referenceOrientation[reading.source]
        if (ref == null) {
            // First event since reset — capture the user's natural holding
            // angle as neutral. Emit a zero-stick contribution so the cache
            // has the GYRO slot at (0, 0) until the user actually tilts.
            referenceOrientation[reading.source] = floatArrayOf(reading.tiltRollRad, reading.tiltPitchRad)
            ctx.gamepad.setLeftStick(reading.source, 0f, 0f)
            return
        }
        val deltaRoll = reading.tiltRollRad - ref[0]
        // Built-in -1 sign correction on the pitch axis. User-verified on
        // AYN Thor 2026-06-01: tilting the device forward (top edge dips
        // toward player) drove the left stick BACKWARD — opposite of
        // player intent. Inverting the delta lines it up: forward tilt →
        // forward move. The roll axis was empirically correct without a
        // flip. `invert_y` on the settings continues to layer on top as a
        // user-preference toggle inside `toAxis`.
        val deltaPitch = ref[1] - reading.tiltPitchRad
        // Feed the delta to the shared toAxis math. Units shift from
        // "deflection per rad/sec" (rate) to "deflection per rad of tilt"
        // (angle); the new DEFLECTION_DEFAULTS sensitivity (5.0) targets
        // ~12° of tilt for saturation.
        val (ax, ay) = settings.toAxis(deltaRoll, deltaPitch)
        ctx.gamepad.setLeftStick(reading.source, ax, ay)
    }
}

/**
 * **Brick D.5 — Gyro → Directional Swipe.** Rate-based one-shot gesture
 * mode that emits a directional sub-input edge when the device is rotated
 * past a per-axis threshold. Steam Deck's modern replacement for legacy
 * gyro Flick Stick.
 *
 * **Behavior** (verified against Steam Deck 2026-05-31):
 *  - Rotating the device forward / back (pitch) fires `dpad_up` / `dpad_down`.
 *  - Yawing the device left / right (rotation around the vertical axis,
 *    like turning a steering wheel) fires `dpad_left` / `dpad_right`.
 *  - Each sub-input fires DOWN when the corresponding rate exceeds
 *    `rate_threshold`; releases UP when the rate drops below
 *    `release_floor`. Typical use: brief flick → quick down+up pair → the
 *    downstream activator engine sees it as a Regular Press (tap).
 *  - Hysteresis (threshold ≫ release_floor) prevents direction flutter
 *    during the deceleration phase of a flick.
 *
 * **Why rate-based, not angle-integrated.** The user's description was
 * specifically "triggered when the user gyros the device" — the rotation
 * gesture itself fires the command. Holding the device tilted doesn't
 * keep firing; that's [GyroToJoystickDeflectionMode]'s lane. Per-axis
 * thresholding without angle integration matches the test feel.
 *
 * **Axis mapping.**
 *  - `reading.y` is pitch rate (sign-corrected by [InputEvaluator.handleGyroReading]
 *    so pitching forward gives y < 0).
 *  - `reading.z` is yaw rate (rotation around screen-normal axis).
 *
 * **Source filter** identical to the other gyro modes. Non-GYRO sources
 * could in principle host this mode (joystick-flick gestures), but Steam
 * exposes Directional Swipe only on the gyro source, so we mirror that.
 *
 * **Stateless** — no per-source integrator state. The previous-emit state
 * lives in [ModeContext.priorLatched], same idiom as [DpadMode].
 */
object DirectionalSwipeMode : SourceMode {
    override val mode: BindingMode = BindingMode.DIRECTIONAL_SWIPE
    override fun validInputs(): Set<String> = INPUTS
    override fun defaultSettingsJson(): String = DirectionalSwipeSettings.DEFAULT_JSON
    private val INPUTS = setOf("dpad_up", "dpad_down", "dpad_left", "dpad_right")

    override fun evaluate(
        reading: AnalogEvent,
        ctx: ModeContext,
        digitalEmit: (subInput: String, isDown: Boolean) -> Unit,
        mouse: MouseEmitter,
    ) {
        if (reading.source != InputSource.GYRO) return
        val settings = DirectionalSwipeSettings.parse(ctx.settingsJson)

        val priorUp = ctx.priorLatched["dpad_up"] == true
        val priorDown = ctx.priorLatched["dpad_down"] == true
        val priorLeft = ctx.priorLatched["dpad_left"] == true
        val priorRight = ctx.priorLatched["dpad_right"] == true

        val pitch = reading.y  // sign-corrected: forward pitch → y < 0
        val yaw = reading.z

        // Per-direction hysteresis: latch ON at threshold, release at floor.
        // Up / down ride the pitch axis; left / right ride the yaw axis.
        // Negative y = pitch forward = up; positive y = pitch back = down.
        // Positive z = CCW around screen-normal = yaw left; negative z = yaw right.
        val wantUp = if (priorUp) pitch < -settings.releaseFloor else pitch < -settings.rateThreshold
        val wantDown = if (priorDown) pitch > settings.releaseFloor else pitch > settings.rateThreshold
        val wantLeft = if (priorLeft) yaw > settings.releaseFloor else yaw > settings.rateThreshold
        val wantRight = if (priorRight) yaw < -settings.releaseFloor else yaw < -settings.rateThreshold

        if (wantUp != priorUp) digitalEmit("dpad_up", wantUp)
        if (wantDown != priorDown) digitalEmit("dpad_down", wantDown)
        if (wantLeft != priorLeft) digitalEmit("dpad_left", wantLeft)
        if (wantRight != priorRight) digitalEmit("dpad_right", wantRight)
    }
}

/**
 * Parsed [DirectionalSwipeMode] settings. Tolerant of missing keys; falls
 * back to defaults so a binding_group seeded with `{}` keeps working.
 *
 * Defaults target deliberate flicks. Typical hand rotation rates:
 *  - Idle / drift: 0.01–0.1 rad/sec.
 *  - Slow tilt: 0.1–0.5 rad/sec.
 *  - Deliberate flick: 2–5 rad/sec.
 *
 * `rate_threshold = 2.0` cleanly distinguishes flicks from tilts.
 * `release_floor = 0.5` provides hysteresis so the deceleration phase of
 * a flick doesn't re-fire the opposite direction. Both are user-tunable
 * in the Cog menu (Phase 7 Brick F).
 */
internal data class DirectionalSwipeSettings(
    val rateThreshold: Float,
    val releaseFloor: Float,
) {
    companion object {
        const val DEFAULT_RATE_THRESHOLD = 2.0f
        const val DEFAULT_RELEASE_FLOOR = 0.5f

        val DEFAULTS = DirectionalSwipeSettings(
            rateThreshold = DEFAULT_RATE_THRESHOLD,
            releaseFloor = DEFAULT_RELEASE_FLOOR,
        )
        val DEFAULT_JSON =
            """{"rate_threshold":$DEFAULT_RATE_THRESHOLD,"release_floor":$DEFAULT_RELEASE_FLOOR}"""

        fun parse(json: String): DirectionalSwipeSettings {
            if (json.isBlank()) return DEFAULTS
            return try {
                val obj = JSONObject(json)
                val threshold = obj.optDouble("rate_threshold", DEFAULT_RATE_THRESHOLD.toDouble())
                    .toFloat().coerceAtLeast(0f)
                val floor = obj.optDouble("release_floor", DEFAULT_RELEASE_FLOOR.toDouble())
                    .toFloat().coerceAtLeast(0f)
                // Clamp floor below threshold — if a user typo'd them
                // equal/reversed, fall back to a small hysteresis margin
                // rather than producing a no-release latch.
                val safeFloor = if (floor >= threshold) (threshold * 0.25f) else floor
                DirectionalSwipeSettings(
                    rateThreshold = threshold,
                    releaseFloor = safeFloor,
                )
            } catch (_: JSONException) {
                DEFAULTS
            }
        }
    }
}

/**
 * Parsed settings + math for the gyro→stick modes
 * ([GyroToJoystickCameraMode], [GyroToJoystickDeflectionMode]). Sibling
 * of [StickToMouseSettings]: same "caller-supplied defaults" pattern so
 * the two modes can share the parser while shipping different tuning
 * presets.
 *
 * **toAxis math:**
 *  1. Per-axis deadzone — `|rate| < deadzone` → 0 on that axis. Same
 *     rationale as [GyroToMouseSettings]: a still hand emits independent
 *     low-magnitude noise on each axis; per-axis nukes both
 *     independently. A radial threshold would let (0.04, 0.04) slip
 *     through.
 *  2. Multiply by per-axis sensitivity → raw deflection in stick units.
 *  3. Apply `invert_x` / `invert_y` sign flips.
 *  4. Clamp to [-1, +1] — virtual stick deflection saturates at the rim;
 *     beyond-saturation rotation has no further effect, matching Steam.
 *
 * **Units of sensitivity:** stick-deflection units per rad/sec of gyro
 * rate. With camera default 0.8, a sustained 1 rad/sec rotation
 * (~57°/sec) produces 0.8 deflection — strong but not saturated. With
 * deflection default 0.4, same rotation produces 0.4 deflection —
 * moderate. Users tune for feel via the Cog menu (Phase 7 Brick F).
 *
 * **No response curve** (`exponent`) like [StickToMouseSettings] has:
 * gyro→stick is linear by Steam convention, matching the linear
 * [GyroToMouseSettings] choice. Add a curve setting later if needed.
 */
internal data class GyroToStickSettings(
    val sensitivityX: Float,
    val sensitivityY: Float,
    val deadzone: Float,
    val invertX: Boolean,
    val invertY: Boolean,
) {
    /**
     * Convert (roll_rate, pitch_rate) in rad/sec to (ax, ay) normalized
     * stick deflection in [-1, +1]. See class KDoc for the math steps.
     */
    fun toAxis(rollRadPerSec: Float, pitchRadPerSec: Float): Pair<Float, Float> {
        val roll = if (abs(rollRadPerSec) < deadzone) 0f else rollRadPerSec
        val pitch = if (abs(pitchRadPerSec) < deadzone) 0f else pitchRadPerSec
        val ax = (roll * sensitivityX * if (invertX) -1f else 1f).coerceIn(-1f, 1f)
        val ay = (pitch * sensitivityY * if (invertY) -1f else 1f).coerceIn(-1f, 1f)
        return ax to ay
    }

    companion object {
        // Camera defaults — high sensitivity for FPS aim feel. 1 rad/sec
        // gyro → 0.8 deflection (strong but not saturated); saturates
        // around 1.25 rad/sec (~72°/sec, a moderately fast turn).
        // Camera is rate-based; sensitivity units are "deflection per
        // rad/sec".
        const val DEFAULT_CAMERA_SENSITIVITY = 0.8f
        // Deflection defaults — tilt-based (2026-06-01), so sensitivity
        // units are "deflection per radian of tilt-from-rest", NOT
        // "deflection per rad/sec of rate". Higher numeric value at the
        // same feel because typical tilt angles are fractions of a radian.
        // 5.0 → ~12° of tilt (0.2 rad) saturates the stick — small lean =
        // full walking speed. Tune in the Cog menu (Phase 7 Brick F).
        const val DEFAULT_DEFLECTION_SENSITIVITY = 5.0f
        // 0.05 rad/sec ≈ 2.9°/sec. Same noise-floor threshold as
        // [GyroToMouseSettings]; both modes consume the same gyro stream.
        const val DEFAULT_DEADZONE = 0.05f

        val CAMERA_DEFAULTS = GyroToStickSettings(
            sensitivityX = DEFAULT_CAMERA_SENSITIVITY,
            sensitivityY = DEFAULT_CAMERA_SENSITIVITY,
            deadzone = DEFAULT_DEADZONE,
            invertX = false,
            invertY = false,
        )
        val DEFLECTION_DEFAULTS = GyroToStickSettings(
            sensitivityX = DEFAULT_DEFLECTION_SENSITIVITY,
            sensitivityY = DEFAULT_DEFLECTION_SENSITIVITY,
            deadzone = DEFAULT_DEADZONE,
            invertX = false,
            invertY = false,
        )
        val CAMERA_DEFAULT_JSON =
            """{"sensitivity_x":$DEFAULT_CAMERA_SENSITIVITY,"sensitivity_y":$DEFAULT_CAMERA_SENSITIVITY,"deadzone":$DEFAULT_DEADZONE,"invert_x":false,"invert_y":false}"""
        val DEFLECTION_DEFAULT_JSON =
            """{"sensitivity_x":$DEFAULT_DEFLECTION_SENSITIVITY,"sensitivity_y":$DEFAULT_DEFLECTION_SENSITIVITY,"deadzone":$DEFAULT_DEADZONE,"invert_x":false,"invert_y":false}"""

        fun parse(json: String, defaults: GyroToStickSettings): GyroToStickSettings {
            if (json.isBlank()) return defaults
            return try {
                val obj = JSONObject(json)
                GyroToStickSettings(
                    sensitivityX = obj.optDouble("sensitivity_x", defaults.sensitivityX.toDouble()).toFloat()
                        .coerceAtLeast(0f),
                    sensitivityY = obj.optDouble("sensitivity_y", defaults.sensitivityY.toDouble()).toFloat()
                        .coerceAtLeast(0f),
                    // Same 5 rad/sec ceiling as [GyroToMouseSettings] — past any
                    // deliberate hand motion; clamp protects against pathological JSON.
                    deadzone = obj.optDouble("deadzone", defaults.deadzone.toDouble()).toFloat()
                        .coerceIn(0f, 5f),
                    invertX = obj.optBoolean("invert_x", defaults.invertX),
                    invertY = obj.optBoolean("invert_y", defaults.invertY),
                )
            } catch (_: JSONException) {
                defaults
            }
        }
    }
}

/**
 * Parsed settings for [JoystickMoveMode]. Applies a radial deadzone + optional
 * response curve and outputs a normalized `(ax, ay)` in -1..+1. The
 * [GamepadEmitter] handles the final int16 mapping (-32768..+32767).
 *
 * Tolerant of missing keys; defaults match a stock Xbox-controller feel.
 */
internal data class StickToAxisSettings(
    val deadzone: Float,
    val exponent: Float,
    val invertY: Boolean,
) {
    /** Returns (ax, ay) normalized to -1..+1, with deadzone + curve applied. */
    fun toAxis(x: Float, y: Float): Pair<Float, Float> {
        val magnitude = sqrt(x * x + y * y).coerceIn(0f, 1f)
        if (magnitude <= deadzone) return 0f to 0f
        val rescaled = ((magnitude - deadzone) / (1f - deadzone)).coerceIn(0f, 1f)
        val shaped = rescaled.toDouble().pow(exponent.toDouble()).toFloat()
        val ux = x / magnitude
        val uy = y / magnitude
        val ax = ux * shaped
        val ay = uy * shaped * if (invertY) -1f else 1f
        return ax to ay
    }

    companion object {
        const val DEFAULT_DEADZONE = 0.10f
        // Linear (1.0) by default — XInput games typically apply their own curves
        // and expect the raw axis to be linear-ish. Set higher to attenuate near
        // center, lower for amplified small movements.
        const val DEFAULT_EXPONENT = 1.0f

        val DEFAULTS = StickToAxisSettings(
            deadzone = DEFAULT_DEADZONE,
            exponent = DEFAULT_EXPONENT,
            invertY = false,
        )
        val DEFAULT_JSON =
            """{"deadzone":$DEFAULT_DEADZONE,"exponent":$DEFAULT_EXPONENT,"invert_y":false}"""

        fun parse(json: String): StickToAxisSettings {
            if (json.isBlank()) return DEFAULTS
            return try {
                val obj = JSONObject(json)
                StickToAxisSettings(
                    deadzone = obj.optDouble("deadzone", DEFAULT_DEADZONE.toDouble()).toFloat()
                        .coerceIn(0f, 0.95f),
                    exponent = obj.optDouble("exponent", DEFAULT_EXPONENT.toDouble()).toFloat()
                        .coerceIn(0.1f, 10f),
                    invertY = obj.optBoolean("invert_y", false),
                )
            } catch (_: JSONException) {
                DEFAULTS
            }
        }
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
    BindingMode.JOYSTICK_MOVE -> JoystickMoveMode
    BindingMode.MOUSE_REGION -> MouseRegionMode
    BindingMode.GYRO_TO_MOUSE -> GyroToMouseMode
    BindingMode.GYRO_TO_JOYSTICK_CAMERA -> GyroToJoystickCameraMode
    BindingMode.GYRO_TO_JOYSTICK_DEFLECTION -> GyroToJoystickDeflectionMode
    BindingMode.DIRECTIONAL_SWIPE -> DirectionalSwipeMode
    BindingMode.FLICK_STICK,
    BindingMode.SCROLL_WHEEL,
    BindingMode.REFERENCE,
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
 * Modes that need Shizuku to function correctly, for *any* reason —
 * superset of [ANALOG_MODES_REQUIRING_MOTION_CAPTURE]. Used by the UI to
 * decide whether to surface the Shizuku-required dialog / inline banner /
 * persistent health notification when this mode is selected.
 *
 * **Why a superset.** Gyro modes don't need motion capture (sensor data
 * comes from Android's `SensorManager`, not `/dev/input`), but the
 * gyro→mouse and gyro→joystick modes still need Shizuku for clean *output*:
 *  - Mouse output without Shizuku falls back to
 *    [android.accessibilityservice.AccessibilityService.dispatchGesture],
 *    which is synthetic touch — apps like GameNative (Wine) interpret a
 *    sustained drag as click-held-while-moving rather than as cursor motion.
 *  - Gamepad axis output (JOYSTICK_MOVE, GYRO_TO_JOYSTICK_*) needs the
 *    `/dev/uinput` virtual gamepad which is shell-uid-only.
 *
 * So even when the mode itself reads from SensorManager (gyro), Shizuku is
 * still the right gate for "warn the user before letting them pick this and
 * be surprised by the degraded experience."
 */
val MODES_REQUIRING_SHIZUKU: Set<BindingMode> = ANALOG_MODES_REQUIRING_MOTION_CAPTURE + setOf(
    BindingMode.GYRO_TO_MOUSE,                // uinput mouse for clean cursor (else dispatchGesture click-drag)
    BindingMode.GYRO_TO_JOYSTICK_CAMERA,      // uinput gamepad axes
    BindingMode.GYRO_TO_JOYSTICK_DEFLECTION,  // uinput gamepad axes
    // DIRECTIONAL_SWIPE outputs synthetic digital edges via the normal key
    // inject path — works without Shizuku via reflection. Intentionally
    // omitted.
)

/** Convenience predicate over [MODES_REQUIRING_SHIZUKU]. */
fun BindingMode.requiresShizuku(): Boolean = this in MODES_REQUIRING_SHIZUKU

/**
 * Physical sources where [BindingMode.NONE] needs EVIOCGRAB (and therefore Shizuku)
 * to actually silence. The OS has no API for an app to consume analog MotionEvents,
 * so NONE on these sources silences only via the kernel-level grab held by Mapo's
 * Shizuku UserService. Digital sources (face buttons, bumpers, switches) silence
 * via `InputAccessibilityService.onKeyEvent` returning `true` (consumed), no grab
 * needed. GYRO is a SensorManager source — silenced by not subscribing.
 */
val GRABBABLE_ANALOG_SOURCES: Set<InputSource> = setOf(
    InputSource.LEFT_JOYSTICK,
    InputSource.RIGHT_JOYSTICK,
    InputSource.LEFT_TRIGGER,
    InputSource.RIGHT_TRIGGER,
    InputSource.DPAD,
)

/**
 * Source-aware companion to [requiresShizuku]. Returns true if the mode requires
 * Shizuku in general, OR if the mode is [BindingMode.NONE] on a source where
 * silencing depends on EVIOCGRAB (see [GRABBABLE_ANALOG_SOURCES]).
 *
 * Use this — not the unconditional [requiresShizuku] — anywhere the UI gates on
 * "this configuration won't work without Shizuku": dialog triggers, inline
 * banners, health notification predicates. Without source-awareness those
 * surfaces miss NONE-on-stick configurations and the user has no signal that
 * Shizuku is required for silencing to take effect.
 */
fun BindingMode.requiresShizukuOnSource(source: InputSource): Boolean =
    requiresShizuku() || (this == BindingMode.NONE && source in GRABBABLE_ANALOG_SOURCES)

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
    // Switches (Start / Select / back paddles) gained dropdowns 2026-06-01.
    // SINGLE_BUTTON surfaces a bindable "click" sub-input; DEVICE_DEFAULT /
    // NONE / any other mode get nothing (the source passes through or is
    // silenced at the source-mode level, not per sub-input).
    InputSource.SWITCH_START, InputSource.SWITCH_SELECT,
    InputSource.SWITCH_BACK_LEFT, InputSource.SWITCH_BACK_RIGHT -> when (mode) {
        BindingMode.SINGLE_BUTTON -> setOf("click")
        else -> mode.handler().validInputs()
    }
    InputSource.GYRO -> when (mode) {
        BindingMode.DPAD -> DPAD_DIRECTIONS
        BindingMode.DIRECTIONAL_SWIPE -> DPAD_DIRECTIONS
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
            // MOUSE_REGION intentionally hidden 2026-05-30: device-test on
            // AYN Thor showed no reliable absolute-positioning path. REL
            // mouse + dispatchGesture finger touch are both relative on
            // Wine's side, and the virtual stylus device that does work at
            // the Android level is filtered out by GameNative before it
            // reaches Wine. The runtime + uinput-stylus infrastructure
            // stay in place (handler() still resolves MouseRegionMode) so
            // VDF imports can read in a Mouse Region binding without
            // dropping it on the floor — but the picker doesn't expose it.
            // See [project_unsupported_vdf_features.md].
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
            // MOUSE_REGION hidden for the same reason as the joystick rows.
            BindingMode.DPAD,
            BindingMode.DIRECTIONAL_SWIPE,
            BindingMode.RADIAL_MENU,
            BindingMode.TOUCH_MENU,
            BindingMode.HOTBAR_MENU,
        )
        // Switches gained a dropdown 2026-06-01 — Steam-parity divergence,
        // because Mapo's EVIOCGRAB pipeline needs DEVICE_DEFAULT as an
        // explicit user choice (Steam doesn't grab the controller, so its
        // switches get OS pass-through for free). Same 3-option shape as
        // bumpers. Back paddles included for the same reason even though
        // the seed table doesn't ship them yet.
        InputSource.SWITCH_START, InputSource.SWITCH_SELECT,
        InputSource.SWITCH_BACK_LEFT, InputSource.SWITCH_BACK_RIGHT -> listOf(
            BindingMode.DEVICE_DEFAULT,
            BindingMode.NONE,
            BindingMode.SINGLE_BUTTON,
        )
        // Trackpads aren't on Mapo's target hardware. The picker UI hides
        // itself for empty lists.
        else -> emptyList()
    }
}
