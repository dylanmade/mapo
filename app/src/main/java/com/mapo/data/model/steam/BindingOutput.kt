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

    /**
     * Encode into a single string suitable for nav saved-state. Inverse of [decode].
     * Format: `"<outputType.name>|<args>"`. Empty args still keep the trailing `|`,
     * so the decode side can split unconditionally.
     */
    fun encode(): String {
        val (type, args) = toEntity()
        return "${type.name}|$args"
    }

    /** The (outputType, args) shape this BindingOutput persists as. */
    fun toEntity(): Pair<BindingOutputType, String> = when (this) {
        Unbound              -> BindingOutputType.UNBOUND to ""
        is KeyPress          -> BindingOutputType.KEY_PRESS to keyCode
        is XInputButton      -> BindingOutputType.XINPUT_BUTTON to button
        is MouseButton       -> BindingOutputType.MOUSE_BUTTON to button
        is MouseWheel        -> BindingOutputType.MOUSE_WHEEL to direction
        is GameAction        -> BindingOutputType.GAME_ACTION to "$setName,$actionName"
        is ControllerAction  -> BindingOutputType.CONTROLLER_ACTION to (listOf(verb) + args).joinToString(",")
    }

    companion object {
        /**
         * Decode a nav saved-state string produced by [encode]. Unknown discriminators
         * (including unrecognized future variants) fall back to [Unbound] rather than
         * throwing — picker round-trips must be defensive against stale state.
         */
        fun decode(encoded: String): BindingOutput {
            val pipe = encoded.indexOf('|')
            if (pipe < 0) return Unbound
            val typeName = encoded.substring(0, pipe)
            val args = encoded.substring(pipe + 1)
            val type = runCatching { BindingOutputType.valueOf(typeName) }.getOrNull() ?: return Unbound
            return fromEntity(type, args)
        }

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
    BindingOutput.Unbound          -> "(Device default)"
    is BindingOutput.KeyPress      -> "KB: $keyCode"
    is BindingOutput.XInputButton  -> "GP: $button"
    is BindingOutput.MouseButton   -> "MS: $button"
    is BindingOutput.MouseWheel    -> "MS: $direction"
    is BindingOutput.GameAction    -> "Action: $setName/$actionName"
    is BindingOutput.ControllerAction -> when {
        changePresetLabelOrNull() != null ->
            "Switch to: Set #${changePresetLabelOrNull()}"
        verb in LAYER_VERBS ->
            "${verb.layerVerbDisplayPrefix()}: Layer #${layerArgIdOrNull() ?: "?"}"
        else -> "Verb: $verb"
    }
}

/**
 * Context-aware variant of [displayLabel]: resolves `CHANGE_PRESET` to the target set's
 * human title (e.g. "Switch to: Menu") and the layer verbs (`add_layer` / `remove_layer`
 * / `hold_layer`) to their layer's title (e.g. "Hold Layer: Scope"). Falls back to the
 * [displayLabel] form when [config] is null or the referenced set/layer is missing
 * (stale binding after delete).
 */
fun BindingOutput.displayLabel(config: ControllerConfig?): String {
    if (this is BindingOutput.ControllerAction) {
        changePresetLabelOrNull()?.let { targetId ->
            val title = config?.actionSets?.firstOrNull { it.actionSet.id == targetId }?.actionSet?.title
            return if (title != null) "Switch to: $title" else "Switch to: Set #$targetId"
        }
        if (verb in LAYER_VERBS) {
            val layerId = layerArgIdOrNull()
            val prefix = verb.layerVerbDisplayPrefix()
            if (layerId == null) return displayLabel()
            val title = config?.actionSets
                ?.flatMap { it.layers }
                ?.firstOrNull { it.layer.id == layerId }
                ?.layer?.title
            return if (title != null) "$prefix: $title" else "$prefix: Layer #$layerId"
        }
    }
    return displayLabel()
}

/** Extract the target set id from a CHANGE_PRESET binding, or null if this isn't one. */
private fun BindingOutput.ControllerAction.changePresetLabelOrNull(): Long? {
    if (verb != "CHANGE_PRESET") return null
    return args.firstOrNull()?.toLongOrNull()
}

/** Layer-activation verbs. Args[0] is the layer id (Long-encoded as string). */
private val LAYER_VERBS = setOf("add_layer", "remove_layer", "hold_layer")

private fun BindingOutput.ControllerAction.layerArgIdOrNull(): Long? =
    args.firstOrNull()?.toLongOrNull()

private fun String.layerVerbDisplayPrefix(): String = when (this) {
    "add_layer"    -> "Add Layer"
    "remove_layer" -> "Remove Layer"
    "hold_layer"   -> "Hold Layer"
    else           -> "Verb: $this"
}

/**
 * Bridge for the legacy [RemapTarget]-based picker. Steam-Input-only outputs
 * (GameAction / ControllerAction) have no picker UI yet and collapse
 * to [RemapTarget.Unbound] — they'll get their own picker categories in
 * Phase 4 when the verbs become live.
 */
fun BindingOutput.toRemapTarget(): RemapTarget = when (this) {
    BindingOutput.Unbound        -> RemapTarget.Unbound
    is BindingOutput.KeyPress    -> RemapTarget.Keyboard(keyCode)
    is BindingOutput.MouseButton -> RemapTarget.Mouse(button)
    is BindingOutput.MouseWheel  -> RemapTarget.Mouse(direction)
    is BindingOutput.XInputButton -> RemapTarget.Gamepad(button)
    is BindingOutput.GameAction,
    is BindingOutput.ControllerAction -> RemapTarget.Unbound
}
