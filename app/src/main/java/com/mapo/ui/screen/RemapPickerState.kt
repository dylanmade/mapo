package com.mapo.ui.screen

sealed class RemapPickerState {
    object CategorySelection : RemapPickerState()
    data class GamepadList(val filter: String = "") : RemapPickerState()
    data class KeyboardList(val filter: String = "") : RemapPickerState()
    data class MouseList(val filter: String = "") : RemapPickerState()

    /** Brick 4.5: list of action sets in the current controller_profile. No filter — the list is short. */
    object SwitchActionSetList : RemapPickerState()
}
