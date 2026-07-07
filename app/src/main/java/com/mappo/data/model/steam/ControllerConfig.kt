package com.mappo.data.model.steam

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
     * The starting set when the controller_profile is loaded at runtime: the first
     * [ActionSetGraph] by ordering. Steam has no exposed "default" concept; switching
     * is purely runtime via `CHANGE_PRESET` bindings (Brick 4.2), which the evaluator
     * tracks separately from this materialized graph.
     */
    val activeActionSet: ActionSetGraph?
        get() = actionSets.firstOrNull()
}

data class ActionSetGraph(
    val actionSet: ActionSet,
    val layers: List<ActionLayerGraph>,
    val preset: List<PresetEntry>,
    /**
     * Phase 7 Brick B.5 — mode shifts owned by this action set. Each entry is
     * a while-held override on its [SourceModeShiftGraph.shift]'s
     * `ownerSource`; the trigger is `(triggerSource, triggerSubInput)`.
     * Ordered by `displayOrder` (set in the DAO query). Set-owned shifts are
     * always available while the action set is active.
     */
    val modeShifts: List<SourceModeShiftGraph> = emptyList(),
) {
    /** Lookup helper: the active-state preset entry for a given input source. */
    fun presetFor(inputSource: InputSource, state: String = "active"): PresetEntry? =
        preset.firstOrNull { it.inputSource == inputSource && it.state == state }
}

data class ActionLayerGraph(
    val layer: ActionLayer,
    val bindingGroups: List<BindingGroupGraph>,
    val preset: List<PresetEntry> = emptyList(),
    /**
     * Phase 7 Brick B.5 — mode shifts owned by this action layer. Only active
     * while this layer is in the active stack. Same shape + ordering as
     * [ActionSetGraph.modeShifts].
     */
    val modeShifts: List<SourceModeShiftGraph> = emptyList(),
) {
    /** Lookup helper parallel to [ActionSetGraph.presetFor]. */
    fun presetFor(inputSource: InputSource, state: String = "active"): PresetEntry? =
        preset.firstOrNull { it.inputSource == inputSource && it.state == state }
}

/**
 * Phase 7 Brick B.5 — a [SourceModeShift] paired with its resolved target
 * binding group. The shift's `bindingGroupId` is the FK; [group] is that
 * group materialized with its full inputs/activators/bindings tree so the
 * editor UI and the compile path can render and walk it without an extra
 * lookup.
 */
data class SourceModeShiftGraph(
    val shift: SourceModeShift,
    val group: BindingGroupGraph,
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
    layerId: Long? = null,
    modeShiftId: Long? = null,
): GroupInputGraph? {
    val set = resolveActionSet(setId) ?: return null
    // Phase 7 Brick B.6: mode-shift target groups are looked up via [modeShiftId]
    // rather than the source preset map. The shift's target group can be set- or
    // layer-owned; check both. The [inputSource] arg is the shift's ownerSource —
    // useful for validation but not directly used in the lookup.
    if (modeShiftId != null) {
        set.modeShifts.firstOrNull { it.shift.id == modeShiftId }
            ?.group?.inputByKey(inputKey)?.let { return it }
        for (layer in set.layers) {
            layer.modeShifts.firstOrNull { it.shift.id == modeShiftId }
                ?.group?.inputByKey(inputKey)?.let { return it }
        }
        return null
    }
    // Brick 5.5.c: when [layerId] is non-null and resolves to a layer in this set,
    // the editor is in overlay mode — read from the layer's preset (which only carries
    // input sources the user has overridden). Fall back to the base set's preset only
    // when the layer has nothing for this source — but in that scenario the screen
    // shouldn't be open against a layer that has no override (5.5.c materializes
    // eagerly on tap). The fall-through preserves correctness for stale-pointer cases.
    if (layerId != null) {
        val layer = set.layers.firstOrNull { it.layer.id == layerId }
        layer?.presetFor(inputSource)?.group?.inputByKey(inputKey)?.let { return it }
    }
    return set.presetFor(inputSource)?.group?.inputByKey(inputKey)
}

/**
 * Resolve [setId] to its [ActionSetGraph], falling back to [ControllerConfig.activeActionSet]
 * when the id is null or no set in the graph carries it. Centralized so every editor screen
 * picks the same set when wired with the same viewing pointer.
 */
fun ControllerConfig.resolveActionSet(setId: Long?): ActionSetGraph? =
    setId?.let { id -> actionSets.firstOrNull { it.actionSet.id == id } } ?: activeActionSet
