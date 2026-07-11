package com.mappo.ui.screen.remap

import com.mappo.data.model.steam.InputSource
import com.mappo.service.input.modes.StubMode
import com.mappo.service.input.modes.handler

/**
 * Shared registries for the Remap Controls editor: which sub-input rows a (source × mode)
 * pair surfaces, source-aware sub-input labels, which sources can own added modes (mode
 * shifts), and the physical digital sub-inputs offered as mode-shift triggers.
 *
 * (The rail/section-pane registry that used to live here died with the retired detail-pane
 * editor — the simple view's groups are in RemapSimpleView.kt.)
 */
object RemapSections {

    /**
     * Which sources can have a mode shift ("added input mode") created from the editor UI.
     * Triggers + switches are excluded per Steam parity (their physical role makes them
     * awkward shift targets; users shift TO them by binding them as the trigger of someone
     * else's mode shift).
     */
    val MODE_SHIFT_OWNERS: Set<InputSource> = setOf(
        InputSource.BUTTON_DIAMOND,
        InputSource.DPAD,
        InputSource.LEFT_JOYSTICK,
        InputSource.RIGHT_JOYSTICK,
        // GYRO 2026-05-31: Steam Deck's gyro picker exposes "Create a Mode
        // Shift" as a dropdown option. Mappo splits that affordance out as
        // the "+ Add Mode Shift" button on the source row (per
        // `project_mode_shift_per_source_architecture.md`), so the gyro row
        // gets the button alongside the mode picker.
        InputSource.GYRO,
    )

    /**
     * Phase 7 Brick B.6: catalog of physical sub-inputs the user can select as
     * a mode-shift trigger. One entry per `(InputSource, sub_input)` digital
     * edge available on Mappo's target hardware. The trigger picker UI groups
     * these by [groupTitle].
     */
    data class TriggerInputOption(
        val source: InputSource,
        val subInput: String,
        val label: String,
        val groupTitle: String,
    )

    val TRIGGER_INPUT_CATALOG: List<TriggerInputOption> = listOf(
        TriggerInputOption(InputSource.BUTTON_DIAMOND, "button_a", "A", "Face Buttons"),
        TriggerInputOption(InputSource.BUTTON_DIAMOND, "button_b", "B", "Face Buttons"),
        TriggerInputOption(InputSource.BUTTON_DIAMOND, "button_x", "X", "Face Buttons"),
        TriggerInputOption(InputSource.BUTTON_DIAMOND, "button_y", "Y", "Face Buttons"),
        TriggerInputOption(InputSource.LEFT_BUMPER, "click", "L1", "Bumpers"),
        TriggerInputOption(InputSource.RIGHT_BUMPER, "click", "R1", "Bumpers"),
        TriggerInputOption(InputSource.LEFT_TRIGGER, "full_pull", "L2 (Full Pull)", "Triggers"),
        TriggerInputOption(InputSource.RIGHT_TRIGGER, "full_pull", "R2 (Full Pull)", "Triggers"),
        TriggerInputOption(InputSource.DPAD, "dpad_up", "D-Pad Up", "D-Pad"),
        TriggerInputOption(InputSource.DPAD, "dpad_down", "D-Pad Down", "D-Pad"),
        TriggerInputOption(InputSource.DPAD, "dpad_left", "D-Pad Left", "D-Pad"),
        TriggerInputOption(InputSource.DPAD, "dpad_right", "D-Pad Right", "D-Pad"),
        TriggerInputOption(InputSource.LEFT_JOYSTICK, "click", "L3 (Stick Click)", "Joystick Clicks"),
        TriggerInputOption(InputSource.RIGHT_JOYSTICK, "click", "R3 (Stick Click)", "Joystick Clicks"),
        TriggerInputOption(InputSource.SWITCH_START, "click", "Start", "Menu Buttons"),
        TriggerInputOption(InputSource.SWITCH_SELECT, "click", "Select", "Menu Buttons"),
    )

    /**
     * Derived bindable sub-input list for a given source + mode — drives the group editor's
     * rows (and any future mode-shift sections).
     *
     * Reads through `validInputsFor(source, mode)` so the result stays in sync
     * with the runtime catalog. Labels are source-aware via [labelFor] —
     * "Click" reads as "L3 (Stick Click)" on the left joystick, "L2 Full Pull"
     * on the left trigger, etc.
     *
     * Empty-mode fallback: when `validInputsFor` returns empty (which happens
     * for [BindingMode.DEVICE_DEFAULT] / [BindingMode.NONE] / stub modes),
     * we render [canonicalSubInputsFor]'s rows instead so the user sees the
     * source's "natural" sub-inputs and can pre-configure bindings before
     * picking a runtime-active mode. The bindings still exist in the DB and
     * activate as soon as the user picks a real mode.
     */
    fun bindableSubInputsFor(
        source: InputSource,
        mode: com.mappo.data.model.steam.BindingMode,
    ): List<Pair<String, String>> {
        val valid = com.mappo.service.input.modes.validInputsFor(source, mode)
        // Empty-mode fallback applies ONLY to the "no real mode picked" sentinels
        // (DEVICE_DEFAULT / NONE) and not-yet-built stub modes. A real mode that
        // intentionally has zero bindable sub-inputs (e.g. Joystick, where the
        // buttons/stick become the analog output; or the gyro→* modes) must render
        // NO rows — its empty result is authoritative, not a "fall back" signal.
        val keys = when {
            valid.isNotEmpty() -> valid
            shouldFallBackWhenEmpty(mode) -> canonicalSubInputsFor(source)
            else -> emptySet()
        }
        return keys.map { key -> key to labelFor(source, key) }
    }

    /** True for the modes where an empty sub-input set means "show canonical rows so
     *  the user can pre-bind" rather than "this mode genuinely has no sub-inputs." */
    private fun shouldFallBackWhenEmpty(mode: com.mappo.data.model.steam.BindingMode): Boolean =
        mode == com.mappo.data.model.steam.BindingMode.DEVICE_DEFAULT ||
            mode == com.mappo.data.model.steam.BindingMode.NONE ||
            mode.handler() is StubMode

    /**
     * Canonical sub-input set per mode-aware source — the rows the editor
     * displays as a fallback when the current mode would otherwise produce
     * an empty list ([BindingMode.DEVICE_DEFAULT] / [BindingMode.NONE] /
     * stub modes). Matches the seed data in
     * `ControllerConfigRepository.DEFAULT_INPUT_SOURCE_SEEDS` so a fresh
     * install always shows a populated editor.
     */
    private fun canonicalSubInputsFor(source: InputSource): Set<String> = when (source) {
        InputSource.BUTTON_DIAMOND -> setOf("button_a", "button_b", "button_x", "button_y")
        InputSource.DPAD -> setOf("dpad_up", "dpad_down", "dpad_left", "dpad_right")
        InputSource.LEFT_TRIGGER, InputSource.RIGHT_TRIGGER -> setOf("full_pull", "soft_pull")
        InputSource.LEFT_JOYSTICK, InputSource.RIGHT_JOYSTICK -> setOf("click", "outer_ring")
        else -> emptySet()
    }

    /**
     * Phase 7 follow-up: source-aware sub-input label. Lets a generic key like
     * `"click"` render as "L3 (Stick Click)" on the left joystick and "Click"
     * everywhere else, without exploding the registry into one-row-per-source-
     * per-key. Falls back to [SUB_INPUT_LABELS] when no source-specific
     * override exists; ultimately falls back to the raw key.
     */
    fun labelFor(source: InputSource, subInputKey: String): String {
        when (source) {
            InputSource.LEFT_JOYSTICK -> when (subInputKey) {
                "click" -> return "L3 (Stick Click)"
                "outer_ring" -> return "L3 Outer Ring"
            }
            InputSource.RIGHT_JOYSTICK -> when (subInputKey) {
                "click" -> return "R3 (Stick Click)"
                "outer_ring" -> return "R3 Outer Ring"
            }
            InputSource.LEFT_TRIGGER -> when (subInputKey) {
                "full_pull" -> return "L2 Full Pull"
                "soft_pull" -> return "L2 Soft Pull"
                "click" -> return "L2 Click"
            }
            InputSource.RIGHT_TRIGGER -> when (subInputKey) {
                "full_pull" -> return "R2 Full Pull"
                "soft_pull" -> return "R2 Soft Pull"
                "click" -> return "R2 Click"
            }
            InputSource.DPAD -> when (subInputKey) {
                "dpad_up" -> return "D-Pad Up"
                "dpad_down" -> return "D-Pad Down"
                "dpad_left" -> return "D-Pad Left"
                "dpad_right" -> return "D-Pad Right"
            }
            else -> {}
        }
        return SUB_INPUT_LABELS[subInputKey] ?: subInputKey
    }

    private val SUB_INPUT_LABELS: Map<String, String> = mapOf(
        "button_a" to "A",
        "button_b" to "B",
        "button_x" to "X",
        "button_y" to "Y",
        "dpad_up" to "Up",
        "dpad_down" to "Down",
        "dpad_left" to "Left",
        "dpad_right" to "Right",
        "click" to "Click",
        "full_pull" to "Full Pull",
        "soft_pull" to "Soft Pull",
        "outer_ring" to "Outer Ring",
    )
}
