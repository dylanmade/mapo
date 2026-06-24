package com.mapo.data.io.vdf

import com.mapo.data.model.steam.ActivatorType
import com.mapo.data.model.steam.BindingMode
import com.mapo.data.model.steam.BindingOutput
import com.mapo.data.model.steam.ControllerType
import com.mapo.data.model.steam.InputSource

/**
 * Translates a parsed [VdfControllerConfig] into a Mapo-shaped, Room-id-free
 * **import model** ([ImportedConfig]). This is where every "what does this Steam
 * token become in Mapo" decision lives; the mechanical token tables are in
 * [VdfMappings].
 *
 * **Why an intermediate model and not Room entities.** Mapo entities are keyed by
 * Room auto-generated `Long` ids and wired by FK — those ids don't exist until
 * insertion. Producing this id-free tree keeps the translation pure (unit-testable
 * with real enums, no DB) and lets a separate, mechanical persistence brick walk
 * the tree assigning ids and resolving deferred cross-references (CHANGE_PRESET /
 * add_layer targets, mode-shift trigger wiring). See `project_vdf_parser_landed`.
 *
 * **Scope of this brick.** The common `legacy_set "1"` per-source modes, full
 * binding-string → [BindingOutput] tables, the trigger soft-pull unification, and
 * the [ImportSummary]. Structurally-hard cases are surfaced as [ImportWarning]s
 * (never silent wrong-fallbacks) and finished later:
 *  - `switches` mode (one VDF group → many Mapo single-button sources)
 *  - `mode_shift` trigger wiring into `SourceModeShift`
 *  - layer/preset id remapping in CHANGE_PRESET / add_layer args
 *  - group/activator setting-key schema translation (carried raw + flagged)
 *  - `game_action` (legacy_set "0") bindings (landed as placeholders)
 */
object VdfImporter {

    /** Convenience: parse raw VDF text and translate in one call. */
    fun import(vdfText: String): ImportedConfig = translate(VdfControllerConfig.parse(vdfText))

    fun translate(cfg: VdfControllerConfig): ImportedConfig {
        val warnings = mutableListOf<ImportWarning>()

        if (!cfg.isLegacyRawBindings) {
            warnings += ImportWarning(
                ImportWarningKind.ACTION_BASED_CONFIG,
                "Config uses action-based bindings (legacy_set 0); game_action commands land as placeholders.",
            )
        }

        // VDF group id → parsed group, for preset resolution.
        val groupsById: Map<String, VdfGroup> = cfg.groups.mapNotNull { g -> g.id?.let { it to g } }.toMap()

        // Resolve which preset feeds a given set/layer by *name* (preset.name == set/layer name).
        val presetByName: Map<String, VdfPreset> = cfg.presets.mapNotNull { p -> p.name?.let { it to p } }.toMap()

        fun groupsFor(ownerName: String): List<ImportedPresetGroup> {
            val preset = presetByName[ownerName] ?: return emptyList()
            return preset.sourceBindings.mapNotNull { sb ->
                val group = groupsById[sb.groupId] ?: run {
                    warnings += ImportWarning(
                        ImportWarningKind.DANGLING_GROUP_REF,
                        "Preset '${ownerName}' references missing group id ${sb.groupId}.",
                    )
                    return@mapNotNull null
                }
                val source = VdfMappings.inputSource(sb.source)
                if (source == null) {
                    warnings += ImportWarning(
                        ImportWarningKind.UNMAPPED_SOURCE,
                        "Source '${sb.source}' has no Mapo input source (e.g. bundled 'switch' cluster); group ${sb.groupId} skipped.",
                    )
                    return@mapNotNull null
                }
                ImportedPresetGroup(
                    inputSource = source,
                    sourceToken = sb.source,
                    state = when {
                        sb.modeshift -> "modeshift"
                        sb.active -> "active"
                        else -> "inactive"
                    },
                    group = translateGroup(group, warnings),
                )
            }
        }

        val sets = cfg.actionSets.mapIndexed { i, set ->
            val layers = cfg.actionLayers
                .filter { it.parentSetName == set.name }
                .map { layer ->
                    ImportedActionLayer(
                        name = layer.name,
                        title = layer.title ?: layer.name,
                        parentSetName = layer.parentSetName,
                        groups = groupsFor(layer.name),
                    )
                }
            ImportedActionSet(
                name = set.name,
                title = set.title ?: set.name,
                legacy = set.legacySet != "0",
                orderIndex = i,
                groups = groupsFor(set.name),
                layers = layers,
            )
        }

        return ImportedConfig(
            sourceControllerToken = cfg.controllerType,
            controllerType = VdfMappings.controllerType(cfg.controllerType),
            title = cfg.title ?: "Imported config",
            description = cfg.description,
            isLegacyRawBindings = cfg.isLegacyRawBindings,
            sets = sets,
            summary = summarize(sets),
            warnings = warnings,
        )
    }

    private fun translateGroup(group: VdfGroup, warnings: MutableList<ImportWarning>): ImportedGroup {
        val mode = VdfMappings.bindingMode(group.mode) ?: run {
            warnings += ImportWarning(
                ImportWarningKind.UNMAPPED_MODE,
                "Mode '${group.mode}' (group ${group.id}) has no Mapo equivalent; imported as None (silenced).",
            )
            BindingMode.NONE
        }
        if (group.settings.isNotEmpty()) {
            warnings += ImportWarning(
                ImportWarningKind.SETTINGS_NOT_TRANSLATED,
                "Group ${group.id} (${group.mode}) settings carried verbatim; schema-key translation is deferred.",
            )
        }
        val inputs =
            if (mode == BindingMode.TRIGGER) translateTriggerInputs(group, warnings)
            else group.inputs.map { translateInput(it, warnings) }
        return ImportedGroup(
            vdfId = group.id,
            mode = mode,
            modeToken = group.mode,
            name = group.name.orEmpty(),
            settingsJson = jsonObject(group.settings),
            inputs = inputs,
        )
    }

    /**
     * Trigger soft-pull unification (feedback_soft_press_unified_to_soft_pull):
     * VDF models soft-pull as a `Soft_Press` *activator* on the trigger's `click`
     * input. Mapo models it as a dedicated `soft_pull` *sub-input*. So a trigger
     * group's activators are re-homed: `Soft_Press` → the `soft_pull` sub-input as
     * a plain press (its "soft pull" meaning is now carried by the sub-input, not
     * the activator type); everything else → the `full_pull` sub-input.
     */
    private fun translateTriggerInputs(
        group: VdfGroup,
        warnings: MutableList<ImportWarning>,
    ): List<ImportedInput> {
        val fullPull = mutableListOf<ImportedActivator>()
        val softPull = mutableListOf<ImportedActivator>()
        for (input in group.inputs) {
            for (act in input.activators) {
                if (VdfMappings.activatorType(act.type) == ActivatorType.SOFT_PRESS) {
                    softPull += ImportedActivator(
                        type = ActivatorType.FULL_PRESS,
                        typeToken = act.type,
                        settingsJson = jsonObject(act.settings),
                        commands = act.bindings.map { translateCommand(it, warnings) },
                    )
                } else {
                    fullPull += translateActivator(act, warnings)
                }
            }
        }
        return buildList {
            if (fullPull.isNotEmpty()) add(ImportedInput("full_pull", "click", fullPull))
            if (softPull.isNotEmpty()) add(ImportedInput("soft_pull", "click", softPull))
        }
    }

    private fun translateInput(input: VdfGroupInput, warnings: MutableList<ImportWarning>): ImportedInput =
        ImportedInput(
            inputKey = VdfMappings.subInputKey(input.name),
            sourceTokenKey = input.name,
            activators = input.activators.map { translateActivator(it, warnings) },
        )

    private fun translateActivator(act: VdfActivator, warnings: MutableList<ImportWarning>): ImportedActivator {
        val type = VdfMappings.activatorType(act.type) ?: run {
            warnings += ImportWarning(
                ImportWarningKind.UNMAPPED_ACTIVATOR,
                "Activator '${act.type}' unrecognized; imported as Full Press.",
            )
            ActivatorType.FULL_PRESS
        }
        return ImportedActivator(
            type = type,
            typeToken = act.type,
            settingsJson = jsonObject(act.settings),
            commands = act.bindings.map { translateCommand(it, warnings) },
        )
    }

    /** Translates one VDF binding string into a Mapo command. */
    private fun translateCommand(binding: VdfBinding, warnings: MutableList<ImportWarning>): ImportedCommand {
        fun output(out: BindingOutput) = ImportedCommand.Output(out, binding.label.ifBlank { null }, binding.icon.ifBlank { null })

        return when (binding.verb) {
            "" -> output(BindingOutput.Unbound)
            "key_press" -> {
                val raw = binding.args.firstOrNull().orEmpty()
                val code = VdfMappings.keyCode(raw)
                if (code == null) warnings += ImportWarning(ImportWarningKind.UNMAPPED_KEY, "Key '$raw' has no Mapo code; kept verbatim.")
                output(BindingOutput.KeyPress(code ?: raw))
            }
            "xinput_button" -> {
                val raw = binding.args.firstOrNull().orEmpty()
                val btn = VdfMappings.xinputButton(raw)
                if (btn == null) warnings += ImportWarning(ImportWarningKind.UNMAPPED_BUTTON, "Gamepad button '$raw' has no Mapo mapping; kept verbatim.")
                output(BindingOutput.XInputButton(btn ?: raw))
            }
            "mouse_button" -> {
                val raw = binding.args.firstOrNull().orEmpty()
                val btn = VdfMappings.mouseButton(raw)
                if (btn == null) warnings += ImportWarning(ImportWarningKind.UNMAPPED_BUTTON, "Mouse button '$raw' has no Mapo mapping; kept verbatim.")
                output(BindingOutput.MouseButton(btn ?: raw))
            }
            "mouse_wheel" -> {
                val raw = binding.args.firstOrNull().orEmpty()
                val dir = VdfMappings.mouseWheel(raw)
                if (dir == null) warnings += ImportWarning(ImportWarningKind.UNMAPPED_BUTTON, "Mouse wheel '$raw' has no Mapo mapping; kept verbatim.")
                output(BindingOutput.MouseWheel(dir ?: raw))
            }
            "controller_action" -> {
                val verb = binding.args.firstOrNull().orEmpty()
                val rest = binding.args.drop(1)
                if (verb == "empty_binding") output(BindingOutput.Unbound)
                else output(BindingOutput.ControllerAction(verb, rest))
            }
            "game_action" -> {
                warnings += ImportWarning(
                    ImportWarningKind.GAME_ACTION_PLACEHOLDER,
                    "Unresolved game action: ${binding.args.joinToString(" ")}",
                )
                output(BindingOutput.GameAction(binding.args.getOrElse(0) { "" }, binding.args.getOrElse(1) { "" }))
            }
            "mode_shift" -> ImportedCommand.ModeShiftTrigger(
                ownerSourceToken = binding.args.getOrElse(0) { "" },
                targetVdfGroupId = binding.args.getOrElse(1) { "" },
            )
            else -> {
                warnings += ImportWarning(ImportWarningKind.UNMAPPED_VERB, "Binding verb '${binding.verb}' unsupported; dropped.")
                output(BindingOutput.Unbound)
            }
        }
    }

    private fun summarize(sets: List<ImportedActionSet>): ImportSummary {
        val allGroups = sets.flatMap { it.groups + it.layers.flatMap { l -> l.groups } }.map { it.group }
        val commands = allGroups.flatMap { g -> g.inputs.flatMap { it.activators.flatMap { a -> a.commands } } }
        val outputs = commands.filterIsInstance<ImportedCommand.Output>()
        val gameActions = outputs.count { it.output is BindingOutput.GameAction }
        return ImportSummary(
            actionSetCount = sets.size,
            actionLayerCount = sets.sumOf { it.layers.size },
            groupCount = allGroups.size,
            bindingCount = outputs.count { it.output !is BindingOutput.Unbound },
            gameActionPlaceholderCount = gameActions,
            modeShiftCount = commands.count { it is ImportedCommand.ModeShiftTrigger },
        )
    }

    /** Minimal JSON-object encoder for a flat string map — avoids an org.json
     *  dependency so the translator stays plain-JVM unit-testable. */
    private fun jsonObject(map: Map<String, String>): String {
        if (map.isEmpty()) return "{}"
        fun esc(s: String) = s.replace("\\", "\\\\").replace("\"", "\\\"")
        return map.entries.joinToString(prefix = "{", postfix = "}") { (k, v) -> "\"${esc(k)}\":\"${esc(v)}\"" }
    }
}

// ── Import model ─────────────────────────────────────────────────────────────

/** Root of a translated config: everything the persistence brick + summary dialog need. */
data class ImportedConfig(
    /** The VDF `controller_type` token (`controller_neptune`), preserved for re-import. */
    val sourceControllerToken: String?,
    val controllerType: ControllerType,
    val title: String,
    val description: String?,
    val isLegacyRawBindings: Boolean,
    val sets: List<ImportedActionSet>,
    val summary: ImportSummary,
    val warnings: List<ImportWarning>,
)

data class ImportedActionSet(
    val name: String,
    val title: String,
    val legacy: Boolean,
    val orderIndex: Int,
    val groups: List<ImportedPresetGroup>,
    val layers: List<ImportedActionLayer>,
)

data class ImportedActionLayer(
    val name: String,
    val title: String,
    val parentSetName: String?,
    val groups: List<ImportedPresetGroup>,
)

/** A [BindingGroup] paired with the source + state it binds to (the future PresetBinding). */
data class ImportedPresetGroup(
    val inputSource: InputSource,
    val sourceToken: String,
    /** "active" / "inactive" / "modeshift" (Mapo PresetBinding.state). */
    val state: String,
    val group: ImportedGroup,
)

data class ImportedGroup(
    val vdfId: String?,
    val mode: BindingMode,
    val modeToken: String?,
    val name: String,
    /** VDF settings carried verbatim as JSON; schema-key translation deferred. */
    val settingsJson: String,
    val inputs: List<ImportedInput>,
)

data class ImportedInput(
    /** Mapo sub-input key (e.g. `dpad_up`, `full_pull`). */
    val inputKey: String,
    /** Original VDF input name, for diagnostics / re-export. */
    val sourceTokenKey: String,
    val activators: List<ImportedActivator>,
)

data class ImportedActivator(
    val type: ActivatorType,
    val typeToken: String,
    val settingsJson: String,
    val commands: List<ImportedCommand>,
)

/** A command an activator emits. Either a real output, or a mode-shift trigger
 *  (which is NOT a [BindingOutput] — it becomes a `SourceModeShift` at persist time;
 *  see project_mode_shift_per_source_architecture). */
sealed interface ImportedCommand {
    data class Output(val output: BindingOutput, val label: String?, val icon: String?) : ImportedCommand
    data class ModeShiftTrigger(val ownerSourceToken: String, val targetVdfGroupId: String) : ImportedCommand
}

/** Counts for the pre-import confirmation dialog. */
data class ImportSummary(
    val actionSetCount: Int,
    val actionLayerCount: Int,
    val groupCount: Int,
    /** Concrete (non-Unbound) command outputs. */
    val bindingCount: Int,
    val gameActionPlaceholderCount: Int,
    val modeShiftCount: Int,
)

data class ImportWarning(val kind: ImportWarningKind, val detail: String)

enum class ImportWarningKind {
    ACTION_BASED_CONFIG,
    UNMAPPED_MODE,
    UNMAPPED_SOURCE,
    UNMAPPED_ACTIVATOR,
    UNMAPPED_KEY,
    UNMAPPED_BUTTON,
    UNMAPPED_VERB,
    GAME_ACTION_PLACEHOLDER,
    DANGLING_GROUP_REF,
    SETTINGS_NOT_TRANSLATED,
}
