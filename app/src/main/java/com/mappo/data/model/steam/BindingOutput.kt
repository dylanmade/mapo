package com.mappo.data.model.steam

import com.mappo.data.model.RemapTarget

/**
 * Typed wrapper over a [Binding]'s `(outputType, args)` pair.
 *
 * The Room layer stores bindings as a discriminator enum + a Steam-VDF-style CSV string;
 * this sealed class is what the rest of the app code talks to. Conversion lives in
 * [toEntity] / [fromEntity]. [fromRemapTarget] is the bridge from Mappo's legacy
 * 1-to-1 wrapper, used by the brick 1.3 picker flow and the virtual-keyboard adapter.
 */
sealed class BindingOutput {

    /** No output configured for this slot. Slot exists; firing it is a no-op. */
    object Unbound : BindingOutput()

    /** A keyboard key. `keyCode` matches Mappo's existing key-code naming (e.g. "ENTER"). */
    data class KeyPress(val keyCode: String) : BindingOutput()

    /** A virtual gamepad button. `button` matches DeviceButton naming (e.g. "BUTTON_A"). */
    data class XInputButton(val button: String) : BindingOutput()

    /**
     * A virtual gamepad stick deflection. [stick] is "LEFT" / "RIGHT"; [direction] is
     * "UP" / "DOWN" / "LEFT" / "RIGHT". Emits an analog axis on the virtual gamepad
     * (requires Shizuku), unlike [XInputButton] which injects a digital button key.
     */
    data class XInputStick(val stick: String, val direction: String) : BindingOutput() {
        /** Token form used by the [RemapTarget.Gamepad] picker bridge, e.g. "LSTICK_UP". */
        fun token(): String = (if (stick == "LEFT") "LSTICK_" else "RSTICK_") + direction
    }

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
        is XInputStick       -> BindingOutputType.XINPUT_STICK to "$stick,$direction"
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
            BindingOutputType.XINPUT_STICK -> {
                val parts = args.split(",", limit = 2)
                XInputStick(parts.getOrElse(0) { "LEFT" }, parts.getOrElse(1) { "UP" })
            }
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
            is RemapTarget.Gamepad -> stickFromToken(target.button) ?: XInputButton(target.button)
        }

        /** Parse a stick token ("LSTICK_UP" / "RSTICK_LEFT" …) to an [XInputStick], or null. */
        private fun stickFromToken(token: String): XInputStick? {
            val stick = when {
                token.startsWith("LSTICK_") -> "LEFT"
                token.startsWith("RSTICK_") -> "RIGHT"
                else -> return null
            }
            val direction = token.substringAfter("STICK_")
            if (direction !in setOf("UP", "DOWN", "LEFT", "RIGHT")) return null
            return XInputStick(stick, direction)
        }
    }
}

/**
 * True if this output is a virtual-gamepad output ([XInputButton] — incl. the AXIS_L2/R2
 * analog triggers — or [XInputStick] directions). These only reach a game through the
 * Mappo Virtual Gamepad (MVG), which is only the game's controller while the physical pad
 * is grabbed — so binding one forces a grab. Drives both the grab decision
 * ([com.mappo.service.shizuku.ShizukuMotionCoordinator]) and which outputs need Shizuku.
 */
fun BindingOutput.isGamepadOutput(): Boolean = when (this) {
    is BindingOutput.XInputButton -> true
    is BindingOutput.XInputStick -> true
    else -> false
}

/**
 * True if this output can only be produced via the Shizuku virtual gamepad — i.e. it
 * silently no-ops (in games) without Shizuku. All gamepad outputs qualify: the analog
 * stick directions, and (as of 2026-06-10) gamepad buttons + analog triggers, which are
 * only seen by a game through the grabbed MVG — a SOURCE_KEYBOARD key inject reaches a
 * game's menu/overlay layer, never its gamepad layer. Drives the Shizuku-required warning
 * surfaces (banner / drawer notification), parallel to `BindingMode.requiresShizuku()`.
 */
fun BindingOutput.requiresShizuku(): Boolean = isGamepadOutput()

/** One-line display label for a binding output, suitable for trailing row text. */
fun BindingOutput.displayLabel(): String = when (this) {
    BindingOutput.Unbound          -> "(Device default)"
    is BindingOutput.KeyPress      -> "KB: $keyCode"
    is BindingOutput.XInputButton  -> "GP: $button"
    is BindingOutput.XInputStick   -> "GP: ${if (stick == "LEFT") "Left" else "Right"} Stick " +
        direction.lowercase().replaceFirstChar { it.uppercase() }
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
    is BindingOutput.XInputStick -> RemapTarget.Gamepad(token())
    is BindingOutput.GameAction,
    is BindingOutput.ControllerAction -> RemapTarget.Unbound
}
