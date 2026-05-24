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
}

object RemapSections {

    const val SECTION_BUTTONS = "buttons"
    const val SECTION_DPAD = "dpad"
    const val SECTION_TRIGGERS = "triggers"
    const val SECTION_JOYSTICKS = "joysticks"
    const val SECTION_GYRO = "gyro"

    /** Rail entries in display order. Gyro is disabled — visible, but skipped by focus. */
    val rail: List<SectionedPaneItem> = listOf(
        SectionedPaneItem(SECTION_BUTTONS, "Buttons"),
        SectionedPaneItem(SECTION_DPAD, "D-Pad"),
        SectionedPaneItem(SECTION_TRIGGERS, "Triggers"),
        SectionedPaneItem(SECTION_JOYSTICKS, "Joysticks"),
        SectionedPaneItem(SECTION_GYRO, "Gyro", enabled = false),
    )

    val contentBySection: Map<String, List<RemapPaneItem>> = mapOf(
        SECTION_BUTTONS to listOf(
            RemapPaneItem.Subheader("buttons.face.header", "Face Buttons", InputSource.BUTTON_DIAMOND),
            RemapPaneItem.BindingRow("buttons.face.a", "A", InputSource.BUTTON_DIAMOND, "button_a"),
            RemapPaneItem.BindingRow("buttons.face.b", "B", InputSource.BUTTON_DIAMOND, "button_b"),
            RemapPaneItem.BindingRow("buttons.face.x", "X", InputSource.BUTTON_DIAMOND, "button_x"),
            RemapPaneItem.BindingRow("buttons.face.y", "Y", InputSource.BUTTON_DIAMOND, "button_y"),
            // Bumpers + Menu Buttons span multiple sources, each individually
            // SINGLE_BUTTON — no mode choice to surface here.
            RemapPaneItem.Subheader("buttons.bumpers.header", "Bumpers"),
            RemapPaneItem.BindingRow("buttons.bumpers.l1", "L1", InputSource.LEFT_BUMPER, "click"),
            RemapPaneItem.BindingRow("buttons.bumpers.r1", "R1", InputSource.RIGHT_BUMPER, "click"),
            RemapPaneItem.Subheader("buttons.menu.header", "Menu Buttons"),
            RemapPaneItem.BindingRow("buttons.menu.start", "Start", InputSource.SWITCH_START, "click"),
            RemapPaneItem.BindingRow("buttons.menu.select", "Select", InputSource.SWITCH_SELECT, "click"),
        ),
        SECTION_DPAD to listOf(
            RemapPaneItem.Subheader("dpad.header", "Directional Pad Behavior", InputSource.DPAD),
            RemapPaneItem.BindingRow("dpad.up", "D-Pad Up", InputSource.DPAD, "dpad_north"),
            RemapPaneItem.BindingRow("dpad.down", "D-Pad Down", InputSource.DPAD, "dpad_south"),
            RemapPaneItem.BindingRow("dpad.left", "D-Pad Left", InputSource.DPAD, "dpad_west"),
            RemapPaneItem.BindingRow("dpad.right", "D-Pad Right", InputSource.DPAD, "dpad_east"),
        ),
        SECTION_TRIGGERS to listOf(
            RemapPaneItem.Subheader("triggers.left.header", "Left Trigger Behavior", InputSource.LEFT_TRIGGER),
            RemapPaneItem.BindingRow("triggers.left.full", "L2 Full Pull", InputSource.LEFT_TRIGGER, "click"),
            // Soft Pull lights up when the trigger mode is set to TRIGGER (its
            // compile-time validInputs include `soft_press`); under the default
            // UNBOUND mode the binding-row look-up returns null and the row reads
            // as bindable-but-empty, mirroring how a Full Pull row reads when no
            // activator is wired. Brick 5 follow-up unified the soft-press model:
            // soft-pull behavior comes from any activator type bound here, not
            // from a SOFT_PRESS activator type on the click row.
            RemapPaneItem.BindingRow("triggers.left.soft", "L2 Soft Pull", InputSource.LEFT_TRIGGER, "soft_press"),
            RemapPaneItem.DisabledRow("triggers.left.analog", "Analog Output Trigger"),
            RemapPaneItem.Subheader("triggers.right.header", "Right Trigger Behavior", InputSource.RIGHT_TRIGGER),
            RemapPaneItem.BindingRow("triggers.right.full", "R2 Full Pull", InputSource.RIGHT_TRIGGER, "click"),
            RemapPaneItem.BindingRow("triggers.right.soft", "R2 Soft Pull", InputSource.RIGHT_TRIGGER, "soft_press"),
            RemapPaneItem.DisabledRow("triggers.right.analog", "Analog Output Trigger"),
        ),
        SECTION_JOYSTICKS to listOf(
            RemapPaneItem.Subheader("joysticks.left.header", "Left Joystick Behavior", InputSource.LEFT_JOYSTICK),
            RemapPaneItem.BindingRow("joysticks.left.click", "L3 (Stick Click)", InputSource.LEFT_JOYSTICK, "click"),
            RemapPaneItem.Subheader("joysticks.right.header", "Right Joystick Behavior", InputSource.RIGHT_JOYSTICK),
            RemapPaneItem.BindingRow("joysticks.right.click", "R3 (Stick Click)", InputSource.RIGHT_JOYSTICK, "click"),
        ),
        // Gyro: rail entry is disabled. If the user lands on the section anyway,
        // the detail pane shows a placeholder rather than crashing on a missing key.
    )

    const val GYRO_PLACEHOLDER = "Gyro input arrives once analog motion capture lands. Coming soon."
}
