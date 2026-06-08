package com.mapo.ui.nav

import android.net.Uri

/**
 * Top-level navigation destinations. Reached from the profile drawer (or the keyboard grid
 * for the per-button configure flow). Each destination owns its own Scaffold + TopAppBar;
 * the drawer wraps the NavHost so it stays available across destinations.
 *
 * String routes here rather than Navigation Compose 2.8 type-safe @Serializable routes
 * because the project doesn't pull in kotlinx-serialization. Switching later is a small
 * refactor if/when serialization arrives for another reason.
 */
object MapoRoute {
    const val MAIN = "main"
    const val CHANGE_PROFILE = "change_profile"
    const val REMAP_CONTROLS = "remap_controls"
    const val AUTO_SWITCH = "auto_switch"
    const val BLOCKLIST = "blocklist"
    const val THEME_STUDIO = "theme_studio"
    const val SHIZUKU_SETUP = "shizuku_setup"
    const val STEAM_SETUP = "steam_setup"
    const val STEAM_BROWSE = "steam_browse"

    // ── Steam config detail screen ─────────────────────────────────────────────
    //
    // Shows every PublishedFileDetails field for a single Workshop config.
    // Reached by tapping a row in SteamBrowseScreen's Configs state. The
    // publishedFileId arg is the Workshop item ID — the detail VM looks up
    // the full WorkshopConfig from the workshop repo's in-memory cache.
    const val ARG_PUBLISHED_FILE_ID = "publishedFileId"
    const val STEAM_CONFIG_DETAIL = "steam_config_detail/{$ARG_PUBLISHED_FILE_ID}"
    fun steamConfigDetail(publishedFileId: Long): String =
        "steam_config_detail/$publishedFileId"

    // ── Remap-target picker (full-screen, multi-step: category → filtered list) ────
    //
    // Caller navigates with title + currently-selected target (encoded). On selection the
    // picker writes the chosen target's encoded form to `previousBackStackEntry.savedStateHandle`
    // under [PICKER_RESULT_KEY] and pops itself. Caller observes its own savedStateHandle on
    // recomposition and applies the result to whatever it's editing.
    const val ARG_TITLE = "title"
    const val ARG_CURRENT = "current"
    /**
     * Brick 4.5: when true, the picker shows the **Switch Action Set** category populated
     * from the active controller_profile. InputEditor sets true; legacy trackpad-gesture
     * config (ConfigureButton) sets false because its outputs run through a different
     * pipeline that can't honor a runtime set switch.
     */
    const val ARG_SHOW_ACTION_SETS = "showActionSets"
    /**
     * Brick 5.6: when true, the picker also shows the **Layer** category — populated
     * from the active controller_profile's viewing-set layers. Same call-site rule as
     * showActionSets (InputEditor true; ConfigureButton false).
     */
    const val ARG_SHOW_LAYERS = "showLayers"
    const val PICKER_RESULT_KEY = "remap_target_picker_result"
    const val REMAP_TARGET_PICKER =
        "remap_target_picker?$ARG_TITLE={$ARG_TITLE}&$ARG_CURRENT={$ARG_CURRENT}&$ARG_SHOW_ACTION_SETS={$ARG_SHOW_ACTION_SETS}&$ARG_SHOW_LAYERS={$ARG_SHOW_LAYERS}"
    fun remapTargetPicker(
        title: String,
        currentEncoded: String,
        showActionSets: Boolean = false,
        showLayers: Boolean = false,
    ): String =
        "remap_target_picker?$ARG_TITLE=${Uri.encode(title)}" +
            "&$ARG_CURRENT=${Uri.encode(currentEncoded)}" +
            "&$ARG_SHOW_ACTION_SETS=$showActionSets" +
            "&$ARG_SHOW_LAYERS=$showLayers"

    // ── Per-activator settings editor (full-screen, instant-commit) ───────────────
    //
    // Reached by tapping the cog (⚙) on any activator row in `InputEditorScreen`. Shows the
    // settings panel for that single activator — type-specific (long_press_time slider,
    // double_tap_time slider) plus universal placeholders for the 3.3 settings. Slider
    // drag-end commits to the repo; back navigates without an explicit save.
    const val ARG_ACTIVATOR_ID = "activatorId"
    const val ARG_ACTIVATOR_LABEL = "activatorLabel"
    const val ACTIVATOR_EDITOR =
        "activator_editor/{$ARG_ACTIVATOR_ID}?$ARG_ACTIVATOR_LABEL={$ARG_ACTIVATOR_LABEL}"
    fun activatorEditor(activatorId: Long, label: String): String =
        "activator_editor/$activatorId?$ARG_ACTIVATOR_LABEL=${Uri.encode(label)}"

    // ── Per-input activator editor (full-screen, instant-commit) ───────────────────
    //
    // Reached by tapping a row in `RemapControlsScreen`. Shows the activator list for one
    // (inputSource, groupInputKey) pair: each activator gets a binding picker, a type
    // dropdown, and a settings cog. Adds / removes activators on this input. Picker is
    // invoked from within the editor — its result lands via the standard PICKER_RESULT_KEY
    // savedStateHandle pattern, scoped to this destination's back-stack entry.
    const val ARG_INPUT_SOURCE = "inputSource"
    const val ARG_GROUP_INPUT_KEY = "groupInputKey"
    const val ARG_INPUT_LABEL = "inputLabel"
    /**
     * Phase 7 Brick B.6: optional mode-shift id. When non-zero, the editor
     * resolves the binding group through the shift's target group instead of
     * the source's preset entry. `0` is the sentinel for "no mode shift —
     * regular source binding path."
     */
    const val ARG_MODE_SHIFT_ID = "modeShiftId"
    const val INPUT_EDITOR =
        "input_editor/{$ARG_INPUT_SOURCE}/{$ARG_GROUP_INPUT_KEY}?$ARG_INPUT_LABEL={$ARG_INPUT_LABEL}&$ARG_MODE_SHIFT_ID={$ARG_MODE_SHIFT_ID}"
    fun inputEditor(
        inputSource: String,
        groupInputKey: String,
        label: String,
        modeShiftId: Long = 0L,
    ): String =
        "input_editor/${Uri.encode(inputSource)}/${Uri.encode(groupInputKey)}" +
            "?$ARG_INPUT_LABEL=${Uri.encode(label)}&$ARG_MODE_SHIFT_ID=$modeShiftId"

    // ── Per-(source x mode) settings editor (full-screen, instant-commit) ─────────
    //
    // Reached by tapping the settings cog (⚙) next to a source's mode dropdown in
    // `RemapControlsScreen`. Shows the schema-driven settings menu for that
    // (inputSource, mode) — see `SourceModeSettingsSchema`. The screen reads the
    // binding group live from config (resolved by source off the viewed action set)
    // and commits each change straight to the repo; back navigates without a save.
    const val ARG_BINDING_GROUP_ID = "bindingGroupId"
    const val MODE_SETTINGS = "mode_settings/{$ARG_BINDING_GROUP_ID}/{$ARG_INPUT_SOURCE}"
    fun modeSettings(bindingGroupId: Long, inputSource: String): String =
        "mode_settings/$bindingGroupId/${Uri.encode(inputSource)}"

    // ── Chord partner picker (full-screen, listen-for-press) ──────────────────────
    //
    // Reached from `ActivatorEditorScreen` when the activator type is CHORDED_PRESS. While
    // the screen is on top, the accessibility service is in capture mode — the next
    // physical button DOWN is captured and returned via savedStateHandle under
    // [CHORD_PARTNER_RESULT_KEY] as "<InputSource>|<inputKey>". Caller writes through to
    // the activator's chord_partner_source / chord_partner_key settings.
    const val CHORD_PARTNER_RESULT_KEY = "chord_partner_picker_result"
    const val CHORD_PARTNER_PICKER = "chord_partner_picker/{$ARG_ACTIVATOR_ID}"
    fun chordPartnerPicker(activatorId: Long): String = "chord_partner_picker/$activatorId"

    // ── Configure-button screen (full-screen, instant-commit) ─────────────────────
    //
    // Caller navigates with the buttonId of the button being edited. The screen reads the
    // button live from the ViewModel and dispatches each edit straight through to the
    // persistence layer — no draft/Save/Cancel layer.
    const val ARG_BUTTON_ID = "buttonId"
    const val CONFIGURE_BUTTON = "configure_button/{$ARG_BUTTON_ID}"
    fun configureButton(buttonId: String): String = "configure_button/${Uri.encode(buttonId)}"

    // ── Configure-keyboard screen (full-screen, instant-commit) ───────────────────
    //
    // Caller navigates with the layoutId of the keyboard being edited. The screen reads
    // the layout live from the ViewModel and dispatches edits via [MainViewModel.updateLayoutInstant]
    // / [MainViewModel.tryResizeLayout]. The shrink-conflict prompt lives inside the
    // screen as a sub-dialog (no out-of-screen dialog routing required).
    const val ARG_LAYOUT_ID = "layoutId"
    const val CONFIGURE_KEYBOARD = "configure_keyboard/{$ARG_LAYOUT_ID}"
    fun configureKeyboard(layoutId: Long): String = "configure_keyboard/$layoutId"
}
