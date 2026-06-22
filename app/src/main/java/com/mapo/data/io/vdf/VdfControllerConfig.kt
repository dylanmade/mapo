package com.mapo.data.io.vdf

/**
 * Structural reading of a `controller_mappings` VDF — one mechanical step
 * removed from the raw [VdfValue] tree, with the document's shape (action sets,
 * layers, groups, inputs, activators, presets, settings, localization) lifted
 * into typed records.
 *
 * This is the *first half* of VDF import: pure restructuring with **no Mapo
 * schema decisions**. The schema translator (`VdfImporter`, a later brick) maps
 * these records onto `ActionSet` / `ActionLayer` / `BindingGroup` / `GroupInput`
 * / `Activator` / `Binding` and decides mode-token → [com.mapo.data.model.steam.BindingMode],
 * activator-token → `ActivatorType`, and controller-type mappings. Keeping that
 * boundary sharp means everything here is deterministic and unit-testable
 * against a real config without standing up Room or the runtime.
 *
 * VDF tokens are preserved verbatim (`mode = "joystick_move"`, `type =
 * "Soft_Press"`); translation to Mapo enums happens downstream so the mapping
 * tables live in one place.
 */
data class VdfControllerConfig(
    val version: String?,
    val revision: String?,
    val title: String?,
    val description: String?,
    /** `controller_neptune` / `controller_xboxone` / `controller_ps4` / … */
    val controllerType: String?,
    val actionSets: List<VdfActionSet>,
    val actionLayers: List<VdfActionLayer>,
    val groups: List<VdfGroup>,
    val presets: List<VdfPreset>,
    /** language → (token → localized string). Used to resolve `#token` refs at import. */
    val localization: Map<String, Map<String, String>>,
    /** Top-level config-wide settings block. */
    val settings: Map<String, String>,
) {
    /**
     * True when every action set/layer is `legacy_set "1"` — bindings target raw
     * inputs (`key_press` / `mouse_button` / `xinput_button`) and can be imported
     * concretely. `legacy_set "0"` (action-based) bindings reference game actions
     * and land as placeholders; see the plan's Phase 8 scope.
     */
    val isLegacyRawBindings: Boolean
        get() = actionSets.all { it.legacySet != "0" } && actionLayers.all { it.legacySet != "0" }

    companion object {
        /** Reads a parsed VDF document. Accepts either the raw `parse()` result
         *  (with a top-level `controller_mappings` wrapper) or an already-unwrapped
         *  config block. */
        fun from(root: VdfValue.Obj): VdfControllerConfig {
            val cm = root.obj("controller_mappings") ?: root

            val actionSets = cm.obj("actions")?.entries.orEmpty().map { (name, value) ->
                val o = value as? VdfValue.Obj ?: VdfValue.Obj(emptyList())
                VdfActionSet(name = name, title = o.string("title"), legacySet = o.string("legacy_set"))
            }

            val actionLayers = cm.obj("action_layers")?.entries.orEmpty().map { (name, value) ->
                val o = value as? VdfValue.Obj ?: VdfValue.Obj(emptyList())
                VdfActionLayer(
                    name = name,
                    title = o.string("title"),
                    legacySet = o.string("legacy_set"),
                    setLayer = o.string("set_layer"),
                    parentSetName = o.string("parent_set_name"),
                )
            }

            val groups = cm.objects("group").map { g -> readGroup(g) }
            val presets = cm.objects("preset").map { p -> readPreset(p) }

            val localization = cm.obj("localization")?.entries.orEmpty().associate { (lang, value) ->
                lang to ((value as? VdfValue.Obj)?.toStringMap() ?: emptyMap())
            }

            return VdfControllerConfig(
                version = cm.string("version"),
                revision = cm.string("revision"),
                title = cm.string("title"),
                description = cm.string("description"),
                controllerType = cm.string("controller_type"),
                actionSets = actionSets,
                actionLayers = actionLayers,
                groups = groups,
                presets = presets,
                localization = localization,
                settings = cm.obj("settings")?.toStringMap() ?: emptyMap(),
            )
        }

        /** Convenience: parse raw VDF text straight into the structural model. */
        fun parse(vdfText: String): VdfControllerConfig = from(VdfParser.parse(vdfText))

        private fun readGroup(g: VdfValue.Obj): VdfGroup {
            val inputs = g.obj("inputs")?.entries.orEmpty().map { (inputName, value) ->
                val io = value as? VdfValue.Obj ?: VdfValue.Obj(emptyList())
                VdfGroupInput(
                    name = inputName,
                    activators = io.obj("activators")?.entries.orEmpty().map { (type, av) ->
                        val ao = av as? VdfValue.Obj ?: VdfValue.Obj(emptyList())
                        VdfActivator(
                            type = type,
                            bindings = ao.obj("bindings")?.all("binding").orEmpty()
                                .mapNotNull { (it as? VdfValue.Str)?.value }
                                .map { VdfBinding.parse(it) },
                            settings = ao.obj("settings")?.toStringMap() ?: emptyMap(),
                        )
                    },
                    disabledActivators = io.obj("disabled_activators")?.keys.orEmpty(),
                )
            }
            return VdfGroup(
                id = g.string("id"),
                mode = g.string("mode"),
                name = g.string("name"),
                description = g.string("description"),
                inputs = inputs,
                settings = g.obj("settings")?.toStringMap() ?: emptyMap(),
            )
        }

        private fun readPreset(p: VdfValue.Obj): VdfPreset = VdfPreset(
            id = p.string("id"),
            name = p.string("name"),
            sourceBindings = p.obj("group_source_bindings")?.entries.orEmpty()
                .mapNotNull { (groupId, value) ->
                    val spec = (value as? VdfValue.Str)?.value ?: return@mapNotNull null
                    VdfSourceBinding.parse(groupId, spec)
                },
        )
    }
}

/** A `controller_mappings.actions.<name>` action set. */
data class VdfActionSet(val name: String, val title: String?, val legacySet: String?)

/** A `controller_mappings.action_layers.<name>` action layer. */
data class VdfActionLayer(
    val name: String,
    val title: String?,
    val legacySet: String?,
    val setLayer: String?,
    val parentSetName: String?,
)

/** One `"group"` block: a source mode plus its bindable inputs. */
data class VdfGroup(
    val id: String?,
    /** VDF mode token, e.g. `four_buttons`, `joystick_move`, `trigger`, `dpad`. */
    val mode: String?,
    val name: String?,
    val description: String?,
    val inputs: List<VdfGroupInput>,
    val settings: Map<String, String>,
)

/** One bindable input row inside a group (e.g. `button_a`, `click`, `dpad_north`). */
data class VdfGroupInput(
    val name: String,
    val activators: List<VdfActivator>,
    /** Activator types present-but-disabled (Steam keeps their config around). */
    val disabledActivators: List<String>,
)

/** One activator (`Full_Press`, `Soft_Press`, `Long_Press`, …) and its commands. */
data class VdfActivator(
    /** VDF activator token, casing preserved (`Full_Press`). */
    val type: String,
    val bindings: List<VdfBinding>,
    val settings: Map<String, String>,
)

/**
 * A parsed `"binding"` string. VDF binding rows are CSV: `<command>, <label>,
 * <icon>` where the trailing two fields are usually empty (`"key_press SPACE, , "`).
 * The command itself is whitespace-delimited (`controller_action add_layer 3 1 1`).
 */
data class VdfBinding(
    /** First token of the command — `key_press`, `mouse_button`, `xinput_button`,
     *  `controller_action`, `game_action`, `mode_shift`, … */
    val verb: String,
    /** Remaining whitespace-delimited command tokens. */
    val args: List<String>,
    /** Optional author-supplied label (CSV field 2). */
    val label: String,
    /** Optional icon path (CSV field 3). */
    val icon: String,
    /** The whole command field (verb + args), pre-split — handy for logging. */
    val command: String,
    /** The exact original string, for faithful re-export / diagnostics. */
    val raw: String,
) {
    companion object {
        fun parse(raw: String): VdfBinding {
            val parts = raw.split(',')
            val command = parts.getOrNull(0)?.trim().orEmpty()
            val tokens = command.split(Regex("\\s+")).filter { it.isNotEmpty() }
            return VdfBinding(
                verb = tokens.firstOrNull().orEmpty(),
                args = tokens.drop(1),
                label = parts.getOrNull(1)?.trim().orEmpty(),
                icon = parts.getOrNull(2)?.trim().orEmpty(),
                command = command,
                raw = raw,
            )
        }
    }
}

/** One `"preset"` block: which group each source binds to in a given preset. */
data class VdfPreset(
    val id: String?,
    val name: String?,
    val sourceBindings: List<VdfSourceBinding>,
)

/**
 * One `group_source_bindings` row: `"<groupId>" "<source> <active|inactive> [modeshift]"`.
 * Example: `"17" "right_joystick active modeshift"`.
 */
data class VdfSourceBinding(
    val groupId: String,
    /** Source token (`right_joystick`, `dpad`, `button_diamond`, `left_trigger`, `switch`…). */
    val source: String,
    /** `active` (vs `inactive`) — whether the group is the live binding for the source. */
    val active: Boolean,
    /** True when the row carries the `modeshift` qualifier (a mode-shift overlay group). */
    val modeshift: Boolean,
    /** All qualifier tokens after the source, preserved for anything not modeled above. */
    val qualifiers: List<String>,
) {
    companion object {
        fun parse(groupId: String, spec: String): VdfSourceBinding {
            val tokens = spec.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
            val source = tokens.firstOrNull().orEmpty()
            val qualifiers = tokens.drop(1)
            return VdfSourceBinding(
                groupId = groupId,
                source = source,
                active = "active" in qualifiers,
                modeshift = "modeshift" in qualifiers,
                qualifiers = qualifiers,
            )
        }
    }
}
