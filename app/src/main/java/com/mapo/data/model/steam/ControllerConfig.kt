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
    /**
     * The controller_profile's persisted default set, or the first by orderIndex if
     * the default pointer is null or stale. "Active" here means *default-active at
     * load time*; runtime set-switching (Brick 4.2) is layered on top of this in the
     * evaluator, not in the materialized graph.
     */
    val activeActionSet: ActionSetGraph?
        get() {
            val explicit = controllerProfile.defaultActionSetId
                ?.let { id -> actionSets.firstOrNull { it.actionSet.id == id } }
            return explicit ?: actionSets.firstOrNull()
        }
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
 * Resolve `(input source, sub-input, activator type)` on the action set selected by
 * [setId] (falling back to [activeActionSet] when [setId] is null or stale) to a
 * specific [ActivatorGraph]. Returns null if any step doesn't exist.
 */
fun ControllerConfig.findActivator(
    inputSource: InputSource,
    inputKey: String,
    activatorType: ActivatorType = ActivatorType.FULL_PRESS,
    setId: Long? = null,
): ActivatorGraph? = resolveActionSet(setId)
    ?.presetFor(inputSource)
    ?.group
    ?.inputByKey(inputKey)
    ?.firstActivatorOfType(activatorType)

/**
 * Resolve `(input source, sub-input)` on the action set selected by [setId] (falling
 * back to [activeActionSet] when [setId] is null or stale) to its [GroupInputGraph].
 * Carries every activator configured on that input — used by the per-input editor where
 * the user sees the full activator list, not just one type.
 */
fun ControllerConfig.findGroupInput(
    inputSource: InputSource,
    inputKey: String,
    setId: Long? = null,
): GroupInputGraph? = resolveActionSet(setId)
    ?.presetFor(inputSource)
    ?.group
    ?.inputByKey(inputKey)

/**
 * Resolve [setId] to its [ActionSetGraph], falling back to [ControllerConfig.activeActionSet]
 * when the id is null or no set in the graph carries it. Centralized so every editor screen
 * picks the same set when wired with the same viewing pointer.
 */
fun ControllerConfig.resolveActionSet(setId: Long?): ActionSetGraph? =
    setId?.let { id -> actionSets.firstOrNull { it.actionSet.id == id } } ?: activeActionSet
