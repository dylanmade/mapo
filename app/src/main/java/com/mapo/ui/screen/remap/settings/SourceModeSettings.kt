package com.mapo.ui.screen.remap.settings

import com.mapo.data.model.steam.BindingMode
import com.mapo.data.model.steam.InputSource

/**
 * Declarative schema for the per-(input source x mode) settings exposed behind the
 * settings cog on each source row in Remap Controls. One shared renderer
 * ([com.mapo.ui.screen.remap.settings.ModeSettingsSheet]) draws every spec
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
                MOUSE_STICK_RESPONSE_CURVE, CUSTOM_RESPONSE_CURVE, MOUSE_RESPONSE_AXIS_STYLE,
            ),
        ),
        SettingCategory("Output", listOf(OUTPUT_AXIS, ROTATE_OUTPUT)),
        SettingCategory("Deadzones", listOf(DEADZONE_SOURCE, DEADZONE_THRESHOLD, DEADZONE_SHAPE)),
        SettingCategory("Outer ring", listOf(COMMAND_RADIUS, COMMAND_INVERT)),
        SettingCategory("Haptics", listOf(HAPTIC_INTENSITY)),
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

        else -> emptyList()
    }

    /** True when (source, mode) has at least one setting — drives cog visibility. */
    fun hasSettings(source: InputSource, mode: BindingMode): Boolean =
        categoriesFor(source, mode).isNotEmpty()
}
