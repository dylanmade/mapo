package com.mapo.data.model.steam

import com.mapo.data.model.RemapTarget

/**
 * Typed wrapper over a [Binding]'s `(outputType, args)` pair.
 *
 * The Room layer stores bindings as a discriminator enum + a Steam-VDF-style CSV string;
 * this sealed class is what the rest of the app code talks to. Conversion lives in
 * [toEntity] / [fromEntity]. [fromRemapTarget] is the bridge from Mapo's legacy
 * 1-to-1 wrapper, used by the brick 1.3 picker flow and the virtual-keyboard adapter.
 */
sealed class BindingOutput {

    /** No output configured for this slot. Slot exists; firing it is a no-op. */
    object Unbound : BindingOutput()

    /** A keyboard key. `keyCode` matches Mapo's existing key-code naming (e.g. "ENTER"). */
    data class KeyPress(val keyCode: String) : BindingOutput()

    /** A virtual gamepad button. `button` matches DeviceButton naming (e.g. "BUTTON_A"). */
    data class XInputButton(val button: String) : BindingOutput()

    /** Mouse button — "MOUSE_LEFT", "MOUSE_RIGHT", "MOUSE_MIDDLE", "MOUSE_BACK", "MOUSE_FORWARD". */
    data class MouseButton(val button: String) : BindingOutput()

    /** Mouse wheel — "SCROLL_UP" or "SCROLL_DOWN". */
    data class MouseWheel(val direction: String) : BindingOutput()

    /** Action-based mode reference: `<action_set_name>,<action_name>`. Placeholder pre-Phase 4. */
    data class GameAction(val setName: String, val actionName: String) : BindingOutput()

    /** In-engine verb: CHANGE_PRESET, add_layer, remove_layer, etc. Implemented per phase. */
    data class ControllerAction(val verb: String, val args: List<String>) : BindingOutput()

    /** While-held single-source override (Phase 5). Targets a specific binding group. */
    data class ModeShift(val inputSource: InputSource, val bindingGroupId: Long) : BindingOutput()

    /** The (outputType, args) shape this BindingOutput persists as. */
    fun toEntity(): Pair<BindingOutputType, String> = when (this) {
        Unbound              -> BindingOutputType.UNBOUND to ""
        is KeyPress          -> BindingOutputType.KEY_PRESS to keyCode
        is XInputButton      -> BindingOutputType.XINPUT_BUTTON to button
        is MouseButton       -> BindingOutputType.MOUSE_BUTTON to button
        is MouseWheel        -> BindingOutputType.MOUSE_WHEEL to direction
        is GameAction        -> BindingOutputType.GAME_ACTION to "$setName,$actionName"
        is ControllerAction  -> BindingOutputType.CONTROLLER_ACTION to (listOf(verb) + args).joinToString(",")
        is ModeShift         -> BindingOutputType.MODE_SHIFT to "${inputSource.name},$bindingGroupId"
    }

    companion object {
        fun fromEntity(outputType: BindingOutputType, args: String): BindingOutput = when (outputType) {
            BindingOutputType.UNBOUND -> Unbound
            BindingOutputType.KEY_PRESS -> KeyPress(args)
            BindingOutputType.XINPUT_BUTTON -> XInputButton(args)
            BindingOutputType.MOUSE_BUTTON -> MouseButton(args)
            BindingOutputType.MOUSE_WHEEL -> MouseWheel(args)
            BindingOutputType.GAME_ACTION -> {
                val parts = args.split(",", limit = 2)
                GameAction(parts.getOrElse(0) { "" }, parts.getOrElse(1) { "" })
            }
            BindingOutputType.CONTROLLER_ACTION -> {
                val parts = args.split(",")
                ControllerAction(parts.firstOrNull().orEmpty(), parts.drop(1))
            }
            BindingOutputType.MODE_SHIFT -> {
                val parts = args.split(",", limit = 2)
                val source = parts.getOrNull(0)
                    ?.let { runCatching { InputSource.valueOf(it) }.getOrNull() }
                    ?: InputSource.BUTTON_DIAMOND
                val groupId = parts.getOrNull(1)?.toLongOrNull() ?: -1L
                ModeShift(source, groupId)
            }
        }

        fun fromRemapTarget(target: RemapTarget): BindingOutput = when (target) {
            RemapTarget.Unbound -> Unbound
            is RemapTarget.Keyboard -> KeyPress(target.code)
            is RemapTarget.Mouse -> when (target.code) {
                "SCROLL_UP", "SCROLL_DOWN" -> MouseWheel(target.code)
                else -> MouseButton(target.code)
            }
            is RemapTarget.Gamepad -> XInputButton(target.button)
        }
    }
}

/** One-line display label for a binding output, suitable for trailing row text. */
fun BindingOutput.displayLabel(): String = when (this) {
    BindingOutput.Unbound          -> "— Unbound —"
    is BindingOutput.KeyPress      -> "KB: $keyCode"
    is BindingOutput.XInputButton  -> "GP: $button"
    is BindingOutput.MouseButton   -> "MS: $button"
    is BindingOutput.MouseWheel    -> "MS: $direction"
    is BindingOutput.GameAction    -> "Action: $setName/$actionName"
    is BindingOutput.ControllerAction -> "Verb: $verb"
    is BindingOutput.ModeShift     -> "Mode shift: ${inputSource.name}"
}

/**
 * Bridge for the legacy [RemapTarget]-based picker. Steam-Input-only outputs
 * (GameAction / ControllerAction / ModeShift) have no picker UI yet and collapse
 * to [RemapTarget.Unbound] — they'll get their own picker categories in
 * Phases 4 / 5 when the verbs become live.
 */
fun BindingOutput.toRemapTarget(): RemapTarget = when (this) {
    BindingOutput.Unbound        -> RemapTarget.Unbound
    is BindingOutput.KeyPress    -> RemapTarget.Keyboard(keyCode)
    is BindingOutput.MouseButton -> RemapTarget.Mouse(button)
    is BindingOutput.MouseWheel  -> RemapTarget.Mouse(direction)
    is BindingOutput.XInputButton -> RemapTarget.Gamepad(button)
    is BindingOutput.GameAction,
    is BindingOutput.ControllerAction,
    is BindingOutput.ModeShift   -> RemapTarget.Unbound
}
