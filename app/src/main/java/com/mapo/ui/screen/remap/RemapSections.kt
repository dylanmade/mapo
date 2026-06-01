package com.mapo.ui.screen.remap

import com.mapo.data.model.steam.ActivatorType
import com.mapo.data.model.steam.InputSource
import com.mapo.ui.component.layout.SectionedPaneItem

/**
 * One item rendered into the detail pane of `RemapControlsScreen`.
 * Determined statically by the registry rather than derived from the live
 * `ControllerConfig`, because:
 *  - Section grouping is a UI concept, not a data-layer one.
 *  - We want disabled placeholders (Soft Pull, Analog Output Trigger) visible
 *    *before* the data layer can express them. The registry "promises" rows
 *    that later phases will make live.
 *  - The user's preferred grouping (Buttons → Face/Bumpers/Menu/etc.) doesn't
 *    map 1:1 to Steam's input-source taxonomy, so the registry is the bridge.
 */
sealed class RemapPaneItem {
    abstract val key: String

    /**
     * Section subheader with an optional mode-dropdown affordance.
     *
     * When [inputSource] is non-null the subheader renders a mode picker bound to the
     * source's [com.mapo.data.model.steam.BindingGroup.mode]; when null (e.g. the
     * Bumpers / Menu Buttons sections, which span multiple sources that all use
     * `SINGLE_BUTTON`), no picker is rendered.
     */
    data class Subheader(
        override val key: String,
        val title: String,
        val inputSource: InputSource? = null,
    ) : RemapPaneItem()

    /** A live, bindable row. Resolves to one activator in the active config. */
    data class BindingRow(
        override val key: String,
        val label: String,
        val inputSource: InputSource,
        val groupInputKey: String,
        val activatorType: ActivatorType = ActivatorType.FULL_PRESS,
    ) : RemapPaneItem()

    /** A disabled placeholder — visible but not bindable; tap is a no-op. */
    data class DisabledRow(
        override val key: String,
        val label: String,
    ) : RemapPaneItem()

    /**
     * Phase 7 Brick B.6: header row for a per-source mode shift section. Renders
     * "{sourceLabel} (Mode Shift)" with the shift's mode picker, settings cog,
     * and remove button. Resolved at render time from [InputSource]'s display
     * name.
     */
    data class ModeShiftHeader(
        override val key: String,
        val modeShiftId: Long,
        val ownerSource: InputSource,
    ) : RemapPaneItem()

    /**
     * Phase 7 Brick B.6: bindable row within a mode shift's target group.
     * Routes to InputEditorScreen with the [modeShiftId] nav arg so the
     * editor resolves bindings through the shift's group, not the source's
     * preset.
     */
    data class ModeShiftBindingRow(
        override val key: String,
        val modeShiftId: Long,
        val label: String,
        val ownerSource: InputSource,
        val groupInputKey: String,
    ) : RemapPaneItem()
}

object RemapSections {

    const val SECTION_BUTTONS = "buttons"
    const val SECTION_DPAD = "dpad"
    const val SECTION_TRIGGERS = "triggers"
    const val SECTION_JOYSTICKS = "joysticks"
    const val SECTION_GYRO = "gyro"

    /** Rail entries in display order. */
    val rail: List<SectionedPaneItem> = listOf(
        SectionedPaneItem(SECTION_BUTTONS, "Buttons"),
        SectionedPaneItem(SECTION_DPAD, "D-Pad"),
        SectionedPaneItem(SECTION_TRIGGERS, "Triggers"),
        SectionedPaneItem(SECTION_JOYSTICKS, "Joysticks"),
        SectionedPaneItem(SECTION_GYRO, "Gyro"),
    )

    /**
     * Phase 7 follow-up: sources whose visible sub-input rows depend on the
     * currently-selected [com.mapo.data.model.steam.BindingMode]. The static
     * [contentBySection] registry omits binding rows for these sources; the
     * detail pane resolves the effective mode at render time and generates
     * rows dynamically via [bindableSubInputsFor].
     *
     * Sources NOT in this set always render their static rows (bumpers,
     * switch buttons — all permanently `SINGLE_BUTTON`).
     */
    val MODE_AWARE_SOURCES: Set<InputSource> = setOf(
        InputSource.BUTTON_DIAMOND,
        InputSource.DPAD,
        InputSource.LEFT_TRIGGER,
        InputSource.RIGHT_TRIGGER,
        InputSource.LEFT_JOYSTICK,
        InputSource.RIGHT_JOYSTICK,
        InputSource.GYRO,
    )

    val contentBySection: Map<String, List<RemapPaneItem>> = mapOf(
        SECTION_BUTTONS to listOf(
            // Face buttons: rows generated dynamically per current mode (BUTTON_PAD →
            // A/B/X/Y; DPAD → A/B/X/Y mapped to directions; etc.).
            RemapPaneItem.Subheader("buttons.face.header", "Face Buttons", InputSource.BUTTON_DIAMOND),
            // Bumpers + Menu Buttons span multiple sources, each individually
            // SINGLE_BUTTON — no mode choice to surface here, so the rows stay static.
            RemapPaneItem.Subheader("buttons.bumpers.header", "Bumpers"),
            RemapPaneItem.BindingRow("buttons.bumpers.l1", "L1", InputSource.LEFT_BUMPER, "click"),
            RemapPaneItem.BindingRow("buttons.bumpers.r1", "R1", InputSource.RIGHT_BUMPER, "click"),
            RemapPaneItem.Subheader("buttons.menu.header", "Menu Buttons"),
            RemapPaneItem.BindingRow("buttons.menu.start", "Start", InputSource.SWITCH_START, "click"),
            RemapPaneItem.BindingRow("buttons.menu.select", "Select", InputSource.SWITCH_SELECT, "click"),
        ),
        SECTION_DPAD to listOf(
            // Dpad source rows: dynamic per mode. DPAD/BUTTON_PAD modes surface the
            // four directions; JOYSTICK_MOUSE / etc. surface their respective
            // sub-input vocabularies.
            RemapPaneItem.Subheader("dpad.header", "Directional Pad Behavior", InputSource.DPAD),
        ),
        SECTION_TRIGGERS to listOf(
            RemapPaneItem.Subheader("triggers.left.header", "Left Trigger Behavior", InputSource.LEFT_TRIGGER),
            // Dynamic rows for LEFT_TRIGGER inject here. The Analog Output
            // Trigger DisabledRow stays as a fixed-position placeholder after
            // the dynamic rows — it documents a sub-input we don't yet expose.
            RemapPaneItem.DisabledRow("triggers.left.analog", "Analog Output Trigger"),
            RemapPaneItem.Subheader("triggers.right.header", "Right Trigger Behavior", InputSource.RIGHT_TRIGGER),
            RemapPaneItem.DisabledRow("triggers.right.analog", "Analog Output Trigger"),
        ),
        SECTION_JOYSTICKS to listOf(
            RemapPaneItem.Subheader("joysticks.left.header", "Left Joystick Behavior", InputSource.LEFT_JOYSTICK),
            RemapPaneItem.Subheader("joysticks.right.header", "Right Joystick Behavior", InputSource.RIGHT_JOYSTICK),
        ),
        SECTION_GYRO to listOf(
            // Gyro source has no static sub-input rows — gyro modes (Gyro to Mouse,
            // Gyro to Joystick Camera, etc.) emit continuous output, not bindable
            // sub-inputs. The subheader carries the mode picker; everything else
            // (sensitivity / deadzone / invert) lives in the Cog menu.
            RemapPaneItem.Subheader("gyro.header", "Gyro Behavior", InputSource.GYRO),
        ),
    )

    /**
     * Fallback shown when the detail pane is asked for an unknown sectionId — a
     * defensive default. Today every rail entry has a populated content map, but
     * future un-implemented sections route here rather than crashing.
     */
    const val UNIMPLEMENTED_SECTION_PLACEHOLDER = "This section isn't available yet."

    /**
     * Phase 7 Brick B.6: which sources can have a mode shift added in the
     * editor UI. Maps to a source's Subheader; only subheaders whose
     * [RemapPaneItem.Subheader.inputSource] is in this set show a
     * "+ Add Mode Shift" button. Subheaders without a source (Bumpers,
     * Menu Buttons) cover multiple sources at once and don't get the
     * button — mode shifts are per-source. Triggers + switches are
     * excluded per Steam parity (their physical role makes them awkward
     * shift targets; users shift TO them by binding the trigger as the
     * trigger of someone else's mode shift).
     */
    val MODE_SHIFT_OWNERS: Set<InputSource> = setOf(
        InputSource.BUTTON_DIAMOND,
        InputSource.DPAD,
        InputSource.LEFT_JOYSTICK,
        InputSource.RIGHT_JOYSTICK,
        // GYRO 2026-05-31: Steam Deck's gyro picker exposes "Create a Mode
        // Shift" as a dropdown option. Mapo splits that affordance out as
        // the "+ Add Mode Shift" button on the source row (per
        // `project_mode_shift_per_source_architecture.md`), so the gyro row
        // gets the button alongside the mode picker.
        InputSource.GYRO,
    )

    /**
     * Phase 7 Brick B.6: catalog of physical sub-inputs the user can select as
     * a mode-shift trigger. One entry per `(InputSource, sub_input)` digital
     * edge available on Mapo's target hardware. The trigger picker UI groups
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
     * Phase 7 Brick B.6 / mode-driven base rows: derived bindable sub-input list
     * for a given source + mode. Used by:
     *  - The base source rows (post follow-up) — dynamically rendered after each
     *    [RemapPaneItem.Subheader] whose source is in [MODE_AWARE_SOURCES].
     *  - Mode shift sections — same shape but rendered under a "(Mode Shift)"
     *    heading instead of the source's primary subheader.
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
        mode: com.mapo.data.model.steam.BindingMode,
    ): List<Pair<String, String>> {
        val keys = com.mapo.service.input.modes.validInputsFor(source, mode)
            .ifEmpty { canonicalSubInputsFor(source) }
        return keys.map { key -> key to labelFor(source, key) }
    }

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
