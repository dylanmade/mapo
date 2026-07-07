package com.mappo.ui.screen

sealed class RemapPickerState {
    object CategorySelection : RemapPickerState()
    data class GamepadList(val filter: String = "") : RemapPickerState()
    data class KeyboardList(val filter: String = "") : RemapPickerState()
    data class MouseList(val filter: String = "") : RemapPickerState()

    /** Brick 4.5: list of action sets in the current controller_profile. No filter — the list is short. */
    object SwitchActionSetList : RemapPickerState()

    /**
     * Brick 5.6: pick which layer-activation verb (Add Layer / Hold Layer / Remove
     * Layer). Two-step flow: verb selection → layer selection. The verb-then-layer
     * order means common-verb-with-different-layer cases avoid duplicate verb rows.
     */
    object LayerVerbList : RemapPickerState()

    /** Brick 5.6: pick which layer for the previously-selected [verb]. */
    data class LayerSelectionList(val verb: String) : RemapPickerState()
}
