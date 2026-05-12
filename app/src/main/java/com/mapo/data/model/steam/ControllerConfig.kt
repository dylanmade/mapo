package com.mapo.data.model.steam

/**
 * The materialized binding graph for a [ControllerProfile] — what
 * `ControllerConfigRepository.observeActiveConfig` emits.
 *
 * Hierarchical by design so the UI can traverse it naturally
 * (controller → sets → preset bindings → groups → inputs → activators → bindings).
 * A flatter form (`CompiledConfig`) for the runtime evaluator lands in Phase 2.
 */
data class ControllerConfig(
    val controllerProfile: ControllerProfile,
    val actionSets: List<ActionSetGraph>,
) {
    /** First action set is the default-active set in legacy configs. */
    val activeActionSet: ActionSetGraph? get() = actionSets.firstOrNull()
}

data class ActionSetGraph(
    val actionSet: ActionSet,
    val layers: List<ActionLayerGraph>,
    val preset: List<PresetEntry>,
) {
    /** Lookup helper: the active-state preset entry for a given input source. */
    fun presetFor(inputSource: InputSource, state: String = "active"): PresetEntry? =
        preset.firstOrNull { it.inputSource == inputSource && it.state == state }
}

data class ActionLayerGraph(
    val layer: ActionLayer,
    val bindingGroups: List<BindingGroupGraph>,
)

data class PresetEntry(
    val inputSource: InputSource,
    val state: String,
    val group: BindingGroupGraph,
)

data class BindingGroupGraph(
    val group: BindingGroup,
    val inputs: List<GroupInputGraph>,
) {
    fun inputByKey(inputKey: String): GroupInputGraph? =
        inputs.firstOrNull { it.input.inputKey == inputKey }
}

data class GroupInputGraph(
    val input: GroupInput,
    val activators: List<ActivatorGraph>,
) {
    fun firstActivatorOfType(type: ActivatorType): ActivatorGraph? =
        activators.firstOrNull { it.activator.type == type }
}

data class ActivatorGraph(
    val activator: Activator,
    val bindings: List<Binding>,
) {
    /**
     * For Phase 1/2 single-output activators, the first binding is the effective output.
     * `cycle_binding` (multi-binding activators) arrives in Phase 3.
     */
    val primaryOutput: BindingOutput
        get() = bindings.firstOrNull()
            ?.let { BindingOutput.fromEntity(it.outputType, it.args) }
            ?: BindingOutput.Unbound
}

/**
 * Resolve `(input source, sub-input, activator type)` on the active action set to a
 * specific [ActivatorGraph]. Returns null if any step doesn't exist (e.g. the seed
 * didn't create that group, or the activator type isn't attached).
 */
fun ControllerConfig.findActivator(
    inputSource: InputSource,
    inputKey: String,
    activatorType: ActivatorType = ActivatorType.FULL_PRESS,
): ActivatorGraph? = activeActionSet
    ?.presetFor(inputSource)
    ?.group
    ?.inputByKey(inputKey)
    ?.firstActivatorOfType(activatorType)
