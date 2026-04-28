package com.mapo.ui.screen

sealed class RemapPickerState {
    object CategorySelection : RemapPickerState()
    data class GamepadList(val filter: String = "") : RemapPickerState()
    data class KeyboardList(val filter: String = "") : RemapPickerState()
    data class MouseList(val filter: String = "") : RemapPickerState()
}
