package com.pcpad.data.model

sealed class RemapTarget {
    object Unbound : RemapTarget()
    data class Gamepad(val button: String) : RemapTarget()
    data class Keyboard(val code: String) : RemapTarget()
    data class Mouse(val code: String) : RemapTarget()

    fun encode(): String = when (this) {
        is Unbound  -> "none"
        is Gamepad  -> "gamepad:$button"
        is Keyboard -> "keyboard:$code"
        is Mouse    -> "mouse:$code"
    }

    companion object {
        fun decode(raw: String): RemapTarget = when {
            raw == "none"            -> Unbound
            raw.startsWith("gamepad:")  -> Gamepad(raw.removePrefix("gamepad:"))
            raw.startsWith("keyboard:") -> Keyboard(raw.removePrefix("keyboard:"))
            raw.startsWith("mouse:")    -> Mouse(raw.removePrefix("mouse:"))
            else                        -> Unbound
        }
    }
}

fun RemapTarget.displayLabel(): String = when (this) {
    is RemapTarget.Unbound  -> "— Unbound —"
    is RemapTarget.Gamepad  -> "GP: $button"
    is RemapTarget.Keyboard -> "KB: $code"
    is RemapTarget.Mouse    -> "MS: $code"
}
