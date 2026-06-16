package com.mapo.service.input.modes

import android.util.Log
import com.mapo.data.model.steam.BindingMode
import com.mapo.data.model.steam.InputSource
import com.mapo.service.input.AnalogEvent
import com.mapo.service.input.HapticIntensity
import kotlin.math.abs
import kotlin.math.atan2
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
    /**
     * Fire-and-forget haptic sink for modes that produce discrete tactile
     * feedback (e.g. a joystick's outer-ring detent buzz). Wired by
     * [com.mapo.service.input.InputEvaluator] to [com.mapo.service.input.HapticEmitter.buzz];
     * defaults to no-op so test fixtures don't need to provide one.
     */
    val haptic: (HapticIntensity) -> Unit = {},
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

    /**
     * Fire-and-forget instantaneous cursor delta. Used by event-driven modes
     * (e.g. [com.mapo.service.input.modes.FlickStickMode]) where the
     * per-evaluate output is a discrete amount of motion rather than a
     * sustained velocity. The implementation accumulates fractional pixels
     * across calls and forwards integer steps to the uinput mouse — small
     * sub-pixel emissions still register over time.
     *
     * No per-source bookkeeping: caller owns the cadence + timing. Unlike
     * [setStickVelocity], no integration loop runs from this method; the
     * delta is injected immediately and there's nothing to "stop."
     */
    fun addRelativeDelta(dx: Float, dy: Float)

    /**
     * Smoothly emit `(dx, dy)` total pixels over `durationMs`, in small
     * incremental injections at the same cadence as [setStickVelocity]'s
     * integration loop. Used by Flick Stick to play out a flick burst
     * over its `flick_time` window — committed at activation, plays to
     * completion regardless of subsequent stick state, decoupled from the
     * caller's event cadence.
     *
     * Fire-and-forget per call: each invocation spawns its own playout
     * coroutine. Multiple concurrent calls' deltas sum naturally at the
     * uinput layer. `durationMs == 0` falls through to [addRelativeDelta]
     * (instant emit) so callers don't need to special-case the zero case.
     */
    fun scheduleSmoothDelta(dx: Float, dy: Float, durationMs: Long)

    companion object {
        /** No-op sink for digital modes and for tests. */
        val NOOP: MouseEmitter = object : MouseEmitter {
            override fun setStickVelocity(source: InputSource, vxPxPerSec: Float, vyPxPerSec: Float) {}
            override fun setStickAbsoluteTarget(source: InputSource, xFrac: Float, yFrac: Float) {}
            override fun clearStickAbsoluteTarget(source: InputSource) {}
            override fun clearAllVelocities() {}
            override fun addRelativeDelta(dx: Float, dy: Float) {}
            override fun scheduleSmoothDelta(dx: Float, dy: Float, durationMs: Long) {}
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
    /**
     * Press or release a gamepad button. `btnCode` is a `UinputGamepad.Buttons.*` int.
     * Returns true if the button was actually routed to the virtual gamepad (Shizuku
     * ready), false if the MVG was unavailable — letting the caller fall back to a
     * key-event inject so gamepad-button bindings still do *something* without Shizuku.
     */
    fun setButton(btnCode: Int, pressed: Boolean): Boolean

    /**
     * Binding-output stick contribution — a single non-source-keyed slot per stick for
     * directional stick OUTPUTS bound to arbitrary inputs ([BindingOutput.XInputStick]).
     * Summed alongside the per-source contributions. Net (x, y) is computed by the caller
     * ([OutputEmitter]); same convention as [setLeftStick] (+x right, +y down).
     */
    fun setLeftStickOutput(x: Float, y: Float)
    fun setRightStickOutput(x: Float, y: Float)
    /**
     * Binding-output trigger contribution — a single non-source-keyed slot per trigger
     * for AXIS_L2/AXIS_R2 gamepad-button OUTPUTS bound to arbitrary inputs. Drives the
     * analog trigger axis (ABS_Z / ABS_RZ) the way games + testers actually read the
     * trigger — a digital BTN_TL2/TR2 press does NOT register as the trigger axis.
     * 0.0..1.0.
     */
    fun setLeftTriggerOutput(v: Float)
    fun setRightTriggerOutput(v: Float)
    /**
     * Binding-output dpad-hat contribution — a single non-source-keyed net hat for
     * DPAD_UP/DOWN/LEFT/RIGHT gamepad-button OUTPUTS bound to arbitrary inputs. Drives the
     * hat axis (ABS_HAT0X/Y) games read for the d-pad — a digital BTN press / KEYCODE_DPAD
     * inject does NOT register as the gamepad d-pad. Each axis -1 / 0 / +1.
     */
    fun setHatOutput(x: Int, y: Int)
    /** Zero all binding-output slots — sticks, triggers, hat (mode/config change cleanup). */
    fun clearOutputSticks()

    companion object {
        /** No-op sink for digital modes and for tests. */
        val NOOP: GamepadEmitter = object : GamepadEmitter {
            override fun setLeftStick(source: InputSource, x: Float, y: Float) {}
            override fun setRightStick(source: InputSource, x: Float, y: Float) {}
            override fun setLeftTrigger(source: InputSource, v: Float) {}
            override fun setRightTrigger(source: InputSource, v: Float) {}
            override fun setDpadHat(source: InputSource, x: Int, y: Int) {}
            override fun clearSource(source: InputSource) {}
            override fun setButton(btnCode: Int, pressed: Boolean): Boolean = false
            override fun setLeftStickOutput(x: Float, y: Float) {}
            override fun setRightStickOutput(x: Float, y: Float) {}
            override fun setLeftTriggerOutput(v: Float) {}
            override fun setRightTriggerOutput(v: Float) {}
            override fun setHatOutput(x: Int, y: Int) {}
            override fun clearOutputSticks() {}
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
    private val INPUTS = setOf("dpad_up", "dpad_down", "dpad_left", "dpad_right", "click", "outer_ring")
    private const val TAG = "DpadMode"

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
        // The physical DPAD source is bridged from its ABS_HAT0 hat reading into
        // the digital dpad_* edge pipeline upstream in InputEvaluator.dispatchReadings
        // (Mapo's targets are HAT-only — no KEYCODE_DPAD_* reaches onKeyEvent), so this
        // analog handler never runs for DPAD. Defensive bail in case routing changes,
        // and to keep this mode purely stick/gyro-driven.
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
            val axial = settings.diagonalAxial()
            wantUp = effY < -axial
            wantDown = effY > axial
            wantRight = effX > axial
            wantLeft = effX < -axial
        } else if (settings.dpadLayout == DpadSettings.LAYOUT_CROSS_GATE) {
            // Cross gate: diagonals are allowed, but only once deflection reaches the
            // outer edge (mag >= CROSS_GATE_DIAGONAL_THRESHOLD). Below that, near the
            // center, snap to the dominant cardinal — making diagonals harder to hit
            // near center than at the edge (the "+ gate" cardinal bias).
            if (mag >= DpadSettings.CROSS_GATE_DIAGONAL_THRESHOLD) {
                val axial = settings.diagonalAxial()
                wantUp = effY < -axial
                wantDown = effY > axial
                wantRight = effX > axial
                wantLeft = effX < -axial
            } else if (abs(effX) > abs(effY)) {
                wantUp = false; wantDown = false
                wantRight = effX > 0f; wantLeft = effX < 0f
            } else {
                wantUp = effY < 0f; wantDown = effY > 0f
                wantRight = false; wantLeft = false
            }
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

        // Outer-ring command — joystick sources only (gyro readings are rates, not
        // a positional ring). Shared with the other joystick modes.
        if (reading.source == InputSource.LEFT_JOYSTICK || reading.source == InputSource.RIGHT_JOYSTICK) {
            StickOuterRingHaptics.outerRingCommand(
                reading, settings.commandRadius, settings.commandInvert, ctx, digitalEmit, TAG,
            )
        }
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
    /** Raw 2000..16000 (Steam units). Higher = wider diagonal overlap band. */
    val overlapRegion: Float,
    val commandRadius: Float, // 0..1 (normalized from 0..32767)
    val commandInvert: Boolean,
) {
    /**
     * Per-axis floor for 8-way / cross-gate diagonal emit, derived from Overlap
     * Region: higher overlap → lower threshold → both directions fire across a
     * wider band (lowest setting ≈ 4-way; highest ≈ overlap unless near-cardinal).
     */
    fun diagonalAxial(): Float {
        val t = ((overlapRegion - 2000f) / 14000f).coerceIn(0f, 1f)
        return MAX_OVERLAP_AXIAL * (1f - t)
    }

    companion object {
        const val DEFAULT_INNER_DEADZONE = 0.20f
        const val DEFAULT_OUTER_DEADZONE = 0.05f
        const val LAYOUT_4_WAY = "4_way"
        const val LAYOUT_8_WAY = "8_way"
        const val LAYOUT_CROSS_GATE = "cross_gate"
        // Spec default is "8 Way (Overlap)" (SOURCE_MODE_SETTINGS_SPEC.txt line 134) and
        // the settings dropdown's defaultId matches ("8_way"). The runtime default MUST
        // agree: a group whose settingsJson omits "dpad_layout" (the common case — mode
        // switch via updateBindingGroupMode doesn't seed settings) is shown as "8-way" in
        // the cog menu, so parsing it as 4-way silently downgraded diagonals to cardinals.
        const val DEFAULT_LAYOUT = LAYOUT_8_WAY
        // Cross-gate: diagonals allowed, but only once deflection magnitude reaches this
        // fraction of full — below it, snap to the dominant cardinal. This is the
        // "diagonals harder near center, easier at the edge" cardinal bias. Tunable.
        const val CROSS_GATE_DIAGONAL_THRESHOLD = 0.7f
        // GYRO-only: scales integrated tilt angle (radians) into stick-
        // deflection-equivalent units before the deadzone check. 5.0 means
        // ~0.2 rad (≈11.5°) of tilt clamps to full direction-active range.
        // Same value as [GyroToStickSettings.DEFAULT_DEFLECTION_SENSITIVITY]
        // so dpad-on-gyro and deflection feel symmetric. Ignored for
        // joystick sources.
        const val DEFAULT_TILT_SENSITIVITY = 5.0f

        // Deadzone exposed in the joystick Directional Pad menu as a raw 0..32767
        // slider (spec default 10000 ≈ 0.305). Normalized to innerDeadzone.
        const val DEFAULT_DEADZONE_RAW = 10000f
        const val DEFAULT_OVERLAP_REGION = 4000f
        // Max per-axis diagonal floor at the lowest overlap setting (≈4-way feel).
        const val MAX_OVERLAP_AXIAL = 0.5f
        // Outer-ring command radius — shared 0..32767 slider with the Joystick menu.
        const val DEFAULT_COMMAND_RADIUS_RAW = 25000f

        val DEFAULT_JSON =
            """{"deadzone":$DEFAULT_DEADZONE_RAW,"outer_deadzone":$DEFAULT_OUTER_DEADZONE,""" +
                """"dpad_layout":"$DEFAULT_LAYOUT","tilt_sensitivity":$DEFAULT_TILT_SENSITIVITY,""" +
                """"overlap_region":$DEFAULT_OVERLAP_REGION}"""

        fun parse(json: String): DpadSettings {
            if (json.isBlank()) return defaults()
            return try {
                val obj = JSONObject(json)
                val layoutRaw = obj.optString("dpad_layout", DEFAULT_LAYOUT)
                val layout = if (layoutRaw in setOf(LAYOUT_4_WAY, LAYOUT_8_WAY, LAYOUT_CROSS_GATE)) {
                    layoutRaw
                } else {
                    DEFAULT_LAYOUT
                }
                // Prefer the raw 0..32767 "deadzone" key (joystick DPad menu); fall back
                // to the legacy normalized "inner_deadzone"; else the spec default.
                val inner = when {
                    obj.has("deadzone") ->
                        (obj.optDouble("deadzone", DEFAULT_DEADZONE_RAW.toDouble()).toFloat() / 32767f)
                    obj.has("inner_deadzone") ->
                        obj.optDouble("inner_deadzone", DEFAULT_INNER_DEADZONE.toDouble()).toFloat()
                    else -> DEFAULT_DEADZONE_RAW / 32767f
                }.coerceIn(0f, 0.95f)
                DpadSettings(
                    innerDeadzone = inner,
                    outerDeadzone = obj.optDouble("outer_deadzone", DEFAULT_OUTER_DEADZONE.toDouble())
                        .toFloat().coerceIn(0f, 0.95f),
                    dpadLayout = layout,
                    tiltSensitivity = obj.optDouble("tilt_sensitivity", DEFAULT_TILT_SENSITIVITY.toDouble())
                        .toFloat().coerceAtLeast(0f),
                    overlapRegion = obj.optDouble("overlap_region", DEFAULT_OVERLAP_REGION.toDouble())
                        .toFloat().coerceIn(2000f, 16000f),
                    commandRadius = (obj.optDouble("command_radius", DEFAULT_COMMAND_RADIUS_RAW.toDouble())
                        .toFloat() / 32767f).coerceIn(0f, 1f),
                    commandInvert = obj.optBoolean("command_invert", false),
                )
            } catch (_: JSONException) {
                defaults()
            }
        }

        private fun defaults() = DpadSettings(
            innerDeadzone = DEFAULT_DEADZONE_RAW / 32767f,
            outerDeadzone = DEFAULT_OUTER_DEADZONE,
            dpadLayout = DEFAULT_LAYOUT,
            tiltSensitivity = DEFAULT_TILT_SENSITIVITY,
            overlapRegion = DEFAULT_OVERLAP_REGION,
            commandRadius = DEFAULT_COMMAND_RADIUS_RAW / 32767f,
            commandInvert = false,
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

    /**
     * Lowest the soft-pull *press* point may sit. A 0 threshold (e.g. a settings
     * JSON that carries `trigger_threshold:0`, or a hysteresis ≥ threshold) would
     * otherwise make `magnitude >= press` true at rest and latch the soft pull
     * permanently — the exact wedge observed 2026-06-09: one DOWN, never an UP.
     */
    private const val SOFT_PRESS_FLOOR = 0.04f

    /**
     * Smallest gap kept between the press and release points (and between release
     * and rest). Guarantees the release point is strictly in `(0, press)` so a
     * trigger returning to rest always clears the latch, regardless of the
     * configured hysteresis.
     */
    private const val SOFT_RELEASE_FLOOR = 0.02f

    /**
     * Hysteresis band for the analog full-pull edge. Fixed (not user-exposed) —
     * the full pull sits near the end of travel where a wide dead-band prevents
     * flutter as the user holds at maximum.
     */
    private const val FULL_PULL_HYSTERESIS = 0.05f

    override fun evaluate(
        reading: AnalogEvent,
        ctx: ModeContext,
        digitalEmit: (subInput: String, isDown: Boolean) -> Unit,
        mouse: MouseEmitter,
    ) {
        val settings = TriggerSettings.parse(ctx.settingsJson)
        val magnitude = reading.x  // triggers are 0..1 on x; y is always 0
        val priorSoft = ctx.priorLatched[SOFT_PULL_SUB_INPUT] == true
        val priorFull = ctx.priorLatched[FULL_PULL_SUB_INPUT] == true

        // Raw latch decisions (threshold + hysteresis), independent of style.
        // Soft pull at soft_threshold; full pull at the end-of-travel click_threshold
        // (Mapo synthesizes full from the analog axis, not the hardware click — which
        // trips at ~2% on the AYN Thor; that hardware click is suppressed upstream in
        // InputEvaluator.handleDigital while a trigger is in this mode).
        val rawSoft = latchWant(magnitude, priorSoft, settings.softThreshold, settings.softHysteresis)
        val rawFull = latchWant(magnitude, priorFull, settings.clickThreshold, FULL_PULL_HYSTERESIS)

        // Threshold trigger style decides the EFFECTIVE soft/full wants from the raw ones.
        val state = styleStateBySource.getOrPut(reading.source) { StyleState() }
        if (magnitude < RELEASED_FLOOR) state.reset() // fully released → fresh next pull
        val (wantSoft, wantFull) = applyStyle(
            settings.style, state, reading.timestampMs, rawSoft, rawFull, priorSoft, priorFull,
        )

        if (wantSoft != priorSoft) {
            Log.d(TAG_MOTION, "soft_pull edge ${reading.source} mag=${"%.3f".format(magnitude)} style=${settings.style} -> ${if (wantSoft) "DOWN" else "UP"}")
            digitalEmit(SOFT_PULL_SUB_INPUT, wantSoft)
        }
        if (wantFull != priorFull) {
            Log.d(TAG_MOTION, "full_pull edge ${reading.source} mag=${"%.3f".format(magnitude)} style=${settings.style} -> ${if (wantFull) "DOWN" else "UP"}")
            digitalEmit(FULL_PULL_SUB_INPUT, wantFull)
        }

        emitAnalogOutput(reading, settings, ctx.gamepad)
    }

    /**
     * Threshold-cross decision for one stage with press-floor / release-floor + hysteresis.
     * Floors the press point above rest so a resting trigger never latches, and clamps the
     * release strictly into `(0, press)` so a returning trigger always clears (a hysteresis
     * ≥ threshold would otherwise wedge the latch on — the 2026-06-09 soft-pull bug).
     */
    private fun latchWant(magnitude: Float, priorLatched: Boolean, rawThreshold: Float, hysteresis: Float): Boolean {
        val press = rawThreshold.coerceIn(SOFT_PRESS_FLOOR, 1f)
        val release = (press - hysteresis).coerceIn(SOFT_RELEASE_FLOOR, press - SOFT_RELEASE_FLOOR)
        return if (priorLatched) magnitude >= release else magnitude >= press
    }

    /**
     * Apply the Threshold Trigger Style to the raw soft/full latch wants. Returns the
     * effective `(soft, full)` to emit.
     *  - **simple_threshold / hair_trigger** — independent: soft fires at its threshold,
     *    full at its threshold; both can be active. (The spec's Simple-vs-Hair distinction
     *    is about feel; treated identically here — current/baseline behavior.)
     *  - **hip_fire_aggressive / normal / relaxed** — quick pull to full SKIPS the soft
     *    pull; a slower pull (soft held longer than the window) fires soft first, then full.
     *    Windows grow across the three. **Approximate window values + an event-driven check**
     *    (a trigger held *between* soft and full with no further motion fires soft on the
     *    next event, not exactly at the window) — to tune/verify on device.
     *  - **hip_fire_exclusive** — once soft OR full fires, the other is locked out until the
     *    trigger fully releases.
     */
    private fun applyStyle(
        style: String,
        state: StyleState,
        nowMs: Long,
        rawSoft: Boolean,
        rawFull: Boolean,
        priorSoft: Boolean,
        priorFull: Boolean,
    ): Pair<Boolean, Boolean> = when (style) {
        "hip_fire_exclusive" -> when (state.owner) {
            Sub.FULL -> false to rawFull
            Sub.SOFT -> rawSoft to false
            Sub.NONE -> when {
                rawFull -> { state.owner = Sub.FULL; false to true } // full wins if both cross at once
                rawSoft -> { state.owner = Sub.SOFT; true to false }
                else -> false to false
            }
        }
        "hip_fire_aggressive", "hip_fire_normal", "hip_fire_relaxed" -> {
            val windowMs = when (style) {
                "hip_fire_aggressive" -> HIP_FIRE_AGGRESSIVE_MS
                "hip_fire_normal" -> HIP_FIRE_NORMAL_MS
                else -> HIP_FIRE_RELAXED_MS
            }
            hipFire(state, windowMs, nowMs, rawSoft, rawFull, priorSoft)
        }
        else -> rawSoft to rawFull // simple_threshold, hair_trigger
    }

    private fun hipFire(
        state: StyleState,
        windowMs: Long,
        nowMs: Long,
        rawSoft: Boolean,
        rawFull: Boolean,
        priorSoft: Boolean,
    ): Pair<Boolean, Boolean> {
        if (rawFull) {
            // Reached full pull. Soft keeps whatever it was — already firing (slow pull) stays
            // on; deferred-and-never-fired (fast pull) stays off (skipped). Full fires.
            state.fullReached = true
            return priorSoft to true
        }
        if (state.fullReached) {
            // Been to full this pull, now backing off but not released → never (re)fire soft.
            // Keep soft only while still above its threshold; release it below.
            return (if (rawSoft) priorSoft else false) to false
        }
        if (rawSoft) {
            if (priorSoft) return true to false // already firing soft → hold
            if (state.softCrossTimeMs == 0L) state.softCrossTimeMs = nowMs
            val elapsed = nowMs - state.softCrossTimeMs
            return if (elapsed >= windowMs) true to false else false to false // defer until window elapses
        }
        state.softCrossTimeMs = 0L
        return false to false
    }

    /**
     * Analog Output Trigger: send the deadzone-mapped, curved analog pull to the virtual
     * gamepad's chosen trigger axis. Trigger mode can BOTH fire soft/full pull commands
     * AND pass the raw analog through (Steam-faithful). The output axis is the user's
     * `analog_output_trigger` choice, defaulting (absent setting) to the MATCHING trigger
     * so a fresh "Trigger (Analog)" still feels like a real trigger.
     *
     * Writes only the target axis (one push per event). Switching the output axis — or to
     * Off — is a config change, and [InputEvaluator.handleConfigChange] clears both
     * trigger-source contributions on every config change, so a stale value can't persist
     * on the old axis even if the trigger isn't moving when the user changes the setting.
     */
    private fun emitAnalogOutput(reading: AnalogEvent, settings: TriggerSettings, gamepad: GamepadEmitter) {
        val toRight = when (settings.analogOutput) {
            "off" -> return // explicit Off — emit nothing (contribution stays cleared)
            "left" -> false
            "right" -> true
            else -> reading.source == InputSource.RIGHT_TRIGGER // null/unset → matching trigger
        }
        val value = analogOutputValue(reading.x, settings)
        if (toRight) gamepad.setRightTrigger(reading.source, value) else gamepad.setLeftTrigger(reading.source, value)
    }

    /** Map raw pull magnitude through the range-start/end deadzone window + response curve → 0..1. */
    private fun analogOutputValue(magnitude: Float, s: TriggerSettings): Float {
        val span = s.rangeEnd - s.rangeStart
        val t = if (span <= 0f) {
            if (magnitude >= s.rangeEnd) 1f else 0f
        } else {
            ((magnitude - s.rangeStart) / span).coerceIn(0f, 1f)
        }
        return t.pow(s.responseExponent)
    }

    private const val TAG_MOTION = "InputEvaluator.Motion"

    // Hip-fire soft-skip windows (ms). Approximate — tune/verify on device; Steam's exact
    // values aren't published. Aggressive < Normal < Relaxed (larger = easier to still get
    // the soft pull on a moderately-quick pull).
    private const val HIP_FIRE_AGGRESSIVE_MS = 120L
    private const val HIP_FIRE_NORMAL_MS = 220L
    private const val HIP_FIRE_RELAXED_MS = 400L

    /** Magnitude below which a trigger is "fully released" → per-pull style state resets. */
    private const val RELEASED_FLOOR = 0.02f

    private enum class Sub { NONE, SOFT, FULL }

    /** Per-trigger threshold-style state across the events of one pull (Hip Fire variants). */
    private class StyleState {
        /** When the soft threshold was first crossed this pull (hip-fire defer window). */
        var softCrossTimeMs: Long = 0L
        /** Set once full pull is reached this pull — soft must not (re)fire on the way down. */
        var fullReached: Boolean = false
        /** Exclusive style: which sub-input claimed the pull (locks the other out). */
        var owner: Sub = Sub.NONE
        fun reset() { softCrossTimeMs = 0L; fullReached = false; owner = Sub.NONE }
    }

    private val styleStateBySource = mutableMapOf<InputSource, StyleState>()

    /**
     * Clear per-pull threshold-style state. Called from [InputEvaluator.flushAnalog] at
     * profile / action-set boundaries so a stale lock or defer window can't leak across.
     */
    fun resetState() {
        styleStateBySource.clear()
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
    /**
     * Analog Output Trigger: which virtual-gamepad trigger axis to send the raw analog
     * pull to. `"left"` / `"right"` / `"off"`, or `null` when the setting is absent —
     * which resolves to the MATCHING trigger at evaluate time (Steam's default: a
     * "Trigger (Analog)" still passes its analog value through). `null` is distinct from
     * `"off"` (an explicit user choice to send nothing).
     */
    val analogOutput: String?,
    /** Range start/end as 0..1 magnitudes — the analog output deadzone window. */
    val rangeStart: Float,
    val rangeEnd: Float,
    /** Response-curve power applied to the ranged value before output (1.0 = linear). */
    val responseExponent: Float,
    /**
     * Threshold trigger style: how the soft/full pull commands are timed relative to each
     * other. One of `simple_threshold` / `hair_trigger` / `hip_fire_aggressive` /
     * `hip_fire_normal` / `hip_fire_relaxed` / `hip_fire_exclusive`. See [TriggerMode].
     */
    val style: String,
) {
    companion object {
        const val DEFAULT_CLICK_THRESHOLD = 0.95f
        const val DEFAULT_SOFT_THRESHOLD = 0.10f
        const val DEFAULT_SOFT_HYSTERESIS = 0.05f
        // Range defaults (Steam units → 0..1): start 1000, end 32000.
        const val DEFAULT_RANGE_START = 1000f / 32767f
        const val DEFAULT_RANGE_END = 32000f / 32767f

        /** Steam-canonical analog range; the settings UI's "Trigger threshold point" is 0..[STEAM_AXIS_MAX]. */
        private const val STEAM_AXIS_MAX = 32767.0

        fun parse(json: String): TriggerSettings {
            if (json.isBlank()) return defaults()
            return try {
                val obj = JSONObject(json)
                // The settings UI stores the soft-pull point as "trigger_threshold" in
                // Steam units (0..32767) for VDF round-trip; convert to the 0..1 magnitude
                // this mode compares against. Fall back to the legacy 0..1 "soft_threshold"
                // key for any group seeded before the trigger menu existed.
                val softThreshold = when {
                    obj.has("trigger_threshold") ->
                        (obj.optDouble("trigger_threshold", 0.0) / STEAM_AXIS_MAX).toFloat().coerceIn(0f, 1f)
                    else -> obj.optDouble("soft_threshold", DEFAULT_SOFT_THRESHOLD.toDouble()).toFloat()
                }
                TriggerSettings(
                    clickThreshold = obj.optDouble("click_threshold", DEFAULT_CLICK_THRESHOLD.toDouble()).toFloat(),
                    softThreshold = softThreshold,
                    softHysteresis = obj.optDouble("soft_hysteresis", DEFAULT_SOFT_HYSTERESIS.toDouble()).toFloat(),
                    analogOutput = if (obj.has("analog_output_trigger")) obj.optString("analog_output_trigger") else null,
                    rangeStart = (obj.optDouble("trigger_range_start", 1000.0) / STEAM_AXIS_MAX).toFloat().coerceIn(0f, 1f),
                    rangeEnd = (obj.optDouble("trigger_range_end", 32000.0) / STEAM_AXIS_MAX).toFloat().coerceIn(0f, 1f),
                    responseExponent = responseExponentFor(
                        obj.optString("trigger_response_curve", "linear"),
                        obj.optDouble("trigger_custom_response_curve", 1000.0).toFloat(),
                    ),
                    style = obj.optString("threshold_trigger_style", "hair_trigger"),
                )
            } catch (_: JSONException) {
                defaults()
            }
        }

        /**
         * Map the named response-curve option (or the custom slider) to a power-curve
         * exponent applied as `output = t^exponent`. `<1` reaches max faster (aggressive),
         * `>1` slower (relaxed/wide). **Approximate** — these are reasonable shapes, not
         * exact Steam curves (the precise curve values remain an open item). The custom
         * slider (25..4000, 1000 = linear) is inverse: higher = max sooner = smaller
         * exponent, per the setting's helper text.
         */
        fun responseExponentFor(curve: String, customValue: Float): Float = when (curve) {
            "aggressive" -> 0.6f
            "relaxed" -> 1.5f
            "wide" -> 2.2f
            "extra_wide" -> 3.0f
            "custom" -> (1000f / customValue.coerceAtLeast(1f)).coerceIn(0.2f, 5f)
            else -> 1f // linear
        }

        private fun defaults() = TriggerSettings(
            clickThreshold = DEFAULT_CLICK_THRESHOLD,
            softThreshold = DEFAULT_SOFT_THRESHOLD,
            softHysteresis = DEFAULT_SOFT_HYSTERESIS,
            analogOutput = null,
            rangeStart = DEFAULT_RANGE_START,
            rangeEnd = DEFAULT_RANGE_END,
            responseExponent = 1f,
            style = "hair_trigger",
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
 * **Settings — the full "Joystick Mouse" menu.** Shares the deadzone, response
 * curve, outer-ring, and haptic machinery with [JoystickMoveMode] via
 * [StickToAxisSettings] (curve / axis-style default to Wide / Per axis here, per
 * the spec), then converts the deadzone-+-curve-shaped vector into a cursor
 * velocity through [MouseOutputSettings] (mouse sensitivity %, per-axis scale,
 * rotate, axis-limit). The outer-ring command + movement haptic run through the
 * shared [StickOuterRingHaptics].
 *
 * **What gets emitted.** Each [evaluate] call samples the stick's normalized
 * (x, y), produces a (vx, vy) pixels-per-second velocity, and writes it into the
 * [MouseEmitter] for this source. The emitter's integration loop keeps the
 * cursor moving while the stick is held.
 */
object JoystickMouseMode : SourceMode {
    override val mode: BindingMode = BindingMode.JOYSTICK_MOUSE
    override fun validInputs(): Set<String> = INPUTS
    override fun defaultSettingsJson(): String = "{}"
    private val INPUTS = setOf("click", "outer_ring")
    private const val TAG = "JoystickMouseMode"

    override fun evaluate(
        reading: AnalogEvent,
        ctx: ModeContext,
        digitalEmit: (subInput: String, isDown: Boolean) -> Unit,
        mouse: MouseEmitter,
    ) {
        // Joystick Mouse only handles the two analog stick sources (gyro mouse is
        // a separate mode). "click" arrives as a digital event via onKeyEvent.
        when (reading.source) {
            InputSource.LEFT_JOYSTICK, InputSource.RIGHT_JOYSTICK -> Unit
            else -> return
        }
        // Deadzone + response curve (Wide / Per-axis defaults per spec), shared
        // with Joystick Move.
        val analog = StickToAxisSettings.parse(
            ctx.settingsJson, curveDefault = "wide", axisStyleDefault = "per_axis",
        )
        val out = MouseOutputSettings.parse(ctx.settingsJson)
        val (sx, sy) = analog.toShaped(reading.x, reading.y)
        val (vx, vy) = out.toVelocity(sx, sy)
        mouse.setStickVelocity(reading.source, vx, vy)

        // Outer-ring command + travel-based haptic (shared with Joystick Move).
        StickOuterRingHaptics.process(reading, analog, ctx, digitalEmit, TAG)
    }
}

/**
 * **Flick Stick.** Jibb Smart's invention: the player pushes the stick out to
 * a direction, the camera "flicks" (rotates fast) to face that direction,
 * then while the stick is held the camera rotates 1:1 with the stick's
 * angular delta around its center. Returning the stick to center clears the
 * state; the next push re-flicks to wherever the stick now points.
 *
 * **Output: mouse_dx (horizontal only).** dy is always zero — Flick Stick is
 * a yaw-only camera mode by design.
 *
 * **State machine** per source:
 *  - `NEUTRAL` — stick magnitude < `flick_deadzone`. No output.
 *  - `HOLDING` — stick crossed the activation ring; the flick has been
 *    *committed* and is playing out via the mouse emitter over
 *    `flick_time` ms. Hold-phase angular-delta emissions are LOCKED OUT
 *    during this window so the user's finishing-the-push drift isn't
 *    interpreted as intentional rotation (user-reported bounce-back
 *    artifact 2026-06-03). After the lockout expires, the first evaluate()
 *    re-baselines `lastStickAngleRad` to the current stick angle without
 *    emitting; subsequent evaluates emit hold-phase deltas normally.
 *
 * **Steam parity (verified on Steam Deck by user 2026-06-03):** the flick
 * is committed at threshold-crossing time and plays out over `flick_time`.
 * What the user does *after* crossing — keeps holding at the edge, drifts
 * back into the live zone, releases immediately — doesn't change the flick
 * amount. The flick equals the angle the stick was pointing AT THE INSTANT
 * it crossed the ring.
 *
 * **Coordinate convention.** Screen-up is the zero-angle reference, so a
 * stick push straight up = 0 flick, push right = +π/2 flick (clockwise),
 * push down = ±π (180°), push left = −π/2.
 *
 * **Snap-to-cardinal.** When `flick_snap_mode != 0`, the flick target is
 * snapped to the nearest cardinal (4-way) or 8-way direction with strength
 * proportional to `flick_snap_strength`. 0 = no snap; 1 = full snap. Hold-
 * phase rotation is unaffected — the player can still fine-tune by rotating
 * the stick after the flick.
 *
 * **Source filter.** Stick sources only (LEFT_JOYSTICK / RIGHT_JOYSTICK).
 * GYRO and other sources are no-ops; the gyro variant lives in
 * [GyroFlickStickMode] when that lands.
 *
 * **Per-source state** lives in [stateBySource]; cleared via [resetState]
 * from [com.mapo.service.input.InputEvaluator.handleConfigChange] on mode
 * change so a deflected stick from the prior mode doesn't leak into the
 * Flick Stick state machine.
 */
object FlickStickMode : SourceMode {
    override val mode: BindingMode = BindingMode.FLICK_STICK
    override fun validInputs(): Set<String> = INPUTS
    override fun defaultSettingsJson(): String = FlickStickSettings.DEFAULT_JSON
    private val INPUTS = setOf("click", "outer_ring")
    private const val TAG = "FlickStickMode"

    private enum class Phase { NEUTRAL, HOLDING }

    private class State {
        var phase: Phase = Phase.NEUTRAL
        var lastStickAngleRad: Float = 0f
        /** Timestamp (ms) of the most recent NEUTRAL → HOLDING transition. */
        var flickActivationMs: Long = 0L
        /** False during the post-activation lockout; see hold-phase handling. */
        var holdSettled: Boolean = false
        /**
         * Whether the next outward crossing is allowed to fire a flick. Set from
         * `flick_on_awake` on the first evaluate (so a stick already deflected when
         * the action set activates only flicks if the user opted in), then forced
         * true whenever the stick returns home.
         */
        var armed: Boolean = true
        var initialized: Boolean = false
        /** Exponential-smoothing accumulator for hold-phase sweep. */
        var smoothedSweep: Float = 0f
        /** Accumulated |sweep| degrees toward the next rotational haptic bump. */
        var hapticAccumDeg: Float = 0f
        var prevMagnitude: Float = 0f
        var prevTimestampMs: Long = 0L
    }

    private val stateBySource = mutableMapOf<InputSource, State>()

    /**
     * Clear all per-source state. Called from
     * [com.mapo.service.input.InputEvaluator.handleConfigChange] when a
     * source's mode changes off FLICK_STICK so the flick state machine
     * starts fresh next time the user picks FLICK_STICK again.
     */
    fun resetState() {
        synchronized(stateBySource) { stateBySource.clear() }
    }

    override fun evaluate(
        reading: AnalogEvent,
        ctx: ModeContext,
        digitalEmit: (subInput: String, isDown: Boolean) -> Unit,
        mouse: MouseEmitter,
    ) {
        if (reading.source != InputSource.LEFT_JOYSTICK && reading.source != InputSource.RIGHT_JOYSTICK) return
        val s = FlickStickSettings.parse(ctx.settingsJson)

        val state = synchronized(stateBySource) {
            stateBySource.getOrPut(reading.source) { State() }
        }

        // Outer-ring command — independent of the flick state machine, shared with
        // the other joystick modes.
        StickOuterRingHaptics.outerRingCommand(
            reading, s.commandRadius, s.commandInvert, ctx, digitalEmit, TAG,
        )

        val magnitude = kotlin.math.sqrt(reading.x * reading.x + reading.y * reading.y)
        // Capture the prior radial sample for release-dampening, then advance it.
        val prevMag = state.prevMagnitude
        val prevTs = state.prevTimestampMs
        state.prevMagnitude = magnitude
        state.prevTimestampMs = reading.timestampMs

        // First evaluate after a (re)start: "Allow Flick on Awake" decides whether a
        // stick already past the inner deadzone may flick immediately.
        if (!state.initialized) {
            state.armed = s.flickOnAwake
            state.initialized = true
        }

        // Stick home → reset; arm the next flick. Any in-flight flick playout
        // continues independently in the MouseEmitter (committed at activation).
        if (magnitude < s.innerDeadzone) {
            state.phase = Phase.NEUTRAL
            state.flickActivationMs = 0L
            state.holdSettled = false
            state.armed = true
            state.smoothedSweep = 0f
            state.hapticAccumDeg = 0f
            return
        }

        when (state.phase) {
            Phase.NEUTRAL -> {
                // atan2(x, -y): 0 = up, +π/2 = right, ±π = down, −π/2 = left.
                // Rotation Offset shifts the whole flick frame.
                val rawTarget = wrapPi(kotlin.math.atan2(reading.x, -reading.y) + s.rotationOffsetRad)
                if (state.armed && kotlin.math.abs(rawTarget) >= s.frontAngleDeadzoneRad) {
                    // Commit the flick: schedule target*velocity to play out over the
                    // flick-turn duration. Front Angle Deadzone suppresses flicks that
                    // are within `front_angle_deadzone` of forward (sweep-only intent).
                    val target = s.snapAngle(rawTarget)
                    val px = target * s.velocityPxPerRad
                    if (px != 0f) scheduleDelta(mouse, s, px)
                }
                // Enter HOLDING regardless (sweep tracking). The lockout only applies
                // when a flick actually fired; otherwise settle the baseline now.
                state.lastStickAngleRad = rawTarget
                state.smoothedSweep = 0f
                state.hapticAccumDeg = 0f
                if (state.armed && kotlin.math.abs(rawTarget) >= s.frontAngleDeadzoneRad) {
                    state.flickActivationMs = reading.timestampMs
                    state.holdSettled = false
                } else {
                    // No flick fired → no lockout; sweep immediately from here.
                    state.flickActivationMs = reading.timestampMs - s.flickTimeMs
                    state.holdSettled = true
                }
                state.phase = Phase.HOLDING
            }
            Phase.HOLDING -> {
                val elapsedMs = reading.timestampMs - state.flickActivationMs
                if (elapsedMs < s.flickTimeMs) {
                    // Lockout: flick still resolving. Suppress hold-phase emission and
                    // re-baseline at lockout end against wherever the stick drifted to.
                    return
                }
                val currentAngle = wrapPi(kotlin.math.atan2(reading.x, -reading.y) + s.rotationOffsetRad)
                if (!state.holdSettled) {
                    state.lastStickAngleRad = currentAngle
                    state.holdSettled = true
                    state.smoothedSweep = 0f
                    return
                }
                val delta = angularDelta(state.lastStickAngleRad, currentAngle)
                state.lastStickAngleRad = currentAngle

                // Sweep smoothing (Sweep Tightness): higher = more responsive.
                val alpha = s.sweepTightness.coerceIn(0.05f, 1f)
                state.smoothedSweep = state.smoothedSweep * (1f - alpha) + delta * alpha

                // Release Dampening: reduce sweep while the stick is moving home, so a
                // thumb bounce-off on release doesn't fire a stray sweep. Higher
                // setting = the stick must return faster to dampen.
                val dtSec = ((reading.timestampMs - prevTs).toFloat() / 1000f).coerceAtLeast(1e-3f)
                val inwardSpeed = ((prevMag - magnitude) / dtSec).coerceAtLeast(0f)
                val dampen = if (inwardSpeed <= 0f) {
                    1f
                } else if (s.releaseDampening <= 0f) {
                    0f // any inward motion fully dampens
                } else {
                    (1f - (inwardSpeed / s.releaseDampening).coerceIn(0f, 1f))
                }

                val sweptRad = state.smoothedSweep * s.sweepSensitivity * dampen
                val px = sweptRad * s.velocityPxPerRad
                if (px != 0f) emitDelta(mouse, s, px)

                // Rotational haptic bumps — one buzz per `degrees_per_haptic_bump` of
                // raw sweep, at the configured intensity.
                if (s.degreesPerHapticBump > 0f) {
                    val intensity = HapticIntensity.fromId(s.rotationalHaptics) ?: HapticIntensity.OFF
                    if (intensity != HapticIntensity.OFF) {
                        state.hapticAccumDeg += Math.toDegrees(kotlin.math.abs(delta).toDouble()).toFloat()
                        if (state.hapticAccumDeg >= s.degreesPerHapticBump) {
                            state.hapticAccumDeg = 0f
                            ctx.haptic(intensity)
                        }
                    }
                }
            }
        }
    }

    /** Schedule the flick burst on the configured output axis, honoring invert. */
    private fun scheduleDelta(mouse: MouseEmitter, s: FlickStickSettings, amount: Float) {
        val signed = if (s.invertOutput) -amount else amount
        val dur = s.flickTimeMs.toLong()
        if (s.outputVertical) mouse.scheduleSmoothDelta(0f, signed, dur)
        else mouse.scheduleSmoothDelta(signed, 0f, dur)
    }

    /** Emit a hold-phase sweep delta on the configured output axis, honoring invert. */
    private fun emitDelta(mouse: MouseEmitter, s: FlickStickSettings, amount: Float) {
        val signed = if (s.invertOutput) -amount else amount
        if (s.outputVertical) mouse.addRelativeDelta(0f, signed)
        else mouse.addRelativeDelta(signed, 0f)
    }

    /** Wrap an angle to (−π, π]. */
    private fun wrapPi(rad: Float): Float {
        var d = rad
        val twoPi = (2 * kotlin.math.PI).toFloat()
        while (d > kotlin.math.PI) d -= twoPi
        while (d < -kotlin.math.PI) d += twoPi
        return d
    }

    /**
     * Shortest signed angular difference between two angles. Wraps around
     * ±π so a 350°-to-10° transition is treated as +20° rather than −340°.
     */
    private fun angularDelta(fromRad: Float, toRad: Float): Float = wrapPi(toRad - fromRad)
}

/**
 * Parsed [FlickStickMode] settings — the full "Flick Stick" menu. Tolerant of
 * missing keys. Keys mirror
 * [com.mapo.ui.screen.remap.settings.SourceModeSettingsSchema]'s
 * `FLICK_STICK_CATEGORIES`.
 *
 * Stored slider units (normalized at parse): [dotsPer360] is mouse pixels per
 * full 360° (1..32000); [flickTurnTightness] / [sweepTightness] / [innerDeadzone]
 * / [outerDeadzone] are percentages (0..100); [rotationOffsetRad] /
 * [frontAngleDeadzoneRad] come from degrees; [commandRadius] is the raw Steam
 * 0..32767 radius (shared with the Joystick menu's slider for data consistency,
 * a minor deviation from the spec's %-display).
 *
 * Derived: [velocityPxPerRad] = dots-per-360 ÷ 2π (the px/radian the flick burst
 * + sweep emit at); [flickTimeMs] is derived from Flick Turn Tightness (higher =
 * shorter = snappier turn).
 *
 * Approximations flagged to the user pending on-device verification: [outerDeadzone]
 * is parsed but the one-shot flick model doesn't scale turn speed between inner/outer;
 * `forward_only` snap semantics (snap to forward only when roughly forward).
 */
internal data class FlickStickSettings(
    val dotsPer360: Float,
    val sweepSensitivity: Float,
    val rotationOffsetRad: Float,
    val snapMode: SnapMode,
    val frontAngleDeadzoneRad: Float,
    val flickTurnTightness: Float, // 0..1
    val sweepTightness: Float,     // 0..1
    val releaseDampening: Float,   // 0..10 units/sec
    val innerDeadzone: Float,      // 0..1
    val outerDeadzone: Float,      // 0..1
    val outputVertical: Boolean,   // false = horizontal (default), true = vertical
    val invertOutput: Boolean,
    val commandRadius: Float,      // 0..1 (normalized from 0..32767)
    val commandInvert: Boolean,
    val flickOnAwake: Boolean,
    val rotationalHaptics: String, // off / low / medium / high
    val degreesPerHapticBump: Float,
) {
    enum class SnapMode { NONE, HALF, QUARTER, SIXTHS, EIGHTHS, FORWARD_ONLY }

    /** px per radian = dots-per-360 over a full turn. */
    val velocityPxPerRad: Float get() = dotsPer360 / (2f * kotlin.math.PI.toFloat())

    /**
     * Flick-turn playout duration from tightness: 100% → fast (MIN), 0% → slow (MAX).
     * Calibrated against Steam Input (user 2026-06-15: our 80% felt a touch slower
     * than Steam's 80%) — the range was tightened 50–250ms → 40–200ms, so 80% ≈ 72ms.
     */
    val flickTimeMs: Int
        get() = (MAX_FLICK_MS - flickTurnTightness.coerceIn(0f, 1f) * (MAX_FLICK_MS - MIN_FLICK_MS))
            .toInt().coerceAtLeast(1)

    /**
     * Snap the flick's landing angle. For the evenly-divided modes (180/90/sixths/
     * eighths) the target quantizes to the nearest division at full strength.
     * `forward_only` snaps to forward (0) only when the flick is roughly forward
     * (within ±[FORWARD_ONLY_WINDOW_RAD]); other angles pass through unsnapped, so
     * you can reliably re-center while keeping precise turns elsewhere. Hold-phase
     * sweep is unaffected.
     */
    fun snapAngle(rawTargetRad: Float): Float {
        val divisions = when (snapMode) {
            SnapMode.NONE -> return rawTargetRad
            SnapMode.HALF -> 2
            SnapMode.QUARTER -> 4
            SnapMode.SIXTHS -> 6
            SnapMode.EIGHTHS -> 8
            SnapMode.FORWARD_ONLY ->
                return if (kotlin.math.abs(rawTargetRad) <= FORWARD_ONLY_WINDOW_RAD) 0f else rawTargetRad
        }
        val step = (2 * kotlin.math.PI / divisions).toFloat()
        return (kotlin.math.round(rawTargetRad / step) * step).toFloat()
    }

    companion object {
        const val MIN_FLICK_MS = 40
        const val MAX_FLICK_MS = 200
        val FORWARD_ONLY_WINDOW_RAD = (kotlin.math.PI / 4).toFloat() // ±45°

        const val DEFAULT_DOTS_PER_360 = 6545f
        const val DEFAULT_SWEEP_SENSITIVITY = 1f
        const val DEFAULT_FRONT_ANGLE_DEG = 7f
        const val DEFAULT_FLICK_TURN_TIGHTNESS = 0.80f
        const val DEFAULT_SWEEP_TIGHTNESS = 0.70f
        const val DEFAULT_RELEASE_DAMPENING = 2.5f
        const val DEFAULT_INNER_DEADZONE = 0.50f
        const val DEFAULT_OUTER_DEADZONE = 0.90f
        const val DEFAULT_COMMAND_RADIUS_RAW = 26214f // ≈ 80% of 32767 (spec default)
        const val DEFAULT_DEGREES_PER_HAPTIC_BUMP = 5f

        val DEFAULTS = FlickStickSettings(
            dotsPer360 = DEFAULT_DOTS_PER_360,
            sweepSensitivity = DEFAULT_SWEEP_SENSITIVITY,
            rotationOffsetRad = 0f,
            snapMode = SnapMode.FORWARD_ONLY,
            frontAngleDeadzoneRad = Math.toRadians(DEFAULT_FRONT_ANGLE_DEG.toDouble()).toFloat(),
            flickTurnTightness = DEFAULT_FLICK_TURN_TIGHTNESS,
            sweepTightness = DEFAULT_SWEEP_TIGHTNESS,
            releaseDampening = DEFAULT_RELEASE_DAMPENING,
            innerDeadzone = DEFAULT_INNER_DEADZONE,
            outerDeadzone = DEFAULT_OUTER_DEADZONE,
            outputVertical = false,
            invertOutput = false,
            commandRadius = DEFAULT_COMMAND_RADIUS_RAW / 32767f,
            commandInvert = false,
            flickOnAwake = false,
            rotationalHaptics = "medium",
            degreesPerHapticBump = DEFAULT_DEGREES_PER_HAPTIC_BUMP,
        )

        // All keys tolerant → "{}" seeds the spec defaults.
        val DEFAULT_JSON = "{}"

        fun parse(json: String): FlickStickSettings {
            if (json.isBlank()) return DEFAULTS
            return try {
                val obj = JSONObject(json)
                fun pct(key: String, defPct: Float) =
                    (obj.optDouble(key, defPct.toDouble()).toFloat() / 100f).coerceIn(0f, 1f)
                FlickStickSettings(
                    dotsPer360 = obj.optDouble("dots_per_360", DEFAULT_DOTS_PER_360.toDouble())
                        .toFloat().coerceIn(1f, 32000f),
                    sweepSensitivity = obj.optDouble("sweep_sensitivity", DEFAULT_SWEEP_SENSITIVITY.toDouble())
                        .toFloat().coerceIn(0f, 6f),
                    rotationOffsetRad = Math.toRadians(
                        obj.optDouble("rotation_offset", 0.0).coerceIn(-180.0, 180.0),
                    ).toFloat(),
                    snapMode = parseSnapMode(obj.optString("snap_angle", "forward_only")),
                    frontAngleDeadzoneRad = Math.toRadians(
                        obj.optDouble("front_angle_deadzone", DEFAULT_FRONT_ANGLE_DEG.toDouble())
                            .coerceIn(0.0, 180.0),
                    ).toFloat(),
                    flickTurnTightness = pct("flick_turn_tightness", DEFAULT_FLICK_TURN_TIGHTNESS * 100f),
                    sweepTightness = pct("sweep_tightness", DEFAULT_SWEEP_TIGHTNESS * 100f),
                    releaseDampening = obj.optDouble("release_dampening", DEFAULT_RELEASE_DAMPENING.toDouble())
                        .toFloat().coerceIn(0f, 10f),
                    innerDeadzone = pct("inner_deadzone", DEFAULT_INNER_DEADZONE * 100f),
                    outerDeadzone = pct("outer_deadzone", DEFAULT_OUTER_DEADZONE * 100f),
                    outputVertical = obj.optString("output_axis", "horizontal") == "vertical",
                    invertOutput = obj.optBoolean("invert_output", false),
                    commandRadius = (obj.optDouble("command_radius", DEFAULT_COMMAND_RADIUS_RAW.toDouble())
                        .toFloat() / 32767f).coerceIn(0f, 1f),
                    commandInvert = obj.optBoolean("command_invert", false),
                    flickOnAwake = obj.optBoolean("flick_on_awake", false),
                    rotationalHaptics = obj.optString("rotational_haptics", "medium"),
                    degreesPerHapticBump = obj.optDouble(
                        "degrees_per_haptic_bump", DEFAULT_DEGREES_PER_HAPTIC_BUMP.toDouble(),
                    ).toFloat().coerceIn(0f, 360f),
                )
            } catch (_: JSONException) {
                DEFAULTS
            }
        }

        private fun parseSnapMode(s: String): SnapMode = when (s.lowercase()) {
            "no_snapping", "none" -> SnapMode.NONE
            "180" -> SnapMode.HALF
            "90" -> SnapMode.QUARTER
            "sixths" -> SnapMode.SIXTHS
            "eighths" -> SnapMode.EIGHTHS
            "forward_only" -> SnapMode.FORWARD_ONLY
            else -> SnapMode.FORWARD_ONLY
        }
    }
}

/**
 * **Scroll Wheel.** Rotating the stick around its center — like turning a
 * physical dial — emits per-tick sub-input edges that the user binds to mouse
 * wheel commands (or any other action: next/prev weapon, hotbar cycle, etc.).
 *
 * **Behavior** (Steam-parity, verified against Steam Deck):
 *  - Below the radial deadzone, no ticks fire and the angular baseline resets.
 *  - Above the deadzone, the mode tracks the stick angle. Whenever the angle
 *    advances by [ScrollWheelSettings.scrollAngleDeg] in either direction, one
 *    tick fires on the matching sub-input. The baseline advances by exactly
 *    one threshold per emitted tick so sub-threshold remainder accumulates
 *    cleanly into the next tick.
 *  - Fast rotation that crosses multiple thresholds in a single evaluate
 *    fires multiple ticks back-to-back (e.g. a flicked stick).
 *
 * **Sub-inputs:**
 *  - `scroll_clockwise` — fires one DOWN→UP edge pair per clockwise tick.
 *  - `scroll_counter_clockwise` — same for counter-clockwise.
 *  - `click` / `outer_ring` — passthrough sub-inputs, same as the other
 *    joystick modes; this mode doesn't drive them.
 *
 * The DOWN→UP pair fires in the **same** [evaluate] call, so the activator
 * engine sees one full press cycle per tick (no held-down state across
 * events). A bind to `MOUSE_WHEEL_DOWN` therefore emits exactly one
 * `REL_WHEEL = -1` notch per tick via the existing `dispatchTargetAsClick`
 * → `injectMouseScroll` chain.
 *
 * **Angle convention** (matches [FlickStickMode]): `atan2(x, -y)` so 0 = stick
 * up, +π/2 = right, ±π = down, −π/2 = left. Rotating clockwise from the
 * user's perspective increases the angle, so positive angular delta →
 * `scroll_clockwise` (unless [ScrollWheelSettings.invert] is set).
 *
 * **Source filter.** Joystick sources only (LEFT_JOYSTICK / RIGHT_JOYSTICK).
 * Trackpad rotation would use the same model but Mapo's target hardware
 * lacks capacitive trackpads.
 *
 * **Per-source state** lives in [baselineAngleBySource]; cleared via
 * [resetState] from [com.mapo.service.input.InputEvaluator.handleConfigChange]
 * on mode change so a deflected stick from the prior mode doesn't leak in.
 */
object ScrollWheelMode : SourceMode {
    override val mode: BindingMode = BindingMode.SCROLL_WHEEL
    override fun validInputs(): Set<String> = INPUTS
    override fun defaultSettingsJson(): String = ScrollWheelSettings.DEFAULT_JSON
    private val INPUTS = setOf(
        "click",
        "outer_ring",
        "scroll_clockwise",
        "scroll_counter_clockwise",
    )

    /**
     * Most-recently-sampled stick angle per source. `null` = stick is in
     * deadzone (or this source has never been deflected this session). The
     * first deflection event after a deadzone period sets the baseline
     * without emitting; subsequent events accumulate angular delta against
     * it and fire ticks as the threshold is crossed.
     */
    private val baselineAngleBySource = mutableMapOf<InputSource, Float?>()

    fun resetState() {
        synchronized(baselineAngleBySource) { baselineAngleBySource.clear() }
    }

    override fun evaluate(
        reading: AnalogEvent,
        ctx: ModeContext,
        digitalEmit: (subInput: String, isDown: Boolean) -> Unit,
        mouse: MouseEmitter,
    ) {
        if (reading.source != InputSource.LEFT_JOYSTICK &&
            reading.source != InputSource.RIGHT_JOYSTICK
        ) return

        val settings = ScrollWheelSettings.parse(ctx.settingsJson)
        val magnitude = sqrt(reading.x * reading.x + reading.y * reading.y)

        // Below deadzone: clear baseline. Next deflection event will set
        // a fresh baseline rather than measuring against stale state.
        if (magnitude < settings.deadzone) {
            synchronized(baselineAngleBySource) { baselineAngleBySource[reading.source] = null }
            return
        }

        // Track a 1-D coordinate per swipe direction: stick angle (circular), or the
        // stick position on one axis (horizontal/vertical). Only the circular path
        // wraps around ±π.
        val circular = settings.swipeDirection == ScrollWheelSettings.SwipeDirection.CIRCULAR
        val coord = when (settings.swipeDirection) {
            ScrollWheelSettings.SwipeDirection.CIRCULAR -> atan2(reading.x, -reading.y)
            ScrollWheelSettings.SwipeDirection.VERTICAL -> -reading.y // up = forward
            ScrollWheelSettings.SwipeDirection.HORIZONTAL -> reading.x
        }
        val threshold = settings.tickThreshold()
        if (threshold <= 0f) return // defensive — settings clamp sensitivity, but be safe

        val baseline = synchronized(baselineAngleBySource) { baselineAngleBySource[reading.source] }
        if (baseline == null) {
            // First deflection sample — set baseline, emit nothing.
            synchronized(baselineAngleBySource) { baselineAngleBySource[reading.source] = coord }
            return
        }

        val delta = if (circular) wrapToPlusMinusPi(coord - baseline) else coord - baseline
        val absDelta = abs(delta)
        if (absDelta < threshold) {
            // Sub-threshold motion — let it accumulate; baseline unchanged.
            return
        }

        val ticks = (absDelta / threshold).toInt()
        val deltaSign = if (delta >= 0f) 1f else -1f
        val isClockwise = (deltaSign > 0f) xor settings.invert
        val subInput = if (isClockwise) "scroll_clockwise" else "scroll_counter_clockwise"
        val intensity = HapticIntensity.fromId(settings.hapticIntensity) ?: HapticIntensity.OFF

        // Emit one DOWN→UP pulse per tick crossed (back-to-back so the activator
        // engine sees N complete press cycles), with a haptic bump per tick.
        repeat(ticks) {
            digitalEmit(subInput, true)
            digitalEmit(subInput, false)
            if (intensity != HapticIntensity.OFF) ctx.haptic(intensity)
        }

        // Advance baseline by exactly N threshold-widths in the direction of
        // motion. Sub-threshold remainder stays in the baseline-to-current
        // gap and counts toward the next tick.
        val consumed = ticks * threshold * deltaSign
        val newBaseline = if (circular) wrapToPlusMinusPi(baseline + consumed) else baseline + consumed
        synchronized(baselineAngleBySource) { baselineAngleBySource[reading.source] = newBaseline }
    }

    /** Wrap an angle to (−π, +π]. */
    private fun wrapToPlusMinusPi(a: Float): Float {
        val twoPi = (2 * Math.PI).toFloat()
        var w = a
        while (w > Math.PI) w -= twoPi
        while (w <= -Math.PI) w += twoPi
        return w
    }
}

/**
 * Parsed [ScrollWheelMode] settings. Tolerant of missing keys; falls back
 * to defaults tuned to Steam's stock Scroll Wheel feel (16 ticks per
 * full stick revolution, 20% deadzone).
 *
 * **VDF mapping (Steam parity):** Steam's `scrollwheel` group settings —
 * `scroll_angle` (degrees per tick), `scroll_invert` (CW↔CCW swap),
 * `deadzone_inner_radius` — round-trip through this shape.
 *
 * [scrollAngleDeg] is the angular distance the stick must rotate to emit
 * one tick. Smaller = finer resolution (more ticks per revolution); larger
 * = coarser (Steam's lowest 45° → 8 ticks/rev for chunky weapon scrolls).
 */
internal data class ScrollWheelSettings(
    val deadzone: Float,
    /** Ticks per full motion (revolution for circular; full axis sweep for linear). */
    val sensitivity: Float,
    val swipeDirection: SwipeDirection,
    val invert: Boolean,
    val hapticIntensity: String, // off / low / medium / high
    /** Parsed for VDF round-trip; momentum/flywheel not yet implemented. */
    val spinFriction: String,
    /** Parsed for VDF round-trip; Mapo emits raw tick sub-inputs (no command list). */
    val wrapList: Boolean,
) {
    enum class SwipeDirection { HORIZONTAL, VERTICAL, CIRCULAR }

    /** Threshold per tick: radians (circular) or stick-units (linear). */
    fun tickThreshold(): Float =
        if (swipeDirection == SwipeDirection.CIRCULAR) {
            (2.0 * Math.PI / sensitivity).toFloat()
        } else {
            2f / sensitivity // full −1..+1 sweep = `sensitivity` ticks
        }

    companion object {
        /**
         * Radial deadzone — stick magnitude below this emits nothing. Not exposed
         * in the Scroll Wheel menu (the spec has none); kept as an internal floor
         * so idle stick noise doesn't fire spurious ticks.
         */
        const val DEFAULT_DEADZONE = 0.20f

        /** Ticks per full motion. Spec default 90; range 1 (coarse) .. 180 (fine). */
        const val DEFAULT_SENSITIVITY = 90f

        val DEFAULTS = ScrollWheelSettings(
            deadzone = DEFAULT_DEADZONE,
            sensitivity = DEFAULT_SENSITIVITY,
            swipeDirection = SwipeDirection.CIRCULAR,
            invert = false,
            hapticIntensity = "medium",
            spinFriction = "medium",
            wrapList = true,
        )

        // All keys tolerant → "{}" seeds the spec defaults.
        val DEFAULT_JSON = "{}"

        fun parse(json: String): ScrollWheelSettings {
            if (json.isBlank()) return DEFAULTS
            return try {
                val obj = JSONObject(json)
                ScrollWheelSettings(
                    deadzone = obj.optDouble("deadzone_inner_radius", DEFAULT_DEADZONE.toDouble())
                        .toFloat().coerceIn(0f, 0.99f),
                    sensitivity = obj.optDouble("sensitivity", DEFAULT_SENSITIVITY.toDouble())
                        .toFloat().coerceIn(1f, 180f),
                    swipeDirection = when (obj.optString("swipe_direction", "circular")) {
                        "horizontal" -> SwipeDirection.HORIZONTAL
                        "vertical" -> SwipeDirection.VERTICAL
                        else -> SwipeDirection.CIRCULAR
                    },
                    invert = obj.optBoolean("invert_swipe", false),
                    hapticIntensity = obj.optString("haptic_intensity", "medium"),
                    spinFriction = obj.optString("spin_friction", "medium"),
                    wrapList = obj.optBoolean("wrap_list", true),
                )
            } catch (_: JSONException) {
                DEFAULTS
            }
        }
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
    private const val TAG = "JoystickMoveMode"

    /** Delegates to the shared outer-ring/haptic state (see [StickOuterRingHaptics]). */
    fun resetState() = StickOuterRingHaptics.resetState()

    override fun evaluate(
        reading: AnalogEvent,
        ctx: ModeContext,
        digitalEmit: (subInput: String, isDown: Boolean) -> Unit,
        mouse: MouseEmitter,
    ) {
        // Joystick Move only handles the two analog stick sources. (The "click"
        // sub-input is the physical stick press — it arrives as a digital event
        // through onKeyEvent, not here.)
        when (reading.source) {
            InputSource.LEFT_JOYSTICK, InputSource.RIGHT_JOYSTICK -> Unit
            else -> return
        }
        // Analog-specific shaping (deadzone + response curve + outer ring).
        val analog = StickToAxisSettings.parse(ctx.settingsJson)
        // Output-knob shaping (scale / invert / rotate / axis-limit) — the SAME
        // tested path the digital Button-Pad/D-Pad → joystick synthesis uses, so
        // these knobs feel identical across digital and analog joystick sources.
        val shaping = JoystickOutputSettings.parse(ctx.settingsJson)

        // ── Analog stick output ──────────────────────────────────────────────
        // 1. Deadzone + response curve → a post-shaped vector in the raw +Y-down
        //    convention. 2. Hand to JoystickOutputSettings.apply, which works in
        //    +Y-up, so negate y on the way in and back out.
        val (dx, dyDown) = analog.toShaped(reading.x, reading.y)
        val (ox, oyUp) = shaping.apply(dx, -dyDown)
        val ax = ox
        val ay = -oyUp // back to +Y-down for the gamepad emitter

        // Output Joystick: which virtual stick this source emits as. Defaults to
        // the matching stick (Left source → left, Right source → right) when the
        // setting is unset (JoystickOutputSettings defaults non-source-awarely to
        // right, so resolve the source-aware default here).
        val toRight = when (analog.outputJoystick) {
            "left" -> false
            "right" -> true
            else -> reading.source == InputSource.RIGHT_JOYSTICK
        }
        if (toRight) ctx.gamepad.setRightStick(reading.source, ax, ay)
        else ctx.gamepad.setLeftStick(reading.source, ax, ay)

        // Outer-ring command + travel-based haptic (shared with Joystick Mouse).
        StickOuterRingHaptics.process(reading, analog, ctx, digitalEmit, TAG)
    }
}

/**
 * Shared outer-ring command + movement-driven ("texture") haptic for the
 * analog-stick modes (Joystick Move, Joystick Mouse). Both modes carry the same
 * [StickToAxisSettings] outer-ring + haptic fields and want identical behavior,
 * so the logic — and its per-source travel state — lives here once.
 *
 * A source is only ever in one of these modes at a time, so a single per-source
 * state map keyed by [InputSource] is safe; a mode/config change clears it via
 * [resetState] (called from [com.mapo.service.input.InputEvaluator.handleConfigChange]).
 */
internal object StickOuterRingHaptics {
    /**
     * Stick travel (in normalized units) between haptic buzzes. The Joystick
     * Haptic Intensity setting fires one buzz per this much accumulated movement
     * as the stick is moved — Steam's joystick-haptic feel. ~0.12 gives a handful
     * of bumps across a full center→edge sweep. Tunable.
     */
    private const val HAPTIC_TRAVEL_STEP = 0.12f

    private class HapticState {
        var lastX = 0f
        var lastY = 0f
        var travel = 0f
        var initialized = false
    }
    private val hapticStateBySource = mutableMapOf<InputSource, HapticState>()

    fun resetState() {
        synchronized(hapticStateBySource) { hapticStateBySource.clear() }
    }

    /**
     * Outer-ring command edge (shared by all analog-stick modes incl. Flick Stick).
     *
     * Distance metric = Chebyshev (max axis), i.e. the fraction of the way to the
     * stick's travel edge. The axes are each calibrated to ±1 independently, so the
     * reachable region is a square; clamped Euclidean magnitude would read the
     * entire corner region (everything outside the inscribed unit circle, from
     * ~71% diagonal travel onward) as a flat 1.0 — a too-wide ring that fires well
     * before the stick reaches its furthest point. Chebyshev removes that band: the
     * max radius setting now requires an axis to actually reach its edge. With
     * [commandInvert] the command fires *inside* the radius instead of outside
     * (Steam's "Walk/Sneak in the center" use case). [commandRadius] is 0..1.
     */
    fun outerRingCommand(
        reading: AnalogEvent,
        commandRadius: Float,
        commandInvert: Boolean,
        ctx: ModeContext,
        digitalEmit: (subInput: String, isDown: Boolean) -> Unit,
        tag: String,
    ) {
        val radius = maxOf(abs(reading.x), abs(reading.y)).coerceIn(0f, 1f)
        val want = if (commandInvert) radius < commandRadius else radius >= commandRadius
        val prior = ctx.priorLatched["outer_ring"] ?: false
        if (want != prior) {
            digitalEmit("outer_ring", want)
            // Diagnostic: logs the reported stick values at the boundary crossing.
            Log.d(
                tag,
                "outer_ring ${if (want) "DOWN" else "UP"} " +
                    "radius=%.3f thr=%.3f x=%.3f y=%.3f".format(
                        radius, commandRadius, reading.x, reading.y,
                    ),
            )
        }
    }

    fun process(
        reading: AnalogEvent,
        analog: StickToAxisSettings,
        ctx: ModeContext,
        digitalEmit: (subInput: String, isDown: Boolean) -> Unit,
        tag: String,
    ) {
        outerRingCommand(reading, analog.commandRadius, analog.commandInvert, ctx, digitalEmit, tag)

        // ── Travel-based haptic ──────────────────────────────────────────────
        // A texture buzz every HAPTIC_TRAVEL_STEP units of stick travel as the
        // stick is moved. Accumulates only on actual movement; at rest there's no
        // travel and no buzz. Skipped entirely when Off.
        val intensity = HapticIntensity.fromId(analog.hapticIntensity) ?: HapticIntensity.OFF
        if (intensity != HapticIntensity.OFF) {
            val st = hapticStateBySource.getOrPut(reading.source) { HapticState() }
            if (!st.initialized) {
                st.lastX = reading.x
                st.lastY = reading.y
                st.initialized = true
            } else {
                val mdx = reading.x - st.lastX
                val mdy = reading.y - st.lastY
                st.lastX = reading.x
                st.lastY = reading.y
                st.travel += sqrt(mdx * mdx + mdy * mdy)
                if (st.travel >= HAPTIC_TRAVEL_STEP) {
                    st.travel = 0f // drop sub-step remainder to avoid buzz bursts on big jumps
                    ctx.haptic(intensity)
                }
            }
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
 * **Mapo extension — no Steam equivalent.** Gyro yaw → mouse_dx
 * continuous mapping with an additive flick burst on rapid yaw gestures.
 * Loose analog to the joystick [FlickStickMode]: a quick wrist-flick
 * fires a one-shot rotation boost, while sustained slow yaw flows as
 * 1:1 (or settings-scaled) gyro→mouse. **Yaw-only — pitch is ignored**
 * (the design intent is camera turning, not look-up/down).
 *
 * **Steady-state** — every evaluate() emits a velocity proportional to
 * `reading.z` (yaw rate, rad/sec). Below `deadzone` (drift suppression),
 * the source's velocity slot is zeroed.
 *
 * **Flick gesture** — when `|yaw rate|` crosses `flick_threshold` AND
 * the source is armed (no recent flick in flight), schedule a
 * `flick_angle * velocity` burst via [MouseEmitter.scheduleSmoothDelta]
 * over `flick_time` ms. The burst is *additive* on top of the continuous
 * velocity — the user's flick gesture contributes its native yaw motion
 * PLUS the configured boost angle. Disarms until yaw rate drops below
 * the deadzone (rearm hysteresis), so one sustained rotation = one flick.
 *
 * **Sign convention** — positive `reading.z` = CCW yaw (top-edge-rotates-
 * left), which the user intends as "look left." Mouse-dx convention is
 * positive = look right, so the math sign-flips internally.
 *
 * **Source filter** — gyro only. Other sources are a no-op.
 *
 * **Per-source state** — armed/disarmed boolean keyed by [InputSource];
 * cleared via [resetState] from
 * [com.mapo.service.input.InputEvaluator.handleConfigChange] on mode
 * change.
 */
object GyroFlickStickMode : SourceMode {
    override val mode: BindingMode = BindingMode.GYRO_FLICK_STICK
    override fun validInputs(): Set<String> = emptySet()
    override fun defaultSettingsJson(): String = GyroFlickStickSettings.DEFAULT_JSON

    private val armedBySource = mutableMapOf<InputSource, Boolean>()

    /** Reset per-source arming state. Called on mode change. */
    fun resetState() {
        synchronized(armedBySource) { armedBySource.clear() }
    }

    override fun evaluate(
        reading: AnalogEvent,
        ctx: ModeContext,
        digitalEmit: (subInput: String, isDown: Boolean) -> Unit,
        mouse: MouseEmitter,
    ) {
        if (reading.source != InputSource.GYRO) return
        val settings = GyroFlickStickSettings.parse(ctx.settingsJson)

        val yawRate = reading.z  // rad/sec
        val absRate = kotlin.math.abs(yawRate)

        // Below deadzone: zero contribution and re-arm for the next flick.
        if (absRate < settings.deadzone) {
            mouse.setStickVelocity(reading.source, 0f, 0f)
            synchronized(armedBySource) { armedBySource[reading.source] = true }
            return
        }

        // Continuous yaw → mouse_dx velocity. Sign-flip from sensor
        // convention (+z = CCW = left) to mouse_dx convention (+dx = right).
        val baseVx = -yawRate * settings.velocityPxPerRad
        mouse.setStickVelocity(reading.source, baseVx, 0f)

        // Flick detection: rate above threshold AND armed → fire burst.
        val armed = synchronized(armedBySource) {
            armedBySource.getOrPut(reading.source) { true }
        }
        if (armed && absRate >= settings.flickThreshold) {
            // Sign matches the yaw direction (positive yaw rate → look-left
            // mouse, so negative dx; negative yaw → positive dx).
            val flickSign = if (yawRate > 0f) -1f else 1f
            val flickPx = flickSign * settings.flickAngleRad * settings.velocityPxPerRad
            mouse.scheduleSmoothDelta(flickPx, 0f, settings.flickTimeMs.toLong())
            synchronized(armedBySource) { armedBySource[reading.source] = false }
        }
    }
}

/**
 * Parsed [GyroFlickStickMode] settings. Tolerant of missing keys; falls
 * back to defaults tuned for FPS camera use.
 *
 * **Mapo-only — no VDF mapping** since Steam doesn't expose a gyro flick
 * stick mode. The settings live entirely in Mapo's JSON namespace.
 */
internal data class GyroFlickStickSettings(
    val deadzone: Float,
    val velocityPxPerRad: Float,
    val flickThreshold: Float,
    val flickAngleRad: Float,
    val flickTimeMs: Int,
) {
    companion object {
        /** Drift-suppression floor. Yaw rates below this emit zero. */
        const val DEFAULT_DEADZONE = 0.05f

        /**
         * Continuous yaw→mouse sensitivity. Comparable to the joystick Flick
         * Stick's derived px/radian (its Dots Per 360 ÷ 2π) so the two flick
         * stick modes feel consistent in their hold-phase sensitivity.
         */
        const val DEFAULT_VELOCITY_PX_PER_RAD = 1500f

        /**
         * Yaw rate at which a flick burst fires. ~2 rad/sec is a
         * deliberate-flick speed — slow tilts and fine aim don't trigger
         * it. Same value [DirectionalSwipeSettings] uses for its rate
         * threshold (gesture-detection symmetry).
         */
        const val DEFAULT_FLICK_THRESHOLD = 2.0f

        /**
         * Flick burst rotation amount. π/2 = 90° turn. Mapo extension —
         * user can dial to π (180°), 2π (full spin), etc.
         */
        val DEFAULT_FLICK_ANGLE_RAD = (kotlin.math.PI / 2.0).toFloat()

        /** Flick playout duration. Same default as joystick FLICK_STICK. */
        const val DEFAULT_FLICK_TIME_MS = 100

        val DEFAULTS = GyroFlickStickSettings(
            deadzone = DEFAULT_DEADZONE,
            velocityPxPerRad = DEFAULT_VELOCITY_PX_PER_RAD,
            flickThreshold = DEFAULT_FLICK_THRESHOLD,
            flickAngleRad = DEFAULT_FLICK_ANGLE_RAD,
            flickTimeMs = DEFAULT_FLICK_TIME_MS,
        )

        val DEFAULT_JSON =
            """{"deadzone":$DEFAULT_DEADZONE,"velocity":$DEFAULT_VELOCITY_PX_PER_RAD,""" +
                """"flick_threshold":$DEFAULT_FLICK_THRESHOLD,""" +
                """"flick_angle":$DEFAULT_FLICK_ANGLE_RAD,"flick_time":$DEFAULT_FLICK_TIME_MS}"""

        fun parse(json: String): GyroFlickStickSettings {
            if (json.isBlank()) return DEFAULTS
            return try {
                val obj = JSONObject(json)
                GyroFlickStickSettings(
                    deadzone = obj.optDouble("deadzone", DEFAULT_DEADZONE.toDouble())
                        .toFloat().coerceAtLeast(0f),
                    velocityPxPerRad = obj.optDouble("velocity", DEFAULT_VELOCITY_PX_PER_RAD.toDouble())
                        .toFloat().coerceAtLeast(1f),
                    flickThreshold = obj.optDouble("flick_threshold", DEFAULT_FLICK_THRESHOLD.toDouble())
                        .toFloat().coerceAtLeast(0f),
                    flickAngleRad = obj.optDouble("flick_angle", DEFAULT_FLICK_ANGLE_RAD.toDouble())
                        .toFloat(),
                    flickTimeMs = obj.optInt("flick_time", DEFAULT_FLICK_TIME_MS).coerceAtLeast(1),
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
 * The **analog-specific** subset of the "Joystick" mode settings for
 * [JoystickMoveMode] — deadzone (source / shape / threshold), response curve,
 * outer-ring command, plus the source-aware Output Joystick resolution. The
 * remaining output knobs (scale / invert / rotate / axis-limit / output-stick)
 * are owned by [JoystickOutputSettings] and applied *after* this, so those
 * knobs behave identically across the digital Button-Pad/D-Pad → joystick path
 * and this analog path. Keys mirror
 * [com.mapo.ui.screen.remap.settings.SourceModeSettingsSchema]'s
 * `JOYSTICK_CATEGORIES`.
 *
 * [toShaped] outputs a deadzone- and curve-shaped `(x, y)` in the raw **+Y-down**
 * convention (no scale/invert/rotate yet); the caller hands it to
 * [JoystickOutputSettings.apply] for the rest. Tolerant of missing keys.
 *
 * **Shaping pipeline** (each setting independently observable when tested):
 *  1. Deadzone by [deadzoneShape] → dead band rescaled out so a stick just past
 *     the inner edge produces non-zero output.
 *  2. Response curve by [responseAxisStyle] (per-axis vs. circular distance).
 *
 * Units as stored by the schema's sliders: deadzone handles are percentages
 * (0..100); [commandRadius] is the raw Steam 0..32767 radius. Normalized at
 * parse time.
 *
 * [hapticIntensity] + the outer-ring fields are consumed by [StickOuterRingHaptics]
 * (the outer-ring command edge + the movement-driven joystick haptic), shared
 * with Joystick Mouse. [parse] takes per-mode defaults for the response curve +
 * axis style because Joystick Mouse defaults them differently (Wide / Per axis)
 * than Joystick Move (Linear / Circular).
 */
internal data class StickToAxisSettings(
    val curve: String,             // linear/aggressive/relaxed/wide/extra_wide/custom
    val customCurve: Float,        // raw 25..375
    val responseAxisStyle: String, // per_axis / circular
    val outputJoystick: String?,   // "left" / "right" / null (= match source)
    val deadzoneSource: String,    // device_default / none / custom
    val deadzoneInner: Float,      // 0..1
    val deadzoneOuter: Float,      // 0..1
    val deadzoneShape: String,     // cross / circle / square
    val commandRadius: Float,      // 0..1 (normalized from 0..32767)
    val commandInvert: Boolean,
    val hapticIntensity: String,   // off / low / medium / high (not yet actuated)
) {
    /** Response-curve exponent — higher = more low-end fine control. */
    private val exponent: Float
        get() = when (curve) {
            "aggressive" -> 0.6f
            "relaxed" -> 1.5f
            "wide" -> 2.2f
            "extra_wide" -> 3.0f
            // Custom: 200 (slider default) → 1.0 exponent; higher slider = reaches
            // max sooner (lower exponent), lower slider = more low-end (higher exponent).
            "custom" -> (200f / customCurve.coerceAtLeast(1f)).coerceIn(0.2f, 10f)
            else -> 1.0f // linear
        }

    /** Effective (inner, outer) radial deadzone in 0..1. */
    private fun innerOuter(): Pair<Float, Float> = when (deadzoneSource) {
        "none" -> 0f to 1f
        "custom" -> deadzoneInner to deadzoneOuter.coerceAtLeast(deadzoneInner + 1e-3f)
        else -> DEFAULT_INNER_DEADZONE to 1f // device_default
    }

    /**
     * Apply deadzone + response curve to a raw stick reading. Returns the shaped
     * vector in the raw +Y-down convention; scale/invert/rotate/axis-limit are
     * applied afterward by [JoystickOutputSettings.apply].
     */
    fun toShaped(rawX: Float, rawY: Float): Pair<Float, Float> {
        val (inner, outer) = innerOuter()
        // 1. Deadzone by shape → gated (gx, gy) in -1..1 (dead band rescaled out).
        val (gx, gy) = when (deadzoneShape) {
            "cross" -> axisGate(rawX, inner, outer) to axisGate(rawY, inner, outer)
            "square" -> squareGate(rawX, rawY, inner, outer)
            else -> circleGate(rawX, rawY, inner, outer) // circle (default)
        }
        // 2. Response curve, per the response-axis style.
        return if (responseAxisStyle == "per_axis") {
            curveAxis(gx) to curveAxis(gy)
        } else { // circular: curve the distance from center, preserve direction
            curveRadial(gx, gy)
        }
    }

    /** Apply the response exponent to a single signed axis value. */
    private fun curveAxis(v: Float): Float {
        val a = abs(v).coerceIn(0f, 1f)
        val shaped = a.toDouble().pow(exponent.toDouble()).toFloat()
        return if (v < 0f) -shaped else shaped
    }

    /** Apply the response exponent to the vector's magnitude, keeping direction. */
    private fun curveRadial(gx: Float, gy: Float): Pair<Float, Float> {
        val m = sqrt(gx * gx + gy * gy).coerceIn(0f, 1f)
        if (m <= 0f) return 0f to 0f
        val shaped = m.toDouble().pow(exponent.toDouble()).toFloat()
        val k = shaped / m
        return gx * k to gy * k
    }

    companion object {
        // A modest inner deadzone for "device default" — we read raw /dev/input,
        // which doesn't apply the device's own deadzone, so a small floor avoids
        // resting-stick drift. Matches the prior JoystickMove default feel.
        const val DEFAULT_INNER_DEADZONE = 0.10f

        val DEFAULTS = StickToAxisSettings(
            curve = "linear",
            customCurve = 200f,
            responseAxisStyle = "circular",
            outputJoystick = null,
            deadzoneSource = "device_default",
            deadzoneInner = 0f,
            deadzoneOuter = 0.5f,
            deadzoneShape = "circle",
            commandRadius = 25000f / 32767f,
            commandInvert = false,
            hapticIntensity = "off",
        )

        // All keys tolerant of absence → "{}" seeds a stock-feel left/right stick.
        val DEFAULT_JSON = "{}"

        /**
         * Radial (circle) deadzone gate: input below [inner] = 0, between =
         * ramped, at/above [outer] = full. Returns the rescaled vector.
         */
        private fun circleGate(x: Float, y: Float, inner: Float, outer: Float): Pair<Float, Float> {
            val m = sqrt(x * x + y * y).coerceIn(0f, 1f)
            if (m <= inner) return 0f to 0f
            val rm = ((m - inner) / (outer - inner)).coerceIn(0f, 1f)
            val k = rm / m
            return x * k to y * k
        }

        /**
         * Per-axis (cross) deadzone gate for one axis: a + shaped band along
         * each axis. Best for navigation where a near-cardinal hold shouldn't
         * drift on the off-axis.
         */
        private fun axisGate(v: Float, inner: Float, outer: Float): Float {
            val a = abs(v).coerceIn(0f, 1f)
            if (a <= inner) return 0f
            val r = ((a - inner) / (outer - inner)).coerceIn(0f, 1f)
            return if (v < 0f) -r else r
        }

        /**
         * Square deadzone: cross inner deadzone, with the circular extent mapped
         * out toward a square so diagonals reach max sooner. Approximated by
         * cross-gating each axis then pushing the vector toward the square by the
         * larger unit component.
         */
        private fun squareGate(x: Float, y: Float, inner: Float, outer: Float): Pair<Float, Float> {
            val gx = axisGate(x, inner, outer)
            val gy = axisGate(y, inner, outer)
            val maxComp = maxOf(abs(gx), abs(gy))
            if (maxComp <= 0f) return 0f to 0f
            val m = sqrt(gx * gx + gy * gy).coerceIn(0f, 1f)
            // Scale the radial magnitude out to the square boundary.
            val k = (m / maxComp).coerceAtMost(1f / maxComp)
            return (gx * k).coerceIn(-1f, 1f) to (gy * k).coerceIn(-1f, 1f)
        }

        fun parse(
            json: String,
            curveDefault: String = "linear",
            axisStyleDefault: String = "circular",
        ): StickToAxisSettings {
            if (json.isBlank()) {
                return DEFAULTS.copy(curve = curveDefault, responseAxisStyle = axisStyleDefault)
            }
            return try {
                val obj = JSONObject(json)
                StickToAxisSettings(
                    curve = obj.optString("stick_response_curve", curveDefault),
                    customCurve = obj.optDouble("custom_response_curve", 200.0).toFloat()
                        .coerceIn(25f, 375f),
                    responseAxisStyle = obj.optString("response_axis_style", axisStyleDefault),
                    outputJoystick = if (obj.has("output_joystick")) obj.optString("output_joystick") else null,
                    deadzoneSource = obj.optString("deadzone_source", "device_default"),
                    deadzoneInner = (obj.optDouble("deadzone_inner", 0.0).toFloat() / 100f)
                        .coerceIn(0f, 1f),
                    deadzoneOuter = (obj.optDouble("deadzone_outer", 50.0).toFloat() / 100f)
                        .coerceIn(0f, 1f),
                    deadzoneShape = obj.optString("deadzone_shape", "circle"),
                    commandRadius = (obj.optDouble("command_radius", 25000.0).toFloat() / 32767f)
                        .coerceIn(0f, 1f),
                    commandInvert = obj.optBoolean("command_invert", false),
                    hapticIntensity = obj.optString("haptic_intensity", "off"),
                )
            } catch (_: JSONException) {
                DEFAULTS
            }
        }
    }
}

/**
 * The mouse-output stage of the "Joystick Mouse" menu — converts the
 * deadzone-+-curve-shaped stick vector from [StickToAxisSettings.toShaped] into a
 * cursor velocity (pixels/sec). Owns the knobs that are mouse-specific: overall
 * mouse sensitivity (%), per-axis scale (%), output-axis limit, and rotation.
 * (Deadzone / response curve / outer-ring / haptics live in [StickToAxisSettings]
 * and are shared with Joystick Move.)
 *
 * No Output-Joystick entry (the output is the cursor). Invert horizontal /
 * vertical are a Mapo extension (the Steam spec omits them for this mode) added
 * at the user's request.
 *
 * **toVelocity** takes the already-shaped `(sx, sy)` (magnitude 0..1, +Y-down):
 *  1. Rotate the vector by [rotateRadians] (same convention as Joystick Move:
 *     +90° maps forward → right).
 *  2. Multiply by [sensitivityPxPerSec] and the per-axis scale.
 *  3. Per-axis invert.
 *  4. Apply the output-axis limit.
 */
internal data class MouseOutputSettings(
    val sensitivityPxPerSec: Float,
    val horizontalScale: Float, // 0..1
    val verticalScale: Float,   // 0..1
    val invertHorizontal: Boolean,
    val invertVertical: Boolean,
    val rotateRadians: Float,
    val outputAxis: String,     // horizontal / vertical / both
) {
    fun toVelocity(shapedX: Float, shapedY: Float): Pair<Float, Float> {
        var x = shapedX
        var y = shapedY
        if (rotateRadians != 0f) {
            val c = kotlin.math.cos(rotateRadians)
            val s = kotlin.math.sin(rotateRadians)
            val nx = x * c - y * s
            val ny = x * s + y * c
            x = nx
            y = ny
        }
        var vx = x * sensitivityPxPerSec * horizontalScale
        var vy = y * sensitivityPxPerSec * verticalScale
        if (invertHorizontal) vx = -vx
        if (invertVertical) vy = -vy
        when (outputAxis) {
            "horizontal" -> vy = 0f
            "vertical" -> vx = 0f
        }
        return vx to vy
    }

    companion object {
        /**
         * Pixels/sec at full deflection per 100% mouse sensitivity. Calibrated so
         * the spec default of 275% matches Steam Input's cursor speed at the same
         * setting. Tuned against user device measurements: base 291 read ~0.30×
         * Steam, so the base is ~291/0.30 ≈ 970 → ~2668 px/sec at 275%. (Two passes:
         * 291 → 485 was still ~0.5× Steam, → 970.)
         */
        const val PX_PER_SEC_AT_100 = 970f
        const val DEFAULT_SENSITIVITY_PCT = 275f

        val DEFAULTS = MouseOutputSettings(
            sensitivityPxPerSec = PX_PER_SEC_AT_100 * (DEFAULT_SENSITIVITY_PCT / 100f),
            horizontalScale = 1f,
            verticalScale = 1f,
            invertHorizontal = false,
            invertVertical = false,
            rotateRadians = 0f,
            outputAxis = "both",
        )

        // NOTE: the Joystick Mouse settings menu also declares `stick_response_curve`
        // (UI default "wide") and `response_axis_style` (UI default "per_axis"), which
        // this parser does NOT yet read. When wiring them, the runtime fallback MUST
        // match those UI defaultIds — mode switch never seeds settingsJson, so a group
        // with the key unset relies on the fallback agreeing with what the cog shows.
        // A divergence is the silent "UI says X, runtime does Y" bug (see DpadSettings
        // DEFAULT_LAYOUT, which read 4-way while the dropdown showed 8-way).
        fun parse(json: String): MouseOutputSettings {
            if (json.isBlank()) return DEFAULTS
            return try {
                val obj = JSONObject(json)
                val pct = obj.optDouble("mouse_sensitivity", DEFAULT_SENSITIVITY_PCT.toDouble())
                    .toFloat().coerceIn(10f, 10000f)
                MouseOutputSettings(
                    sensitivityPxPerSec = PX_PER_SEC_AT_100 * (pct / 100f),
                    horizontalScale = (obj.optDouble("horizontal_scale", 100.0).toFloat() / 100f)
                        .coerceIn(0f, 1f),
                    verticalScale = (obj.optDouble("vertical_scale", 100.0).toFloat() / 100f)
                        .coerceIn(0f, 1f),
                    invertHorizontal = obj.optBoolean("invert_horizontal", false),
                    invertVertical = obj.optBoolean("invert_vertical", false),
                    rotateRadians = Math.toRadians(
                        obj.optDouble("rotate_output", 0.0).coerceIn(-180.0, 180.0),
                    ).toFloat(),
                    outputAxis = obj.optString("output_axis", "both"),
                )
            } catch (_: JSONException) {
                DEFAULTS
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
 * whose runtime ships in later Phase 7 bricks (Hotbar / Radial / Touch Menu /
 * Reference).
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
    BindingMode.FLICK_STICK -> FlickStickMode
    BindingMode.GYRO_FLICK_STICK -> GyroFlickStickMode
    BindingMode.SCROLL_WHEEL -> ScrollWheelMode
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
    BindingMode.GYRO_FLICK_STICK,             // uinput mouse for continuous yaw + flick burst
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
 *
 * **DPAD belongs here (corrected 2026-06-09).** The earlier assumption that
 * Android delivers the physical D-Pad as `KEYCODE_DPAD_*` to the accessibility
 * service is FALSE on Mapo's target hardware (AYN Thor / Odin 2 Mini / Anbernic
 * / Retroid): those devices report the D-Pad as an `ABS_HAT0X` / `ABS_HAT0Y`
 * hat axis, not as key events, so `InputAccessibilityService.onKeyEvent` never
 * sees it and the digital `handleDigital` path is never entered. The D-Pad
 * therefore reaches Mapo ONLY through the Shizuku raw reader (as an analog hat
 * event) and ONLY while grabbed. [InputEvaluator.dispatchReadings] bridges that
 * grabbed hat into the digital `dpad_*` edge pipeline so every D-Pad mode (None
 * silence / Directional Pad / Button Pad / Joystick synthesis) works uniformly.
 * Without DPAD in this set there is no grab, no hat readings, and nothing fires —
 * which is exactly what broke every mode including None when DPAD was removed.
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
        BindingMode.SCROLL_WHEEL -> JOYSTICK_CLICK_OUTER_RING + SCROLL_WHEEL_ROTATION_INPUTS
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
        BindingMode.GYRO_FLICK_STICK,
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
private val SCROLL_WHEEL_ROTATION_INPUTS = setOf("scroll_clockwise", "scroll_counter_clockwise")

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
            BindingMode.GYRO_FLICK_STICK,  // Mapo extension; no Steam equivalent
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
