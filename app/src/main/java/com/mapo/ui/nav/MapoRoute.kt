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

    // ── Remap-target picker (full-screen, multi-step: category → filtered list) ────
    //
    // Caller navigates with title + currently-selected target (encoded). On selection the
    // picker writes the chosen target's encoded form to `previousBackStackEntry.savedStateHandle`
    // under [PICKER_RESULT_KEY] and pops itself. Caller observes its own savedStateHandle on
    // recomposition and applies the result to whatever it's editing.
    const val ARG_TITLE = "title"
    const val ARG_CURRENT = "current"
    const val PICKER_RESULT_KEY = "remap_target_picker_result"
    const val REMAP_TARGET_PICKER = "remap_target_picker?$ARG_TITLE={$ARG_TITLE}&$ARG_CURRENT={$ARG_CURRENT}"
    fun remapTargetPicker(title: String, currentEncoded: String): String =
        "remap_target_picker?$ARG_TITLE=${Uri.encode(title)}&$ARG_CURRENT=${Uri.encode(currentEncoded)}"

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
    const val INPUT_EDITOR =
        "input_editor/{$ARG_INPUT_SOURCE}/{$ARG_GROUP_INPUT_KEY}?$ARG_INPUT_LABEL={$ARG_INPUT_LABEL}"
    fun inputEditor(inputSource: String, groupInputKey: String, label: String): String =
        "input_editor/${Uri.encode(inputSource)}/${Uri.encode(groupInputKey)}?$ARG_INPUT_LABEL=${Uri.encode(label)}"

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
