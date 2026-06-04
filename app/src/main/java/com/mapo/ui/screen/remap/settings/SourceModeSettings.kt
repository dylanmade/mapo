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

    /**
     * The settings menu for a given (source, mode). Empty list = no cog shown.
     * Filled in menu-by-menu as each slice lands.
     */
    fun categoriesFor(source: InputSource, mode: BindingMode): List<SettingCategory> = when {
        // Button Pad (face-button cluster) in Button Pad mode.
        source == InputSource.BUTTON_DIAMOND && mode == BindingMode.BUTTON_PAD -> listOf(
            SettingCategory("Haptics", listOf(HAPTIC_INTENSITY_OVERRIDE)),
        )

        else -> emptyList()
    }

    /** True when (source, mode) has at least one setting — drives cog visibility. */
    fun hasSettings(source: InputSource, mode: BindingMode): Boolean =
        categoriesFor(source, mode).isNotEmpty()
}
