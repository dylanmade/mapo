package com.mappo.ui.screen.remap.settings

import com.mappo.data.model.steam.BindingMode
import com.mappo.data.model.steam.InputSource

/**
 * Declarative schema for the per-(input source x mode) settings exposed behind the
 * settings cog on each source row in Remap Controls. One shared renderer
 * ([com.mappo.ui.screen.remap.settings.ModeSettingsSheet]) draws every spec
 * identically and reads/writes its value into the binding group's `settingsJson`.
 *
 * Built menu-by-menu (top-to-bottom by input type per the build plan); see
 * `project_source_mode_settings_build.md`. The audit of every (setting x menu)
 * permutation lives in ~/Downloads/settings_audit.csv.
 *
 * VDF round-trip is a first-class constraint: option [DropdownOption.id]s and
 * stored slider values are the canonical persisted form, chosen to map cleanly
 * onto Steam Input's VDF tokens.
 */

/** One option in a [SettingControl.Dropdown]. [id] is the value stored in JSON. */
data class DropdownOption(
    val id: String,
    val label: String,
    val helper: String? = null,
)

/** Control type + bounds/defaults for a [SettingSpec]. */
sealed interface SettingControl {
    /** Boolean switch. Off by default unless [default] is true. */
    data class Toggle(val default: Boolean = false) : SettingControl

    /**
     * Continuous (or stepped) numeric value. Every slider also exposes a manual
     * numeric entry field per the spec convention — TODO(next slice): wire the
     * editable field; the Button Pad menu has no sliders so it isn't exercised yet.
     */
    data class Slider(
        val min: Float,
        val max: Float,
        val default: Float,
        val step: Float? = null,
        val unitSuffix: String = "",
        val decimals: Int = 0,
    ) : SettingControl

    /** Single-choice dropdown. [defaultId] must match one of [options]' ids. */
    data class Dropdown(
        val options: List<DropdownOption>,
        val defaultId: String,
    ) : SettingControl

    /**
     * Multi-select physical-input picker (e.g. the gyro enable button(s)).
     * Stored as a JSON array of `"SOURCE|inputKey"` strings under the spec key;
     * the renderer captures presses via the accessibility service's capture mode.
     */
    data object InputPicker : SettingControl

    /**
     * Dual-handle range (e.g. an inner/outer deadzone). Stores two values under
     * [startKey] (the lower handle) and [endKey] (the upper handle); the lower can't
     * pass the upper. Both handles also expose a manual numeric entry field.
     */
    data class RangeSlider(
        val min: Float,
        val max: Float,
        val startKey: String,
        val endKey: String,
        val startDefault: Float,
        val endDefault: Float,
        val step: Float? = null,
        val unitSuffix: String = "",
        val decimals: Int = 0,
    ) : SettingControl
}

/** Read-only view of the current stored settings, for [SettingSpec.visibleWhen]. */
fun interface SettingsLookup {
    /** Current stored value for [key] as a string, or null if unset. */
    fun raw(key: String): String?
}

/**
 * One setting. The renderer stores its value under [key] in the group's
 * settingsJson. [visibleWhen] gates conditional settings off the current values
 * (e.g. a custom-curve slider that only appears when the curve dropdown = Custom).
 */
data class SettingSpec(
    val key: String,
    val label: String,
    val helper: String? = null,
    val control: SettingControl,
    val visibleWhen: ((SettingsLookup) -> Boolean)? = null,
)

/** A titled group of settings within a menu (e.g. "Haptics", "Sensitivity"). */
data class SettingCategory(
    val title: String,
    val settings: List<SettingSpec>,
)

object SourceModeSettingsSchema {

    // ── Shared option sets (reused across menus) ─────────────────────────────
    private val HAPTIC_OVERRIDE_OPTIONS = listOf(
        DropdownOption("use_activator", "Use activator settings"),
        DropdownOption("off", "Off"),
        DropdownOption("low", "Low"),
        DropdownOption("medium", "Medium"),
        DropdownOption("high", "High"),
    )

    // ── Shared specs ─────────────────────────────────────────────────────────
    private val HAPTIC_INTENSITY_OVERRIDE = SettingSpec(
        key = "haptic_intensity_override",
        label = "Haptic intensity override",
        helper = "Override the per-activator haptic strength for this input.",
        control = SettingControl.Dropdown(HAPTIC_OVERRIDE_OPTIONS, defaultId = "use_activator"),
    )

    private val HAPTIC_INTENSITY_OPTIONS = listOf(
        DropdownOption("off", "Off"),
        DropdownOption("low", "Low"),
        DropdownOption("medium", "Medium"),
        DropdownOption("high", "High"),
    )

    private val RESPONSE_CURVE_OPTIONS = listOf(
        DropdownOption("linear", "Linear", "Maps input directly to output 1:1. At 50% deflection, 50% output."),
        DropdownOption("aggressive", "Aggressive", "Reaches 100% output faster — less slow range, quicker response."),
        DropdownOption("relaxed", "Relaxed", "Reaches 100% output slower — more slow range for fine control."),
        DropdownOption("wide", "Wide", "A wide band of low values that ramps up quickly at the outer edge."),
        DropdownOption("extra_wide", "Extra wide", "An even larger band of low values, only reaching 100% at the extremes."),
        DropdownOption("custom", "Custom curve", "Define the curve with the Custom response curve slider."),
    )

    private val RESPONSE_AXIS_OPTIONS = listOf(
        DropdownOption("per_axis", "Per axis", "Curve applied to each axis separately; a circle biases toward the axes."),
        DropdownOption("circular", "Circular", "Curve applied to distance from center; a circle has no directional bias."),
    )

    private val OUTPUT_JOYSTICK_OPTIONS = listOf(
        DropdownOption("left", "Left joystick"),
        DropdownOption("right", "Right joystick"),
    )

    private val OUTPUT_AXIS_OPTIONS = listOf(
        DropdownOption("horizontal", "Horizontal only"),
        DropdownOption("vertical", "Vertical only"),
        DropdownOption("both", "Both"),
    )

    private val DEADZONE_SOURCE_OPTIONS = listOf(
        DropdownOption("device_default", "Device default", "Passes through the device's own deadzone with no adjustment."),
        DropdownOption("none", "No deadzone", "Overrides and removes any hardware deadzone entirely."),
        DropdownOption("custom", "Custom deadzone", "Set a custom deadzone for this configuration."),
    )

    private val DEADZONE_SHAPE_OPTIONS = listOf(
        DropdownOption("cross", "Cross", "Per-axis band; best for navigation where one direction shouldn't drift."),
        DropdownOption("circle", "Circle", "Radial deadzone; input between inner and outer is ramped."),
        DropdownOption("square", "Square", "Cross inner deadzone, output mapped to a square — faster diagonals."),
    )

    // ── Shared Joystick-mode specs (reused by every "Joystick" menu) ─────────────
    private val HORIZONTAL_SCALE = SettingSpec(
        key = "horizontal_scale",
        label = "Horizontal scale",
        control = SettingControl.Slider(0f, 100f, default = 100f, unitSuffix = "%"),
    )
    private val VERTICAL_SCALE = SettingSpec(
        key = "vertical_scale",
        label = "Vertical scale",
        control = SettingControl.Slider(0f, 100f, default = 100f, unitSuffix = "%"),
    )
    private val INVERT_HORIZONTAL = SettingSpec(
        key = "invert_horizontal",
        label = "Invert horizontal axis",
        control = SettingControl.Toggle(),
    )
    private val INVERT_VERTICAL = SettingSpec(
        key = "invert_vertical",
        label = "Invert vertical axis",
        control = SettingControl.Toggle(),
    )
    private val STICK_RESPONSE_CURVE = SettingSpec(
        key = "stick_response_curve",
        label = "Stick response curve",
        helper = "How analog input maps to output.",
        control = SettingControl.Dropdown(RESPONSE_CURVE_OPTIONS, defaultId = "linear"),
    )
    private val CUSTOM_RESPONSE_CURVE = SettingSpec(
        key = "custom_response_curve",
        label = "Custom response curve",
        helper = "Lower = push further before reaching max; higher = max almost immediately past the deadzone.",
        control = SettingControl.Slider(25f, 375f, default = 200f),
        visibleWhen = { it.raw("stick_response_curve") == "custom" },
    )
    private val RESPONSE_AXIS_STYLE = SettingSpec(
        key = "response_axis_style",
        label = "Response axis style",
        helper = "Apply the curve per axis, or by distance from the deadzone.",
        control = SettingControl.Dropdown(RESPONSE_AXIS_OPTIONS, defaultId = "circular"),
    )
    private val OUTPUT_JOYSTICK = SettingSpec(
        key = "output_joystick",
        label = "Output joystick",
        helper = "Which gamepad stick this source emits as.",
        control = SettingControl.Dropdown(OUTPUT_JOYSTICK_OPTIONS, defaultId = "right"),
    )
    private val OUTPUT_AXIS = SettingSpec(
        key = "output_axis",
        label = "Output axis",
        helper = "Limit output to a single axis if desired.",
        control = SettingControl.Dropdown(OUTPUT_AXIS_OPTIONS, defaultId = "both"),
    )
    private val ROTATE_OUTPUT = SettingSpec(
        key = "rotate_output",
        label = "Rotate output",
        helper = "Rotates the output vector. At 90°, forward reads as right.",
        control = SettingControl.Slider(-180f, 180f, default = 0f, unitSuffix = "°"),
    )
    private val DEADZONE_SOURCE = SettingSpec(
        key = "deadzone_source",
        label = "Deadzone source",
        control = SettingControl.Dropdown(DEADZONE_SOURCE_OPTIONS, defaultId = "device_default"),
    )
    private val DEADZONE_THRESHOLD = SettingSpec(
        key = "deadzone_threshold",
        label = "Deadzone threshold",
        helper = "Below the inner handle = no output; between = ramp; at or past the outer = max.",
        control = SettingControl.RangeSlider(
            min = 0f, max = 100f,
            startKey = "deadzone_inner", endKey = "deadzone_outer",
            startDefault = 0f, endDefault = 50f, unitSuffix = "%",
        ),
        visibleWhen = { it.raw("deadzone_source") == "custom" },
    )
    private val DEADZONE_SHAPE = SettingSpec(
        key = "deadzone_shape",
        label = "Deadzone shape",
        control = SettingControl.Dropdown(DEADZONE_SHAPE_OPTIONS, defaultId = "circle"),
        visibleWhen = { it.raw("deadzone_source") == "custom" },
    )
    private val COMMAND_RADIUS = SettingSpec(
        key = "command_radius",
        label = "Command radius",
        helper = "Past this radius, the assigned outer-ring command fires (e.g. sprint at the edge).",
        control = SettingControl.Slider(1f, 32767f, default = 25000f),
    )
    private val COMMAND_INVERT = SettingSpec(
        key = "command_invert",
        label = "Command invert",
        helper = "Fire the command inside the radius instead of outside (e.g. walk in the center).",
        control = SettingControl.Toggle(),
    )
    private val HAPTIC_INTENSITY = SettingSpec(
        key = "haptic_intensity",
        label = "Haptic intensity",
        control = SettingControl.Dropdown(HAPTIC_INTENSITY_OPTIONS, defaultId = "off"),
    )

    // ── Trigger-mode specs (Triggers → Trigger; one menu per L/R trigger) ────────
    private val THRESHOLD_TRIGGER_STYLE_OPTIONS = listOf(
        DropdownOption("simple_threshold", "Simple threshold", "Soft Pull fires first as you pull, then Full Pull. Good for mouse clicks and drags."),
        DropdownOption("hair_trigger", "Hair trigger", "Rapid Soft Pull on quick pulls & releases. Good for action games."),
        DropdownOption("hip_fire_aggressive", "Hip fire aggressive", "Quick pulls send only Full Pull; slower pulls send Soft Pull first, then Full Pull."),
        DropdownOption("hip_fire_normal", "Hip fire normal", "Like aggressive, with a larger window to skip the Soft Pull."),
        DropdownOption("hip_fire_relaxed", "Hip fire relaxed", "An even larger window to skip the Soft Pull."),
        DropdownOption("hip_fire_exclusive", "Hip fire exclusive", "Once Full or Soft Pull fires, the other can't fire until the trigger is fully released."),
    )
    private val ANALOG_OUTPUT_TRIGGER_OPTIONS = listOf(
        DropdownOption("left", "Left trigger"),
        DropdownOption("right", "Right trigger"),
        DropdownOption("off", "Analog off"),
    )

    private val THRESHOLD_TRIGGER_STYLE = SettingSpec(
        key = "threshold_trigger_style",
        label = "Threshold trigger style",
        helper = "How the Soft Pull and Full Pull commands are timed as you pull.",
        control = SettingControl.Dropdown(THRESHOLD_TRIGGER_STYLE_OPTIONS, defaultId = "hair_trigger"),
    )
    private val TRIGGER_THRESHOLD_POINT = SettingSpec(
        key = "trigger_threshold",
        label = "Trigger threshold point",
        helper = "The Soft Pull command fires once the trigger is pulled past this point. Left = lighter press; right = heavier.",
        // Range is Steam-canonical 0..32767 for VDF round-trip. Default ≈10% (3277/32767)
        // per the user's call to keep the existing light soft-pull feel rather than
        // adopt the spec's heavier 10000 (~30%). Matches the runtime default 0.10.
        control = SettingControl.Slider(0f, 32767f, default = 3277f),
    )
    private val TRIGGER_RESPONSE_CURVE = SettingSpec(
        key = "trigger_response_curve",
        label = "Trigger response curve",
        helper = "How analog trigger pull maps to output.",
        control = SettingControl.Dropdown(RESPONSE_CURVE_OPTIONS, defaultId = "linear"),
    )
    private val TRIGGER_CUSTOM_RESPONSE_CURVE = SettingSpec(
        key = "trigger_custom_response_curve",
        label = "Custom response curve",
        helper = "Lower = pull further before reaching max; higher = max almost immediately past the threshold.",
        control = SettingControl.Slider(25f, 4000f, default = 1000f),
        visibleWhen = { it.raw("trigger_response_curve") == "custom" },
    )
    private val TRIGGER_HOLD_TO_REPEAT = SettingSpec(
        key = "hold_to_repeat",
        label = "Hold to repeat (turbo)",
        helper = "Repeatedly fire the command while the trigger is held past the threshold.",
        control = SettingControl.Toggle(),
    )
    private val TRIGGER_REPEAT_RATE = SettingSpec(
        key = "repeat_rate_ms",
        label = "Repeat rate",
        helper = "Time between turbo pulses — lower fires faster.",
        control = SettingControl.Slider(20f, 1000f, default = 150f, unitSuffix = " ms"),
        visibleWhen = { it.raw("hold_to_repeat") == "true" },
    )
    private val TRIGGER_RANGE_START = SettingSpec(
        key = "trigger_range_start",
        label = "Trigger range start",
        helper = "Analog deadzone at the start of the pull — a greater value sends no analog output until the trigger is pulled this far.",
        control = SettingControl.Slider(0f, 32767f, default = 1000f),
        visibleWhen = { it.raw("analog_output_trigger") != "off" },
    )
    private val TRIGGER_RANGE_END = SettingSpec(
        key = "trigger_range_end",
        label = "Trigger range end",
        helper = "The point past which the maximum analog value is sent — a smaller value reaches max earlier in the throw.",
        control = SettingControl.Slider(0f, 32767f, default = 32000f),
        visibleWhen = { it.raw("analog_output_trigger") != "off" },
    )

    /** Analog Output Trigger defaults to the matching trigger (source-aware). */
    private fun analogOutputTrigger(source: InputSource) = SettingSpec(
        key = "analog_output_trigger",
        label = "Analog output trigger",
        helper = "Send the trigger's analog value to the game as this XInput trigger. Only works with XInput-compatible games.",
        control = SettingControl.Dropdown(
            ANALOG_OUTPUT_TRIGGER_OPTIONS,
            defaultId = if (source == InputSource.RIGHT_TRIGGER) "right" else "left",
        ),
    )

    /** The "Trigger" mode menu for an analog trigger source (built per L/R for the source-aware default). */
    private fun triggerCategories(source: InputSource) = listOf(
        SettingCategory(
            "General",
            listOf(
                THRESHOLD_TRIGGER_STYLE, TRIGGER_THRESHOLD_POINT, TRIGGER_RESPONSE_CURVE,
                TRIGGER_CUSTOM_RESPONSE_CURVE, TRIGGER_HOLD_TO_REPEAT, TRIGGER_REPEAT_RATE,
            ),
        ),
        SettingCategory(
            "Analog output",
            listOf(analogOutputTrigger(source), TRIGGER_RANGE_START, TRIGGER_RANGE_END),
        ),
    )

    private val DIRECTIONAL_PAD_LAYOUT_OPTIONS = listOf(
        DropdownOption("8_way", "8-way (overlap)", "Pie layout whose diagonals activate both directional commands."),
        DropdownOption("4_way", "4-way (no overlap)", "Diagonals activate only the single nearest directional command."),
        DropdownOption("analog_emulation", "Analog emulation", "Directional commands are pulsed to simulate an analog stick."),
        DropdownOption("cross_gate", "Cross gate", "Cross-shaped pad that prioritizes horizontal/vertical over the diagonals."),
    )

    // Key matches the runtime DpadSettings ("dpad_layout") so the analog dpad menus reuse it.
    private val DIRECTIONAL_PAD_LAYOUT = SettingSpec(
        key = "dpad_layout",
        label = "Directional pad layout",
        control = SettingControl.Dropdown(DIRECTIONAL_PAD_LAYOUT_OPTIONS, defaultId = "8_way"),
    )

    /** The "Button Pad" mode menu for a digital cluster (Button Pad / D-Pad sources). */
    private val BUTTON_PAD_CATEGORIES = listOf(
        SettingCategory("Haptics", listOf(HAPTIC_INTENSITY_OVERRIDE)),
    )

    /** The "Directional Pad" mode menu for a digital cluster (Button Pad / D-Pad sources). */
    private val DIGITAL_DIRECTIONAL_PAD_CATEGORIES = listOf(
        SettingCategory("General", listOf(DIRECTIONAL_PAD_LAYOUT)),
        SettingCategory("Haptics", listOf(HAPTIC_INTENSITY_OVERRIDE)),
    )

    /** The "Joystick" mode menu — shared across Button Pad / D-Pad / Joystick sources. */
    private val JOYSTICK_CATEGORIES = listOf(
        SettingCategory(
            "Sensitivity",
            listOf(
                HORIZONTAL_SCALE, VERTICAL_SCALE, INVERT_HORIZONTAL, INVERT_VERTICAL,
                STICK_RESPONSE_CURVE, CUSTOM_RESPONSE_CURVE, RESPONSE_AXIS_STYLE,
            ),
        ),
        SettingCategory("Output", listOf(OUTPUT_JOYSTICK, OUTPUT_AXIS, ROTATE_OUTPUT)),
        SettingCategory("Deadzones", listOf(DEADZONE_SOURCE, DEADZONE_THRESHOLD, DEADZONE_SHAPE)),
        SettingCategory("Outer ring", listOf(COMMAND_RADIUS, COMMAND_INVERT)),
        SettingCategory("Haptics", listOf(HAPTIC_INTENSITY)),
    )

    // ── Joystick Mouse specs (Joysticks → Joystick Mouse) ────────────────────────
    private val MOUSE_SENSITIVITY = SettingSpec(
        key = "mouse_sensitivity",
        label = "Mouse sensitivity",
        helper = "Overall cursor speed at full stick deflection.",
        control = SettingControl.Slider(10f, 10000f, default = 275f, unitSuffix = "%"),
    )

    /** Stick response curve, defaulting to Wide (the Joystick Mouse default per spec). */
    private val MOUSE_STICK_RESPONSE_CURVE = SettingSpec(
        key = "stick_response_curve",
        label = "Stick response curve",
        helper = "How analog input maps to cursor speed.",
        control = SettingControl.Dropdown(RESPONSE_CURVE_OPTIONS, defaultId = "wide"),
    )

    /** Response axis style, defaulting to Per axis (the Joystick Mouse default per spec). */
    private val MOUSE_RESPONSE_AXIS_STYLE = SettingSpec(
        key = "response_axis_style",
        label = "Response axis style",
        helper = "Per axis = a circle traces a square cursor path; circular = a round path.",
        control = SettingControl.Dropdown(RESPONSE_AXIS_OPTIONS, defaultId = "per_axis"),
    )

    // ── Directional Pad specs (Joysticks → Directional Pad) ──────────────────────
    private val DPAD_OVERLAP_REGION = SettingSpec(
        key = "overlap_region",
        label = "Overlap region",
        helper = "Diagonal space that triggers both directions. Lowest ≈ 4-way; highest applies overlap unless precisely on a cardinal.",
        control = SettingControl.Slider(2000f, 16000f, default = 4000f),
    )
    private val DPAD_DEADZONE = SettingSpec(
        key = "deadzone",
        label = "Deadzone",
        helper = "Below this stick deflection, no direction is sent.",
        control = SettingControl.Slider(0f, 32767f, default = 10000f),
    )

    // ── Scroll Wheel specs (Joysticks → Scroll Wheel) ───────────────────────────
    private val SWIPE_DIRECTION_OPTIONS = listOf(
        DropdownOption("horizontal", "Horizontal"),
        DropdownOption("vertical", "Vertical"),
        DropdownOption("circular", "Circular", "Make circular motions to scroll forward/backward through the commands."),
    )
    private val SCROLL_SENSITIVITY = SettingSpec(
        key = "sensitivity",
        label = "Sensitivity",
        helper = "Scroll ticks per full stick motion — higher scrolls faster.",
        control = SettingControl.Slider(1f, 180f, default = 90f),
    )
    private val SCROLL_SPIN_FRICTION = SettingSpec(
        key = "spin_friction",
        label = "Spin friction",
        helper = "Momentum/coast after you stop spinning. (Flywheel coasting is not yet implemented.)",
        control = SettingControl.Dropdown(HAPTIC_INTENSITY_OPTIONS, defaultId = "medium"),
    )
    private val SCROLL_SWIPE_DIRECTION = SettingSpec(
        key = "swipe_direction",
        label = "Swipe direction",
        control = SettingControl.Dropdown(SWIPE_DIRECTION_OPTIONS, defaultId = "circular"),
    )
    private val SCROLL_INVERT_SWIPE = SettingSpec(
        key = "invert_swipe",
        label = "Invert swipe direction",
        helper = "Flips which way you swipe to move 'forward' through the commands.",
        control = SettingControl.Toggle(),
    )
    private val SCROLL_WRAP_LIST = SettingSpec(
        key = "wrap_list",
        label = "Wrap list",
        helper = "Scrolling past an end wraps to the other end. (Applies to command-list bindings; Mappo currently emits raw scroll ticks.)",
        control = SettingControl.Toggle(default = true),
    )
    private val SCROLL_HAPTIC_INTENSITY = SettingSpec(
        key = "haptic_intensity",
        label = "Haptic intensity",
        helper = "Haptic bump strength per scroll tick.",
        control = SettingControl.Dropdown(HAPTIC_INTENSITY_OPTIONS, defaultId = "medium"),
    )

    // ── Flick Stick specs (Joysticks → Flick Stick) ─────────────────────────────
    private val SNAP_ANGLE_OPTIONS = listOf(
        DropdownOption("no_snapping", "No snapping"),
        DropdownOption("180", "180 degrees"),
        DropdownOption("90", "90 degrees"),
        DropdownOption("sixths", "Sixths"),
        DropdownOption("eighths", "Eighths"),
        DropdownOption("forward_only", "Forward only", "Snap to forward only when the flick is roughly forward; other turns stay precise."),
    )
    private val FLICK_OUTPUT_AXIS_OPTIONS = listOf(
        DropdownOption("horizontal", "Horizontal only"),
        DropdownOption("vertical", "Vertical only"),
    )

    private val FLICK_DOTS_PER_360 = SettingSpec(
        key = "dots_per_360",
        label = "Dots per 360",
        helper = "Mouse pixels for one full 360° sweep. Shared with Gyro to Mouse's Dots per 360. Bind \"Turn camera 360\" to calibrate against the game.",
        control = SettingControl.Slider(1f, 32000f, default = 6545f, unitSuffix = " px"),
    )
    private val FLICK_SWEEP_SENSITIVITY = SettingSpec(
        key = "sweep_sensitivity",
        label = "Sweep sensitivity",
        helper = "Multiplier for sweeping the stick around the edge. Tune after Dots per 360.",
        control = SettingControl.Slider(0f, 6f, default = 1f, step = 0.125f, unitSuffix = "x", decimals = 3),
    )
    private val FLICK_ROTATION_OFFSET = SettingSpec(
        key = "rotation_offset",
        label = "Rotation offset",
        helper = "Rotates the flick inputs, if a forward flick consistently lands to one side.",
        control = SettingControl.Slider(-180f, 180f, default = 0f, unitSuffix = "°"),
    )
    private val FLICK_SNAP_ANGLE = SettingSpec(
        key = "snap_angle",
        label = "Snap angle",
        control = SettingControl.Dropdown(SNAP_ANGLE_OPTIONS, defaultId = "forward_only"),
    )
    private val FLICK_FRONT_ANGLE_DEADZONE = SettingSpec(
        key = "front_angle_deadzone",
        label = "Front angle deadzone",
        helper = "No flick turn occurs if the stick points within this angle of forward. Lets you sweep without flicking.",
        control = SettingControl.Slider(0f, 180f, default = 7f, unitSuffix = "°"),
    )
    private val FLICK_TURN_TIGHTNESS = SettingSpec(
        key = "flick_turn_tightness",
        label = "Flick turn tightness",
        helper = "Flick turn smoothing. Higher = quicker turns.",
        control = SettingControl.Slider(0f, 100f, default = 80f, unitSuffix = "%"),
    )
    private val FLICK_SWEEP_TIGHTNESS = SettingSpec(
        key = "sweep_tightness",
        label = "Sweep tightness",
        helper = "Smooths sweep noise. Higher = quicker response; lower = smoother/slower.",
        control = SettingControl.Slider(0f, 100f, default = 70f, unitSuffix = "%"),
    )
    private val FLICK_RELEASE_DAMPENING = SettingSpec(
        key = "release_dampening",
        label = "Release dampening speed",
        helper = "Reduces stray sweep as the stick returns home. Higher = the stick must return faster to dampen.",
        control = SettingControl.Slider(0f, 10f, default = 2.5f, step = 0.25f, unitSuffix = " u/s", decimals = 2),
    )
    private val FLICK_INNER_DEADZONE = SettingSpec(
        key = "inner_deadzone",
        label = "Inner deadzone",
        helper = "Push the stick this far to begin the flick turn. Higher values help compute a more accurate initial angle.",
        control = SettingControl.Slider(0f, 100f, default = 50f, unitSuffix = "%"),
    )
    private val FLICK_OUTER_DEADZONE = SettingSpec(
        key = "outer_deadzone",
        label = "Outer deadzone",
        helper = "Push the stick this far to reach the maximum flick turn rate.",
        control = SettingControl.Slider(0f, 100f, default = 90f, unitSuffix = "%"),
    )
    private val FLICK_OUTPUT_AXIS = SettingSpec(
        key = "output_axis",
        label = "Output axis",
        helper = "Send the flick to horizontal (typical) or vertical mouse movement.",
        control = SettingControl.Dropdown(FLICK_OUTPUT_AXIS_OPTIONS, defaultId = "horizontal"),
    )
    private val FLICK_INVERT_OUTPUT = SettingSpec(
        key = "invert_output",
        label = "Invert output",
        control = SettingControl.Toggle(),
    )
    private val FLICK_ON_AWAKE = SettingSpec(
        key = "flick_on_awake",
        label = "Allow flick on awake",
        helper = "If the stick is already past the inner deadzone when this action set activates, flick immediately. Off = move the stick home and back out first.",
        control = SettingControl.Toggle(),
    )
    private val FLICK_ROTATIONAL_HAPTICS = SettingSpec(
        key = "rotational_haptics",
        label = "Rotational haptics",
        helper = "Haptic bump intensity while sweeping the flick stick.",
        control = SettingControl.Dropdown(HAPTIC_INTENSITY_OPTIONS, defaultId = "medium"),
    )
    private val FLICK_DEGREES_PER_HAPTIC_BUMP = SettingSpec(
        key = "degrees_per_haptic_bump",
        label = "Degrees per haptic bump",
        helper = "Degrees of sweep rotation between rotational haptic bumps.",
        control = SettingControl.Slider(0f, 360f, default = 5f, unitSuffix = "°"),
    )

    /** Output Joystick defaults to the matching stick for an analog joystick source. */
    private fun outputJoystick(source: InputSource) = SettingSpec(
        key = "output_joystick",
        label = "Output joystick",
        helper = "Which gamepad stick this source emits as.",
        control = SettingControl.Dropdown(
            OUTPUT_JOYSTICK_OPTIONS,
            defaultId = if (source == InputSource.LEFT_JOYSTICK) "left" else "right",
        ),
    )

    /**
     * The "Joystick" mode menu for an analog joystick source. Identical to
     * [JOYSTICK_CATEGORIES] except Output Joystick defaults to the matching
     * stick (Left for the left stick, Right for the right) per the spec.
     */
    private fun joystickSourceCategories(source: InputSource) = listOf(
        JOYSTICK_CATEGORIES[0],
        SettingCategory("Output", listOf(outputJoystick(source), OUTPUT_AXIS, ROTATE_OUTPUT)),
        JOYSTICK_CATEGORIES[2],
        JOYSTICK_CATEGORIES[3],
        JOYSTICK_CATEGORIES[4],
    )

    /**
     * The "Joystick Mouse" mode menu (analog stick → cursor). Shares Deadzones /
     * Outer ring / Haptics with the Joystick menu; its General section swaps in a
     * Mouse sensitivity slider and defaults the response curve / axis style to
     * Wide / Per axis. Per the spec the Output section has no Output Joystick
     * entry (the output is the cursor) — only axis-limit + rotate.
     */
    private val JOYSTICK_MOUSE_CATEGORIES = listOf(
        SettingCategory(
            "General",
            listOf(
                MOUSE_SENSITIVITY, HORIZONTAL_SCALE, VERTICAL_SCALE,
                INVERT_HORIZONTAL, INVERT_VERTICAL,
                MOUSE_STICK_RESPONSE_CURVE, CUSTOM_RESPONSE_CURVE, MOUSE_RESPONSE_AXIS_STYLE,
            ),
        ),
        SettingCategory("Output", listOf(OUTPUT_AXIS, ROTATE_OUTPUT)),
        SettingCategory("Deadzones", listOf(DEADZONE_SOURCE, DEADZONE_THRESHOLD, DEADZONE_SHAPE)),
        SettingCategory("Outer ring", listOf(COMMAND_RADIUS, COMMAND_INVERT)),
        SettingCategory("Haptics", listOf(HAPTIC_INTENSITY)),
    )

    /** The "Scroll Wheel" mode menu for an analog joystick source. */
    private val SCROLL_WHEEL_CATEGORIES = listOf(
        SettingCategory(
            "General",
            listOf(
                SCROLL_SENSITIVITY, SCROLL_SPIN_FRICTION, SCROLL_SWIPE_DIRECTION,
                SCROLL_INVERT_SWIPE, SCROLL_WRAP_LIST,
            ),
        ),
        SettingCategory("Haptics", listOf(SCROLL_HAPTIC_INTENSITY)),
    )

    /** The "Directional Pad" mode menu for an analog joystick source. */
    private val JOYSTICK_DPAD_CATEGORIES = listOf(
        SettingCategory("General", listOf(DIRECTIONAL_PAD_LAYOUT, DPAD_OVERLAP_REGION)),
        SettingCategory("Deadzones", listOf(DPAD_DEADZONE)),
        SettingCategory("Outer ring", listOf(COMMAND_RADIUS, COMMAND_INVERT)),
        SettingCategory("Haptics", listOf(HAPTIC_INTENSITY_OVERRIDE)),
    )

    /** The "Flick Stick" mode menu (analog stick → flick-turn + sweep camera). */
    private val FLICK_STICK_CATEGORIES = listOf(
        SettingCategory("Angle calibration", listOf(FLICK_DOTS_PER_360)),
        SettingCategory("Sensitivity", listOf(FLICK_SWEEP_SENSITIVITY, FLICK_ROTATION_OFFSET)),
        SettingCategory("Snapping", listOf(FLICK_SNAP_ANGLE, FLICK_FRONT_ANGLE_DEADZONE)),
        SettingCategory(
            "Smoothing",
            listOf(FLICK_TURN_TIGHTNESS, FLICK_SWEEP_TIGHTNESS, FLICK_RELEASE_DAMPENING),
        ),
        SettingCategory("Deadzones", listOf(FLICK_INNER_DEADZONE, FLICK_OUTER_DEADZONE)),
        SettingCategory("Output", listOf(FLICK_OUTPUT_AXIS, FLICK_INVERT_OUTPUT)),
        SettingCategory("Outer ring", listOf(COMMAND_RADIUS, COMMAND_INVERT)),
        SettingCategory("Action set activation", listOf(FLICK_ON_AWAKE)),
        SettingCategory(
            "Haptics",
            listOf(FLICK_ROTATIONAL_HAPTICS, FLICK_DEGREES_PER_HAPTIC_BUMP),
        ),
    )

    // ── Gyro shared option sets ──────────────────────────────────────────────
    private val GYRO_ACCELERATION_OPTIONS = listOf(
        DropdownOption("off", "Off"),
        DropdownOption("linear", "Linear"),
        DropdownOption("relaxed", "Relaxed"),
        DropdownOption("aggressive", "Aggressive"),
    )

    // Steam-named orientation presets — Title Case is intentional (proper-noun mode
    // names, per the UI-strings convention). Helper text is the authoritative spec.
    private val GYRO_CONVERSION_STYLE_OPTIONS = listOf(
        DropdownOption("yaw", "Yaw", "Turn the device around its vertical axis for horizontal output; tilt up/down for vertical."),
        DropdownOption("roll", "Roll", "Lean the device around its forward axis for horizontal output; tilt up/down for vertical."),
        DropdownOption("yaw_roll", "Yaw + Roll", "Combines lean and turn for horizontal output; tilting up/down still moves vertically."),
        DropdownOption("local_space", "Local Space", "Like Yaw + Roll, but with full control over the principal axis's pitch and the complementary axis's contribution."),
        DropdownOption("player_space", "Player Space", "Yaw + Roll around the gravity axis for horizontal output; Local Pitch for vertical."),
        DropdownOption("world_space", "World Space", "All rotation around the gravity axis for horizontal output; World Pitch for vertical (no vertical move when tilted on its side)."),
        DropdownOption("laser_pointer", "Laser Pointer", "Acts like a laser pointer — great for cursor control of light-gun games on a standalone device."),
    )

    private val GYRO_TRIGGER_DAMPENING_OPTIONS = listOf(
        DropdownOption("off", "Off"),
        DropdownOption("left_soft", "Left trigger soft pull"),
        DropdownOption("right_soft", "Right trigger soft pull"),
        DropdownOption("both_soft", "Both triggers soft pull"),
        DropdownOption("left_soft_full", "Left trigger soft + full pull"),
        DropdownOption("right_soft_full", "Right trigger soft + full pull"),
        DropdownOption("both_soft_full", "Both triggers soft + full pull"),
    )

    private val GYRO_ENABLE_MODE_OPTIONS = listOf(
        DropdownOption("enable", "Hold to enable", "Gyro is active only while a selected button is held."),
        DropdownOption("suppress", "Hold to suppress", "Gyro is active except while a selected button is held."),
        DropdownOption("toggle", "Toggle", "Each press of a selected button toggles the gyro on or off."),
    )

    // ── Shared gyro General specs (reused by every gyro menu) ─────────────────
    private val GYRO_BUTTONS = SettingSpec(
        key = "gyro_buttons",
        label = "Choose gyro button(s)",
        helper = "Selected buttons enable/suppress/toggle gyro output. If none are selected, the gyro is always on.",
        control = SettingControl.InputPicker,
    )
    private val GYRO_ENABLE_MODE = SettingSpec(
        key = "gyro_enable_mode",
        label = "Gyro enable / suppress / toggle",
        control = SettingControl.Dropdown(GYRO_ENABLE_MODE_OPTIONS, defaultId = "enable"),
        visibleWhen = { val r = it.raw("gyro_buttons"); r != null && r != "[]" },
    )
    private val GYRO_GENERAL_CATEGORY = SettingCategory("General", listOf(GYRO_BUTTONS, GYRO_ENABLE_MODE))

    // ── Gyro to Mouse specs ──────────────────────────────────────────────────
    // All rows are wired to the runtime (GyroToMouseSettings + GyroToMouseMode +
    // InputEvaluator gating): Dots Per 360, sensitivity, invert, speed deadzone,
    // precision, mixer, acceleration, conversion styles, rotate output, momentum,
    // trigger dampening, movement threshold, rotational haptics, and the gyro
    // enable-button gating (General). The gravity-relative conversion styles
    // (Local / Player / World / Laser) + acceleration curves are faithful
    // approximations of Steam's unpublished algorithms — verify signs on device.
    private val GYRO_DOTS_PER_360 = SettingSpec(
        key = "dots_per_360",
        label = "Gyro angles to mouse pixels (Dots Per 360)",
        helper = "One full 360° turn of the gyro generates this many mouse pixels at 1× sensitivity. Shared with Flick Stick's Dots Per 360.",
        control = SettingControl.Slider(1f, 32000f, default = 6545f, unitSuffix = " px"),
    )
    private val GYRO_SENSITIVITY = SettingSpec(
        key = "gyro_sensitivity",
        label = "Gyro sensitivity",
        helper = "Multiplies the gyro's output once Dots Per 360 is calibrated in-game. 1× is 1:1 real-world to in-game rotation; 2× turns twice as fast.",
        control = SettingControl.Slider(0f, 30f, default = 2.5f, step = 0.1f, unitSuffix = "x", decimals = 1),
    )
    private val GYRO_INVERT_Y = SettingSpec(
        key = "invert_y",
        label = "Invert Y output",
        control = SettingControl.Toggle(),
    )
    private val GYRO_INVERT_X = SettingSpec(
        key = "invert_x",
        label = "Invert X output",
        control = SettingControl.Toggle(),
    )
    private val GYRO_ACCELERATION = SettingSpec(
        key = "acceleration",
        label = "Acceleration",
        control = SettingControl.Dropdown(GYRO_ACCELERATION_OPTIONS, defaultId = "off"),
    )
    private val GYRO_OUTPUT_MIXER = SettingSpec(
        key = "output_mixer",
        label = "Vertical/horizontal output mixer",
        helper = "Slide left to reduce vertical sensitivity, right to reduce horizontal. At 0% the horizontal-to-vertical ratio is 1:1.",
        control = SettingControl.Slider(-100f, 100f, default = 0f, unitSuffix = "%"),
    )
    private val GYRO_SPEED_DEADZONE = SettingSpec(
        key = "gyro_speed_deadzone",
        label = "Gyro speed deadzone",
        helper = "Minimum gyro speed before there's a reaction on screen. Mitigates hand shake and flick bounce-back. Output lost to the deadzone is recovered at high speeds.",
        control = SettingControl.Slider(0f, 1f, default = 0.36f, step = 0.01f, unitSuffix = " °/s", decimals = 2),
    )
    private val GYRO_PRECISION_SPEED = SettingSpec(
        key = "gyro_precision_speed",
        label = "Gyro precision speed",
        helper = "Below this speed, sensitivity is reduced relative to speed so small motions are even smaller in-game. May be preferable to a deadzone for adept users.",
        control = SettingControl.Slider(0f, 15f, default = 0.75f, step = 0.01f, unitSuffix = " °/s", decimals = 2),
    )
    private val GYRO_ENABLE_MOMENTUM = SettingSpec(
        key = "enable_momentum",
        label = "Enable momentum",
        helper = "When the gyro is disabled by its enable button, it keeps outputting briefly. Friction controls how quickly it slows down.",
        control = SettingControl.Toggle(),
    )
    private val GYRO_H_MOMENTUM_FRICTION = SettingSpec(
        key = "h_momentum_friction",
        label = "Horizontal momentum friction",
        helper = "How quickly horizontal gyro momentum decays.",
        control = SettingControl.Slider(0f, 720f, default = 100f, unitSuffix = " °/s"),
        visibleWhen = { it.raw("enable_momentum") == "true" },
    )
    private val GYRO_V_MOMENTUM_FRICTION = SettingSpec(
        key = "v_momentum_friction",
        label = "Vertical momentum friction",
        helper = "How quickly vertical gyro momentum decays. Set very high to make momentum turn only horizontally.",
        control = SettingControl.Slider(0f, 720f, default = 200f, unitSuffix = " °/s"),
        visibleWhen = { it.raw("enable_momentum") == "true" },
    )
    private val GYRO_CONVERSION_STYLE = SettingSpec(
        key = "conversion_style",
        label = "3DOF to 2D conversion style",
        control = SettingControl.Dropdown(GYRO_CONVERSION_STYLE_OPTIONS, defaultId = "yaw_roll"),
    )
    private val GYRO_ROLL_CONTRIBUTION = SettingSpec(
        key = "roll_contribution",
        label = "Roll axis contribution",
        helper = "How much turning the device like a steering wheel (Roll) adds to horizontal output. Negative values invert the contribution.",
        control = SettingControl.Slider(-100f, 100f, default = 100f, unitSuffix = "%"),
        visibleWhen = { it.raw("conversion_style") == "yaw_roll" },
    )
    private val GYRO_PRIMARY_AXIS_OFFSET = SettingSpec(
        key = "primary_axis_offset",
        label = "Primary axis offset",
        helper = "Tilt the primary (Yaw) axis. 0° samples in front of the device (= Yaw); 90° samples from above (= Roll). 0–90° covers most needs.",
        control = SettingControl.Slider(-180f, 180f, default = 0f, unitSuffix = "°"),
        visibleWhen = { it.raw("conversion_style") == "local_space" },
    )
    private val GYRO_COMPLEMENTARY_AXIS = SettingSpec(
        key = "complementary_axis_contribution",
        label = "Complementary axis contribution (Roll)",
        helper = "Tune the complementary axis's contribution to horizontal output — the axis perpendicular to your tilted primary axis.",
        control = SettingControl.Slider(-100f, 100f, default = 100f, unitSuffix = "%"),
        visibleWhen = { it.raw("conversion_style") == "local_space" },
    )
    private val GYRO_ROTATE_OUTPUT = SettingSpec(
        key = "rotate_output",
        label = "Rotate output",
        helper = "Adjust the gyro's 2D output clockwise or counterclockwise.",
        control = SettingControl.Slider(-180f, 180f, default = 0f, unitSuffix = "°"),
    )
    private val GYRO_TRIGGER_DAMPENING = SettingSpec(
        key = "trigger_dampening",
        label = "Trigger press mouse dampening",
        helper = "Dampen mouse movement while a trigger is pulled, to correct for accidental movement when firing.",
        control = SettingControl.Dropdown(GYRO_TRIGGER_DAMPENING_OPTIONS, defaultId = "off"),
    )
    private val GYRO_TRIGGER_DAMPENING_AMOUNT = SettingSpec(
        key = "trigger_dampening_amount",
        label = "Trigger dampening amount",
        helper = "How much the trigger dampens mouse movement. Higher values suppress more.",
        control = SettingControl.Slider(0f, 100f, default = 90f),
        visibleWhen = { it.raw("trigger_dampening") != "off" },
    )
    private val GYRO_MOVEMENT_THRESHOLD = SettingSpec(
        key = "movement_threshold",
        label = "Movement threshold",
        helper = "Batches small movements and only sends them once large enough, for games that filter tiny mouse moves. Keep as small as possible while preserving movement.",
        control = SettingControl.Slider(0f, 40f, default = 0f),
    )
    private val GYRO_ROTATIONAL_HAPTICS = SettingSpec(
        key = "rotational_haptics",
        label = "Rotational haptics",
        helper = "How intense the haptic bumps are when rotating the gyro.",
        control = SettingControl.Dropdown(HAPTIC_INTENSITY_OPTIONS, defaultId = "off"),
    )

    /** The "Gyro to Mouse" menu (device rotation → mouse cursor). */
    private val GYRO_TO_MOUSE_CATEGORIES = listOf(
        GYRO_GENERAL_CATEGORY,
        SettingCategory("Angle calibration", listOf(GYRO_DOTS_PER_360)),
        SettingCategory(
            "Sensitivity",
            listOf(
                GYRO_SENSITIVITY, GYRO_INVERT_Y, GYRO_INVERT_X, GYRO_ACCELERATION,
                GYRO_OUTPUT_MIXER, GYRO_SPEED_DEADZONE, GYRO_PRECISION_SPEED,
            ),
        ),
        SettingCategory(
            "Momentum",
            listOf(GYRO_ENABLE_MOMENTUM, GYRO_H_MOMENTUM_FRICTION, GYRO_V_MOMENTUM_FRICTION),
        ),
        SettingCategory(
            "Gyro orientation",
            listOf(
                GYRO_CONVERSION_STYLE, GYRO_ROLL_CONTRIBUTION, GYRO_PRIMARY_AXIS_OFFSET,
                GYRO_COMPLEMENTARY_AXIS, GYRO_ROTATE_OUTPUT,
            ),
        ),
        SettingCategory(
            "Trigger dampening",
            listOf(GYRO_TRIGGER_DAMPENING, GYRO_TRIGGER_DAMPENING_AMOUNT),
        ),
        SettingCategory("Mouse output", listOf(GYRO_MOVEMENT_THRESHOLD)),
        SettingCategory("Haptics", listOf(GYRO_ROTATIONAL_HAPTICS)),
    )

    // ── Gyro Directional Pad specs ───────────────────────────────────────────
    private val GYRO_PITCH_NEUTRAL = SettingSpec(
        key = "gyro_pitch_neutral",
        label = "Gyro pitch neutral angle",
        helper = "The default centered position. Shift it to tilt the neutral pose forward or backward for comfort. (For steering-wheel-style use, disable the vertical axis and ignore this.)",
        control = SettingControl.Slider(0f, 32767f, default = 16384f),
    )
    private val GYRO_LOCK_AT_EDGES = SettingSpec(
        key = "gyro_lock_at_edges",
        label = "Gyro lock at edges",
        helper = "When on, rotating past the outer edge locks to that edge. When off, input may wind up past the edge. Disable if the device locks up unexpectedly.",
        control = SettingControl.Toggle(default = true),
    )

    // ── Gyro to Joystick Camera specs ────────────────────────────────────────
    private val GYRO_SEND_TO_JOYSTICK_OPTIONS = listOf(
        DropdownOption("left", "Left joystick"),
        DropdownOption("right", "Right joystick"),
    )
    private val GYRO_ACCEL_NO_OFF_OPTIONS = listOf(
        DropdownOption("linear", "Linear"),
        DropdownOption("relaxed", "Relaxed"),
        DropdownOption("aggressive", "Aggressive"),
    )
    private val GYRO_ANGLE_CATCHUP_OPTIONS = listOf(
        DropdownOption("off", "Off", "Don't catch up to the desired angle after large gyro flicks saturate output."),
        DropdownOption("only_while_active", "Only while gyro active", "Catch up to the desired angle, but only while the gyro is active."),
        DropdownOption("always", "Always", "Always catch up — keep serving remaining catch-up angles even when the gyro is inactive."),
    )
    private val GYRO_SEND_TO_JOYSTICK = SettingSpec(
        key = "send_to_joystick",
        label = "Send to joystick",
        helper = "Which joystick the gyro camera drives.",
        control = SettingControl.Dropdown(GYRO_SEND_TO_JOYSTICK_OPTIONS, defaultId = "right"),
    )
    private val GYRO_MIN_INPUT_SPEED = SettingSpec(
        key = "min_input_speed",
        label = "Minimum gyro input speed",
        control = SettingControl.Slider(0f, 1800f, default = 5f, unitSuffix = " °/s"),
    )
    private val GYRO_MAX_INPUT_SPEED = SettingSpec(
        key = "max_input_speed",
        label = "Maximum gyro input speed",
        helper = "The in-game camera turn rate (°/s) at maximum joystick deflection. Set in-game aim sensitivity high; if it clamps, use Angle Catch-Up.",
        control = SettingControl.Slider(0f, 1800f, default = 180f, unitSuffix = " °/s"),
    )
    private val GYRO_MIN_OUTPUT = SettingSpec(
        key = "min_output",
        label = "Minimum joystick output",
        helper = "Minimum gyro input speed maps to this output. Try to match the game's joystick deadzone.",
        control = SettingControl.Slider(0f, 100f, default = 0f, unitSuffix = "%"),
    )
    private val GYRO_MAX_OUTPUT = SettingSpec(
        key = "max_output",
        label = "Maximum joystick output",
        helper = "Maximum gyro input speed maps to this output. Reduce it to avoid triggering a game's extra-yaw setting.",
        control = SettingControl.Slider(0f, 100f, default = 100f, unitSuffix = "%"),
    )
    private val GYRO_RESPONSE_AXIS = SettingSpec(
        key = "response_axis_style",
        label = "Response axis style",
        control = SettingControl.Dropdown(RESPONSE_AXIS_OPTIONS, defaultId = "circular"),
    )
    private val GYRO_POWER_CURVE = SettingSpec(
        key = "joystick_power_curve",
        label = "Joystick power curve",
        helper = "How aggressively output deflects: 0.1 = extremely aggressive, 1 = linear, 4 = extremely relaxed.",
        control = SettingControl.Slider(0.1f, 4f, default = 1f, step = 0.1f, decimals = 1),
    )
    private val GYRO_LOCK_AT_EDGES_SWITCH = SettingSpec(
        key = "lock_at_edges",
        label = "Lock at edges",
        helper = "Locks output to the maximum deflection angle. Off allows the full output range into the diagonals.",
        control = SettingControl.Toggle(),
    )
    private val GYRO_ANGLE_CATCHUP = SettingSpec(
        key = "angle_catch_up",
        label = "Angle catch-up mode",
        helper = "When demanded rotation exceeds the in-game max turn rate, store the remaining angle to catch up. Can feel like the stick is stuck at its extreme after fast flicks.",
        control = SettingControl.Dropdown(GYRO_ANGLE_CATCHUP_OPTIONS, defaultId = "off"),
    )
    private val GYRO_DEGREE_SENSITIVITY = SettingSpec(
        key = "gyro_degree_sensitivity",
        label = "Gyro degree sensitivity",
        control = SettingControl.Slider(0f, 30f, default = 2.5f, step = 0.1f, unitSuffix = "x", decimals = 1),
    )
    private val GYRO_SPEED_DEADZONE_ZERO = SettingSpec(
        key = "gyro_speed_deadzone",
        label = "Gyro speed deadzone",
        helper = "Minimum gyro speed before there's a reaction on screen. Mitigates hand shake and flick bounce-back.",
        control = SettingControl.Slider(0f, 1f, default = 0f, step = 0.01f, unitSuffix = " °/s", decimals = 2),
    )
    private val GYRO_PRECISION_SPEED_ZERO = SettingSpec(
        key = "gyro_precision_speed",
        label = "Gyro precision speed",
        helper = "Below this speed, sensitivity is reduced so small motions are even smaller in-game.",
        control = SettingControl.Slider(0f, 15f, default = 0f, step = 0.01f, unitSuffix = " °/s", decimals = 2),
    )
    private val GYRO_ACCELERATION_NO_OFF = SettingSpec(
        key = "acceleration",
        label = "Acceleration",
        control = SettingControl.Dropdown(GYRO_ACCEL_NO_OFF_OPTIONS, defaultId = "linear"),
    )

    /** The "Gyro to Joystick Camera" menu (device rotation rate → camera stick). */
    private val GYRO_CAMERA_CATEGORIES = listOf(
        GYRO_GENERAL_CATEGORY,
        SettingCategory(
            "Joystick output",
            listOf(
                GYRO_SEND_TO_JOYSTICK, GYRO_MIN_INPUT_SPEED, GYRO_MAX_INPUT_SPEED,
                GYRO_MIN_OUTPUT, GYRO_MAX_OUTPUT, GYRO_RESPONSE_AXIS, GYRO_POWER_CURVE,
                GYRO_LOCK_AT_EDGES_SWITCH,
            ),
        ),
        SettingCategory("Angle calibration", listOf(GYRO_ANGLE_CATCHUP)),
        SettingCategory(
            "Gyro sensitivity",
            listOf(
                GYRO_DEGREE_SENSITIVITY, GYRO_INVERT_Y, GYRO_INVERT_X, GYRO_SPEED_DEADZONE_ZERO,
                GYRO_PRECISION_SPEED_ZERO, GYRO_OUTPUT_MIXER, GYRO_ACCELERATION_NO_OFF, GYRO_ENABLE_MOMENTUM,
            ),
        ),
        SettingCategory(
            "Gyro orientation",
            listOf(GYRO_CONVERSION_STYLE, GYRO_ROLL_CONTRIBUTION, GYRO_PRIMARY_AXIS_OFFSET, GYRO_COMPLEMENTARY_AXIS),
        ),
        SettingCategory("Trigger dampening", listOf(GYRO_TRIGGER_DAMPENING, GYRO_TRIGGER_DAMPENING_AMOUNT)),
        SettingCategory("Haptics", listOf(GYRO_ROTATIONAL_HAPTICS)),
    )

    // ── Gyro to Joystick Deflection specs ────────────────────────────────────
    private val GYRO_SEND_TO_JOYSTICK_LEFT_DEFAULT = SettingSpec(
        key = "send_to_joystick",
        label = "Send to joystick",
        helper = "Which joystick the gyro deflection drives.",
        control = SettingControl.Dropdown(GYRO_SEND_TO_JOYSTICK_OPTIONS, defaultId = "left"),
    )
    private val GYRO_USE_RELATIVE_ROLL = SettingSpec(
        key = "use_relative_roll",
        label = "Use relative roll",
        helper = "On: horizontal output is the roll relative to the device's starting pose. Off: relative to Earth's horizon.",
        control = SettingControl.Toggle(default = true),
    )
    private val GYRO_USE_RELATIVE_PITCH = SettingSpec(
        key = "use_relative_pitch",
        label = "Use relative pitch",
        helper = "On: vertical output is the pitch relative to the device's starting pose. Off: relative to Earth's horizon.",
        control = SettingControl.Toggle(default = true),
    )
    private val GYRO_MIN_DEFLECTION_ANGLE = SettingSpec(
        key = "min_deflection_angle",
        label = "Minimum gyro deflection angle",
        control = SettingControl.Slider(0f, 180f, default = 2f, unitSuffix = "°"),
    )
    private val GYRO_MAX_DEFLECTION_ANGLE = SettingSpec(
        key = "max_deflection_angle",
        label = "Maximum gyro deflection angle",
        helper = "The real-world tilt angles (from the gyro's starting orientation) that map to the joystick. Use the gyro activation button to reset orientation.",
        control = SettingControl.Slider(0f, 180f, default = 45f, unitSuffix = "°"),
    )
    private val GYRO_DRAG_CENTER_POINT = SettingSpec(
        key = "drag_center_point",
        label = "Drag center point",
        helper = "When exceeding the max deflection angle, drag the gyro reference with the device — more responsive moving back from an extreme, but less consistent returning to center.",
        control = SettingControl.Toggle(default = true),
    )

    /** The "Gyro to Joystick Deflection" menu (device tilt → movement stick). */
    private val GYRO_DEFLECTION_CATEGORIES = listOf(
        GYRO_GENERAL_CATEGORY,
        SettingCategory(
            "Gyro orientation",
            listOf(
                GYRO_USE_RELATIVE_ROLL, GYRO_USE_RELATIVE_PITCH, GYRO_SEND_TO_JOYSTICK_LEFT_DEFAULT,
                GYRO_MIN_DEFLECTION_ANGLE, GYRO_MAX_DEFLECTION_ANGLE, GYRO_MIN_OUTPUT, GYRO_MAX_OUTPUT,
                GYRO_POWER_CURVE, GYRO_RESPONSE_AXIS, GYRO_LOCK_AT_EDGES_SWITCH, GYRO_DRAG_CENTER_POINT,
                GYRO_INVERT_Y, GYRO_INVERT_X, GYRO_SPEED_DEADZONE_ZERO, GYRO_PRECISION_SPEED_ZERO,
                GYRO_OUTPUT_MIXER,
            ),
        ),
        SettingCategory("Output", listOf(GYRO_ROTATE_OUTPUT)),
        SettingCategory("Haptics", listOf(GYRO_ROTATIONAL_HAPTICS)),
    )

    // ── Gyro Directional Swipe specs ─────────────────────────────────────────
    private val SWIPE_SCROLL_MODE_OPTIONS = listOf(
        DropdownOption("off", "Off", "Each swipe fires once and resets before another can fire."),
        DropdownOption("both", "Both horizontal & vertical"),
        DropdownOption("horizontal", "Horizontal only"),
        DropdownOption("vertical", "Vertical only"),
    )
    private val SWIPE_SCROLL_FRICTION_OPTIONS = listOf(
        DropdownOption("off", "Off"),
        DropdownOption("none", "None"),
        DropdownOption("low", "Low"),
        DropdownOption("medium", "Medium"),
        DropdownOption("high", "High"),
    )
    private val GYRO_STEERING_AXIS_OPTIONS = listOf(
        DropdownOption("yaw", "Yaw"),
        DropdownOption("roll", "Roll"),
    )
    private val SWIPE_SENSITIVITY = SettingSpec(
        key = "sensitivity",
        label = "Sensitivity",
        control = SettingControl.Slider(1f, 3000f, default = 100f, unitSuffix = "%"),
    )
    private val SWIPE_SCROLL_MODE = SettingSpec(
        key = "scroll_wheel_mode",
        label = "Scroll wheel mode",
        helper = "When on, a swipe has momentum and fires multiple times. When off, a swipe fires once and resets.",
        control = SettingControl.Dropdown(SWIPE_SCROLL_MODE_OPTIONS, defaultId = "off"),
    )
    private val SWIPE_SCROLL_FRICTION = SettingSpec(
        key = "scroll_wheel_friction",
        label = "Scroll wheel friction",
        helper = "How quickly the swipe momentum slows down.",
        control = SettingControl.Dropdown(SWIPE_SCROLL_FRICTION_OPTIONS, defaultId = "medium"),
        visibleWhen = { it.raw("scroll_wheel_mode")?.let { v -> v != "off" } ?: false },
    )
    private val SWIPE_SMOOTHING = SettingSpec(
        key = "smoothing",
        label = "Smoothing",
        control = SettingControl.Slider(0f, 40f, default = 20f),
    )
    private val SWIPE_ROTATION = SettingSpec(
        key = "rotation",
        label = "Rotation",
        helper = "Rotate the horizon line of swipe movement to better align with your thumb's natural swiping angle.",
        control = SettingControl.Slider(-180f, 180f, default = 0f, unitSuffix = "°"),
    )
    private val GYRO_STEERING_AXIS = SettingSpec(
        key = "gyro_steering_axis",
        label = "Gyro steering axis",
        helper = "Limits the horizontal output to either the yaw or the roll of the device.",
        control = SettingControl.Dropdown(GYRO_STEERING_AXIS_OPTIONS, defaultId = "yaw"),
    )

    /** The "Directional Swipe" menu for the gyro source (flick → directional taps). */
    private val GYRO_SWIPE_CATEGORIES = listOf(
        SettingCategory("General", listOf(SWIPE_SENSITIVITY, SWIPE_SCROLL_MODE, SWIPE_SCROLL_FRICTION)),
        SettingCategory("Output", listOf(SWIPE_SMOOTHING, SWIPE_ROTATION)),
        SettingCategory("Gyro", listOf(GYRO_BUTTONS, GYRO_ENABLE_MODE, GYRO_STEERING_AXIS)),
    )

    /** The "Directional Pad" menu for the gyro source (tilt → directional pad). */
    private val GYRO_DPAD_CATEGORIES = listOf(
        SettingCategory("General", listOf(DIRECTIONAL_PAD_LAYOUT, DPAD_OVERLAP_REGION)),
        GYRO_GENERAL_CATEGORY,
        SettingCategory("Deadzones", listOf(DPAD_DEADZONE)),
        SettingCategory("Outer ring", listOf(COMMAND_RADIUS, COMMAND_INVERT)),
        SettingCategory("Gyro", listOf(GYRO_PITCH_NEUTRAL, GYRO_LOCK_AT_EDGES)),
        SettingCategory("Haptics", listOf(GYRO_ROTATIONAL_HAPTICS)),
    )

    /**
     * The settings menu for a given (source, mode). Empty list = no cog shown.
     * Filled in menu-by-menu as each slice lands.
     */
    fun categoriesFor(source: InputSource, mode: BindingMode): List<SettingCategory> = when {
        // Digital direction clusters — the face-button pad and the D-Pad share the same
        // menus per the settings spec ("DPad - <mode> = same as Button Pad - <mode>").
        source == InputSource.BUTTON_DIAMOND || source == InputSource.DPAD -> when (mode) {
            BindingMode.BUTTON_PAD -> BUTTON_PAD_CATEGORIES
            BindingMode.JOYSTICK_MOVE -> JOYSTICK_CATEGORIES
            BindingMode.DPAD -> DIGITAL_DIRECTIONAL_PAD_CATEGORIES
            else -> emptyList()
        }

        // Analog triggers in Trigger mode (one menu each for L/R; source-aware default
        // on Analog Output Trigger).
        (source == InputSource.LEFT_TRIGGER || source == InputSource.RIGHT_TRIGGER) &&
            mode == BindingMode.TRIGGER -> triggerCategories(source)

        // Analog joysticks in Joystick (Joystick Move) mode (one menu each for L/R;
        // source-aware default on Output Joystick — the matching stick).
        (source == InputSource.LEFT_JOYSTICK || source == InputSource.RIGHT_JOYSTICK) &&
            mode == BindingMode.JOYSTICK_MOVE -> joystickSourceCategories(source)

        // Analog joysticks in Joystick Mouse mode (stick → cursor).
        (source == InputSource.LEFT_JOYSTICK || source == InputSource.RIGHT_JOYSTICK) &&
            mode == BindingMode.JOYSTICK_MOUSE -> JOYSTICK_MOUSE_CATEGORIES

        // Analog joysticks in Flick Stick mode.
        (source == InputSource.LEFT_JOYSTICK || source == InputSource.RIGHT_JOYSTICK) &&
            mode == BindingMode.FLICK_STICK -> FLICK_STICK_CATEGORIES

        // Analog joysticks in Directional Pad mode.
        (source == InputSource.LEFT_JOYSTICK || source == InputSource.RIGHT_JOYSTICK) &&
            mode == BindingMode.DPAD -> JOYSTICK_DPAD_CATEGORIES

        // Analog joysticks in Scroll Wheel mode.
        (source == InputSource.LEFT_JOYSTICK || source == InputSource.RIGHT_JOYSTICK) &&
            mode == BindingMode.SCROLL_WHEEL -> SCROLL_WHEEL_CATEGORIES

        // Gyro source — built menu-by-menu.
        source == InputSource.GYRO -> when (mode) {
            BindingMode.GYRO_TO_MOUSE -> GYRO_TO_MOUSE_CATEGORIES
            BindingMode.GYRO_TO_JOYSTICK_CAMERA -> GYRO_CAMERA_CATEGORIES
            BindingMode.GYRO_TO_JOYSTICK_DEFLECTION -> GYRO_DEFLECTION_CATEGORIES
            BindingMode.DIRECTIONAL_SWIPE -> GYRO_SWIPE_CATEGORIES
            BindingMode.DPAD -> GYRO_DPAD_CATEGORIES
            else -> emptyList()
        }

        else -> emptyList()
    }

    /** True when (source, mode) has at least one setting — drives cog visibility. */
    fun hasSettings(source: InputSource, mode: BindingMode): Boolean =
        categoriesFor(source, mode).isNotEmpty()
}
