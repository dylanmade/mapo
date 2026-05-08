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

    // ── Configure-button screen (full-screen, instant-commit) ─────────────────────
    //
    // Caller navigates with the buttonId of the button being edited. The screen reads the
    // button live from the ViewModel and dispatches each edit straight through to the
    // persistence layer — no draft/Save/Cancel layer.
    const val ARG_BUTTON_ID = "buttonId"
    const val CONFIGURE_BUTTON = "configure_button/{$ARG_BUTTON_ID}"
    fun configureButton(buttonId: String): String = "configure_button/${Uri.encode(buttonId)}"
}
