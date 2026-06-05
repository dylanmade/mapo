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

    /**
     * The settings menu for a given (source, mode). Empty list = no cog shown.
     * Filled in menu-by-menu as each slice lands.
     */
    fun categoriesFor(source: InputSource, mode: BindingMode): List<SettingCategory> = when {
        // Button Pad (face-button cluster) in Button Pad mode.
        source == InputSource.BUTTON_DIAMOND && mode == BindingMode.BUTTON_PAD -> listOf(
            SettingCategory("Haptics", listOf(HAPTIC_INTENSITY_OVERRIDE)),
        )

        // Button Pad (face-button cluster) emulating a joystick.
        source == InputSource.BUTTON_DIAMOND && mode == BindingMode.JOYSTICK_MOVE -> JOYSTICK_CATEGORIES

        else -> emptyList()
    }

    /** True when (source, mode) has at least one setting — drives cog visibility. */
    fun hasSettings(source: InputSource, mode: BindingMode): Boolean =
        categoriesFor(source, mode).isNotEmpty()
}
