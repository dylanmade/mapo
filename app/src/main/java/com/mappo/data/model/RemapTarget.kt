package com.mappo.data.model

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

        /**
         * Classify a bare code (e.g. "ENTER", "MOUSE_LEFT", "SCROLL_UP") into the
         * appropriate RemapTarget subtype. Mirrors the dispatch routing the
         * accessibility service uses for click-style targets.
         */
        fun fromCode(code: String): RemapTarget = when (code) {
            "MOUSE_LEFT", "MOUSE_MIDDLE", "MOUSE_RIGHT", "MOUSE_BACK", "MOUSE_FORWARD",
            "SCROLL_UP", "SCROLL_DOWN" -> Mouse(code)
            else -> Keyboard(code)
        }
    }
}

fun RemapTarget.displayLabel(): String = when (this) {
    is RemapTarget.Unbound  -> "(Device default)"
    is RemapTarget.Gamepad  -> "GP: $button"
    is RemapTarget.Keyboard -> "KB: $code"
    is RemapTarget.Mouse    -> "MS: $code"
}
