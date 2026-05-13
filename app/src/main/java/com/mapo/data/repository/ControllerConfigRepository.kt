package com.mapo.data.repository

import com.mapo.data.db.steam.ActionLayerDao
import com.mapo.data.db.steam.ActionSetDao
import com.mapo.data.db.steam.ActivatorDao
import com.mapo.data.db.steam.BindingDao
import com.mapo.data.db.steam.BindingGroupDao
import com.mapo.data.db.steam.ControllerProfileDao
import com.mapo.data.db.steam.GameActionDao
import com.mapo.data.db.steam.GroupInputDao
import com.mapo.data.db.steam.PresetBindingDao
import com.mapo.data.model.steam.ActionLayerGraph
import com.mapo.data.model.steam.ActionSet
import com.mapo.data.model.steam.ActionSetGraph
import com.mapo.data.model.steam.Activator
import com.mapo.data.model.steam.ActivatorGraph
import com.mapo.data.model.steam.ActivatorType
import com.mapo.data.model.steam.Binding
import com.mapo.data.model.steam.BindingGroup
import com.mapo.data.model.steam.BindingGroupGraph
import com.mapo.data.model.steam.BindingMode
import com.mapo.data.model.steam.BindingOutput
import com.mapo.data.model.steam.BindingOutputType
import com.mapo.data.model.steam.ControllerConfig
import com.mapo.data.model.steam.ControllerProfile
import com.mapo.data.model.steam.ControllerType
import com.mapo.data.model.steam.GroupInput
import com.mapo.data.model.steam.GroupInputGraph
import com.mapo.data.model.steam.InputSource
import com.mapo.data.model.steam.PresetBinding
import com.mapo.data.model.steam.PresetEntry
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Reads + mutates the Steam-Input-style binding graph for a given [com.mapo.data.model.Profile].
 *
 * Lifecycle:
 *  - [observeActiveConfig] auto-seeds a default config if none exists, so the UI
 *    can subscribe without separately calling [ensureSeeded].
 *  - Mutations bump [configDirtyTick] so observers re-load. We don't observe every
 *    DAO Flow individually because the materialized graph spans 7 tables;
 *    re-loading on a tick is simpler and the snapshot is cheap to recompute.
 *
 * Clone invariant (per feedback_duplicates_own_their_data): every seeded child
 * gets a fresh auto-generated PK from Room — we never copy ids from a template.
 * When duplicate-action-set lands in Phase 4, it must follow the same rule.
 */
@Singleton
class ControllerConfigRepository @Inject constructor(
    private val controllerProfileDao: ControllerProfileDao,
    private val actionSetDao: ActionSetDao,
    private val actionLayerDao: ActionLayerDao,
    private val gameActionDao: GameActionDao,
    private val bindingGroupDao: BindingGroupDao,
    private val groupInputDao: GroupInputDao,
    private val activatorDao: ActivatorDao,
    private val bindingDao: BindingDao,
    private val presetBindingDao: PresetBindingDao,
) {

    private val configDirtyTick = MutableStateFlow(0L)

    /**
     * Ensures the given profile has a [ControllerProfile]. Returns the active
     * controller_profile's id (either pre-existing or newly seeded).
     */
    suspend fun ensureSeeded(profileId: Long): Long {
        controllerProfileDao.getByProfile(profileId).firstOrNull()?.let { return it.id }
        return seedDefaultConfig(profileId)
    }

    /**
     * Seeds a default Steam-Input-style config under [profileId]:
     * one Generic Android controller_profile, one "Default" action_set,
     * one binding_group per default input source, default activators (FULL_PRESS)
     * with [BindingOutputType.UNBOUND] bindings.
     *
     * Caller is responsible for ensuring no controller_profile exists for [profileId]
     * already; otherwise this creates a second one.
     */
    suspend fun seedDefaultConfig(profileId: Long): Long {
        val controllerProfileId = controllerProfileDao.insert(
            ControllerProfile(
                profileId = profileId,
                controllerType = ControllerType.GENERIC_ANDROID,
                name = "Default",
                legacySet = true,
            )
        )

        val actionSetId = actionSetDao.insert(
            ActionSet(
                controllerProfileId = controllerProfileId,
                name = "default",
                title = "Default",
                legacy = true,
                orderIndex = 0,
            )
        )
        seedDefaultSetContents(actionSetId)

        controllerProfileDao.getById(controllerProfileId)?.let { cp ->
            controllerProfileDao.update(cp.copy(defaultActionSetId = actionSetId))
        }

        configDirtyTick.value = configDirtyTick.value + 1
        return controllerProfileId
    }

    /**
     * Populate [actionSetId] with one [BindingGroup] per [DEFAULT_INPUT_SOURCE_SEEDS]
     * entry, each carrying a default FULL_PRESS activator with a single Unbound
     * binding, plus the matching `active`-state [PresetBinding]. Shared by
     * [seedDefaultConfig] and [addActionSet] when no inherit-from set is supplied.
     */
    private suspend fun seedDefaultSetContents(actionSetId: Long) {
        for ((inputSource, spec) in DEFAULT_INPUT_SOURCE_SEEDS) {
            val groupId = bindingGroupDao.insert(
                BindingGroup(
                    actionSetId = actionSetId,
                    actionLayerId = null,
                    name = spec.groupName,
                    mode = spec.mode,
                    settingsJson = "{}",
                )
            )

            for ((idx, inputKey) in spec.inputKeys.withIndex()) {
                val groupInputId = groupInputDao.insert(
                    GroupInput(
                        bindingGroupId = groupId,
                        inputKey = inputKey,
                        orderIndex = idx,
                    )
                )
                val activatorId = activatorDao.insert(
                    Activator(
                        groupInputId = groupInputId,
                        type = ActivatorType.FULL_PRESS,
                        settingsJson = "{}",
                        orderIndex = 0,
                    )
                )
                bindingDao.insert(
                    Binding(
                        activatorId = activatorId,
                        outputType = BindingOutputType.UNBOUND,
                        args = "",
                        orderIndex = 0,
                    )
                )
            }

            presetBindingDao.insert(
                PresetBinding(
                    actionSetId = actionSetId,
                    inputSource = inputSource,
                    state = "active",
                    bindingGroupId = groupId,
                )
            )
        }
    }

    /**
     * Deep-clone every group / input / activator / binding / preset / layer
     * under [sourceSetId] into [destSetId], using a fresh autogenerated id for
     * every new row (per feedback_duplicates_own_their_data). The destination
     * set is assumed to already exist and be empty.
     */
    private suspend fun cloneSetContents(sourceSetId: Long, destSetId: Long) {
        val sourceLayers = actionLayerDao.getByActionSets(listOf(sourceSetId))
        val layerIdMap = HashMap<Long, Long>(sourceLayers.size)
        for (sourceLayer in sourceLayers) {
            val newLayerId = actionLayerDao.insert(
                sourceLayer.copy(id = 0, parentActionSetId = destSetId)
            )
            layerIdMap[sourceLayer.id] = newLayerId
        }

        for (sourceGameAction in gameActionDao.getByActionSets(listOf(sourceSetId))) {
            gameActionDao.insert(sourceGameAction.copy(id = 0, actionSetId = destSetId))
        }

        val groupsUnderSet = bindingGroupDao.getByActionSets(listOf(sourceSetId))
        val groupsUnderLayers = if (sourceLayers.isNotEmpty()) {
            bindingGroupDao.getByActionLayers(sourceLayers.map { it.id })
        } else emptyList()
        val groupIdMap = HashMap<Long, Long>(groupsUnderSet.size + groupsUnderLayers.size)
        for (sourceGroup in groupsUnderSet + groupsUnderLayers) {
            val newGroupId = bindingGroupDao.insert(
                sourceGroup.copy(
                    id = 0,
                    actionSetId = sourceGroup.actionSetId?.let { destSetId },
                    actionLayerId = sourceGroup.actionLayerId?.let(layerIdMap::getValue),
                )
            )
            groupIdMap[sourceGroup.id] = newGroupId
        }

        val allGroupIds = (groupsUnderSet + groupsUnderLayers).map { it.id }
        val sourceInputs = if (allGroupIds.isNotEmpty()) {
            groupInputDao.getByGroups(allGroupIds)
        } else emptyList()
        val inputIdMap = HashMap<Long, Long>(sourceInputs.size)
        for (sourceInput in sourceInputs) {
            val newInputId = groupInputDao.insert(
                sourceInput.copy(
                    id = 0,
                    bindingGroupId = groupIdMap.getValue(sourceInput.bindingGroupId),
                )
            )
            inputIdMap[sourceInput.id] = newInputId
        }

        val sourceActivators = if (sourceInputs.isNotEmpty()) {
            activatorDao.getByGroupInputs(sourceInputs.map { it.id })
        } else emptyList()
        val activatorIdMap = HashMap<Long, Long>(sourceActivators.size)
        for (sourceActivator in sourceActivators) {
            val newActivatorId = activatorDao.insert(
                sourceActivator.copy(
                    id = 0,
                    groupInputId = inputIdMap.getValue(sourceActivator.groupInputId),
                )
            )
            activatorIdMap[sourceActivator.id] = newActivatorId
        }

        if (sourceActivators.isNotEmpty()) {
            for (sourceBinding in bindingDao.getByActivators(sourceActivators.map { it.id })) {
                bindingDao.insert(
                    sourceBinding.copy(
                        id = 0,
                        activatorId = activatorIdMap.getValue(sourceBinding.activatorId),
                    )
                )
            }
        }

        for (sourcePreset in presetBindingDao.getByActionSets(listOf(sourceSetId))) {
            presetBindingDao.insert(
                sourcePreset.copy(
                    id = 0,
                    actionSetId = destSetId,
                    bindingGroupId = groupIdMap.getValue(sourcePreset.bindingGroupId),
                )
            )
        }
    }

    /**
     * Append a new [ActionSet] to [controllerProfileId]. When [inheritFromSetId] is
     * null the new set is seeded with the same default groups/inputs/activators as
     * [seedDefaultConfig]; when non-null the named source set is deep-cloned into
     * the new set (groups, inputs, activators, bindings, preset_bindings, layers).
     * Returns the new set id.
     */
    suspend fun addActionSet(
        controllerProfileId: Long,
        name: String,
        title: String,
        inheritFromSetId: Long? = null,
    ): Long {
        val existing = actionSetDao.getByControllerProfile(controllerProfileId)
        val nextOrder = (existing.maxOfOrNull { it.orderIndex } ?: -1) + 1
        val newSetId = actionSetDao.insert(
            ActionSet(
                controllerProfileId = controllerProfileId,
                name = name,
                title = title,
                legacy = true,
                orderIndex = nextOrder,
            )
        )
        if (inheritFromSetId != null) {
            cloneSetContents(inheritFromSetId, newSetId)
        } else {
            seedDefaultSetContents(newSetId)
        }
        configDirtyTick.value = configDirtyTick.value + 1
        return newSetId
    }

    /** Update [actionSetId]'s human-facing [name] / [title]. Other fields are unaffected. */
    suspend fun renameActionSet(actionSetId: Long, name: String, title: String) {
        val existing = actionSetDao.getById(actionSetId) ?: return
        if (existing.name == name && existing.title == title) return
        actionSetDao.update(existing.copy(name = name, title = title))
        configDirtyTick.value = configDirtyTick.value + 1
    }

    /**
     * Deep-clone [sourceSetId] under the same controller_profile with [name] / [title].
     * Returns the new set id. Convenience wrapper around [addActionSet] with
     * `inheritFromSetId = sourceSetId`.
     */
    suspend fun duplicateActionSet(sourceSetId: Long, name: String, title: String): Long {
        val source = actionSetDao.getById(sourceSetId)
            ?: error("Source action set $sourceSetId not found")
        return addActionSet(source.controllerProfileId, name, title, inheritFromSetId = sourceSetId)
    }

    /**
     * Delete [actionSetId]. Guards against deleting the last remaining set under a
     * controller_profile (returns false). When the deleted set was the controller_profile's
     * default, the default is reassigned to the first remaining set by orderIndex.
     * Returns true on successful deletion.
     */
    suspend fun deleteActionSet(actionSetId: Long): Boolean {
        val existing = actionSetDao.getById(actionSetId) ?: return false
        val siblings = actionSetDao.getByControllerProfile(existing.controllerProfileId)
        if (siblings.size <= 1) return false

        val cp = controllerProfileDao.getById(existing.controllerProfileId)
        actionSetDao.deleteById(actionSetId)

        if (cp != null && cp.defaultActionSetId == actionSetId) {
            val remaining = actionSetDao.getByControllerProfile(existing.controllerProfileId)
            controllerProfileDao.update(cp.copy(defaultActionSetId = remaining.firstOrNull()?.id))
        }
        configDirtyTick.value = configDirtyTick.value + 1
        return true
    }

    /**
     * Mark [actionSetId] as the controller_profile's default (the set that becomes
     * active when the profile is loaded at runtime). No-op if the set doesn't belong
     * to [controllerProfileId].
     */
    suspend fun setDefaultActionSet(controllerProfileId: Long, actionSetId: Long) {
        val cp = controllerProfileDao.getById(controllerProfileId) ?: return
        if (cp.defaultActionSetId == actionSetId) return
        val set = actionSetDao.getById(actionSetId) ?: return
        if (set.controllerProfileId != controllerProfileId) return
        controllerProfileDao.update(cp.copy(defaultActionSetId = actionSetId))
        configDirtyTick.value = configDirtyTick.value + 1
    }

    /**
     * Observe the active config for [profileId]. Auto-seeds if none exists.
     * Emits null only while seeding is in flight on first subscription
     * (transient — the next emission carries the seeded config).
     */
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    fun observeActiveConfig(profileId: Long): Flow<ControllerConfig?> = flow {
        ensureSeeded(profileId)
        emitAll(
            combine(
                controllerProfileDao.observeByProfile(profileId)
                    .map { profiles -> profiles.firstOrNull() }
                    .distinctUntilChanged { a, b -> a?.id == b?.id },
                configDirtyTick,
            ) { activeProfile, _ -> activeProfile }
                .flatMapLatest { activeProfile ->
                    if (activeProfile == null) flowOf<ControllerConfig?>(null)
                    else flow { emit(loadConfigSnapshot(activeProfile)) }
                }
        )
    }

    /** One-shot read of the active config, or null if none exists yet. */
    suspend fun getActiveConfigOnce(profileId: Long): ControllerConfig? {
        val cp = controllerProfileDao.getByProfile(profileId).firstOrNull() ?: return null
        return loadConfigSnapshot(cp)
    }

    /**
     * Deep-clone every [ControllerProfile] under [sourceProfileId] into [destProfileId].
     * No-op when the source has no controller_profile (e.g., fresh profile that hasn't been
     * observed yet — the dest will auto-seed on first observation, same as the source would).
     *
     * Per feedback_duplicates_own_their_data, each cloned row gets a fresh autogenerated id
     * via `.copy(id = 0)` so the duplicate is independently addressable.
     */
    suspend fun copyConfig(sourceProfileId: Long, destProfileId: Long) {
        val sourceControllerProfiles = controllerProfileDao.getByProfile(sourceProfileId)
        if (sourceControllerProfiles.isEmpty()) return

        for (sourceCp in sourceControllerProfiles) {
            val newCpId = controllerProfileDao.insert(
                sourceCp.copy(id = 0, profileId = destProfileId)
            )

            val sourceSets = actionSetDao.getByControllerProfile(sourceCp.id)
            if (sourceSets.isEmpty()) continue
            val setIdMap = HashMap<Long, Long>(sourceSets.size)
            for (sourceSet in sourceSets) {
                val newSetId = actionSetDao.insert(
                    sourceSet.copy(id = 0, controllerProfileId = newCpId)
                )
                setIdMap[sourceSet.id] = newSetId
            }

            val sourceSetIds = sourceSets.map { it.id }

            for (sourceGameAction in gameActionDao.getByActionSets(sourceSetIds)) {
                gameActionDao.insert(
                    sourceGameAction.copy(
                        id = 0,
                        actionSetId = setIdMap.getValue(sourceGameAction.actionSetId),
                    )
                )
            }

            val sourceLayers = actionLayerDao.getByActionSets(sourceSetIds)
            val layerIdMap = HashMap<Long, Long>(sourceLayers.size)
            for (sourceLayer in sourceLayers) {
                val newLayerId = actionLayerDao.insert(
                    sourceLayer.copy(
                        id = 0,
                        parentActionSetId = setIdMap.getValue(sourceLayer.parentActionSetId),
                    )
                )
                layerIdMap[sourceLayer.id] = newLayerId
            }

            val groupsUnderSets = bindingGroupDao.getByActionSets(sourceSetIds)
            val groupsUnderLayers = if (sourceLayers.isNotEmpty()) {
                bindingGroupDao.getByActionLayers(sourceLayers.map { it.id })
            } else emptyList()
            val groupIdMap = HashMap<Long, Long>(groupsUnderSets.size + groupsUnderLayers.size)
            for (sourceGroup in groupsUnderSets + groupsUnderLayers) {
                val newGroupId = bindingGroupDao.insert(
                    sourceGroup.copy(
                        id = 0,
                        actionSetId = sourceGroup.actionSetId?.let(setIdMap::getValue),
                        actionLayerId = sourceGroup.actionLayerId?.let(layerIdMap::getValue),
                    )
                )
                groupIdMap[sourceGroup.id] = newGroupId
            }

            val allGroupIds = (groupsUnderSets + groupsUnderLayers).map { it.id }
            val sourceInputs = if (allGroupIds.isNotEmpty()) {
                groupInputDao.getByGroups(allGroupIds)
            } else emptyList()
            val inputIdMap = HashMap<Long, Long>(sourceInputs.size)
            for (sourceInput in sourceInputs) {
                val newInputId = groupInputDao.insert(
                    sourceInput.copy(
                        id = 0,
                        bindingGroupId = groupIdMap.getValue(sourceInput.bindingGroupId),
                    )
                )
                inputIdMap[sourceInput.id] = newInputId
            }

            val sourceActivators = if (sourceInputs.isNotEmpty()) {
                activatorDao.getByGroupInputs(sourceInputs.map { it.id })
            } else emptyList()
            val activatorIdMap = HashMap<Long, Long>(sourceActivators.size)
            for (sourceActivator in sourceActivators) {
                val newActivatorId = activatorDao.insert(
                    sourceActivator.copy(
                        id = 0,
                        groupInputId = inputIdMap.getValue(sourceActivator.groupInputId),
                    )
                )
                activatorIdMap[sourceActivator.id] = newActivatorId
            }

            if (sourceActivators.isNotEmpty()) {
                for (sourceBinding in bindingDao.getByActivators(sourceActivators.map { it.id })) {
                    bindingDao.insert(
                        sourceBinding.copy(
                            id = 0,
                            activatorId = activatorIdMap.getValue(sourceBinding.activatorId),
                        )
                    )
                }
            }

            for (sourcePreset in presetBindingDao.getByActionSets(sourceSetIds)) {
                presetBindingDao.insert(
                    sourcePreset.copy(
                        id = 0,
                        actionSetId = setIdMap.getValue(sourcePreset.actionSetId),
                        bindingGroupId = groupIdMap.getValue(sourcePreset.bindingGroupId),
                    )
                )
            }

            // Re-point the cloned controller_profile's defaultActionSetId at the
            // cloned set (it currently still references the source set's id from
            // the initial `.copy(id = 0, profileId = destProfileId)` above).
            val remappedDefault = sourceCp.defaultActionSetId?.let(setIdMap::get)
            if (remappedDefault != sourceCp.defaultActionSetId) {
                controllerProfileDao.getById(newCpId)?.let { newCp ->
                    controllerProfileDao.update(newCp.copy(defaultActionSetId = remappedDefault))
                }
            }
        }

        configDirtyTick.value = configDirtyTick.value + 1
    }

    /**
     * Insert a new [Activator] of [type] on [groupInputId], seeded with one Unbound
     * binding so the activator is immediately editable in the UI. Returns the new id.
     *
     * Order is appended to the end of the group_input's existing activators.
     * Multi-binding (cycle_binding) is Brick 3.3 territory — for now every activator
     * carries one binding.
     */
    suspend fun addActivator(groupInputId: Long, type: ActivatorType): Long {
        val existing = activatorDao.getByGroupInputs(listOf(groupInputId))
        val nextOrder = (existing.maxOfOrNull { it.orderIndex } ?: -1) + 1
        val newActivatorId = activatorDao.insert(
            Activator(
                groupInputId = groupInputId,
                type = type,
                settingsJson = "{}",
                orderIndex = nextOrder,
            )
        )
        bindingDao.insert(
            Binding(
                activatorId = newActivatorId,
                outputType = BindingOutputType.UNBOUND,
                args = "",
                orderIndex = 0,
            )
        )
        configDirtyTick.value = configDirtyTick.value + 1
        return newActivatorId
    }

    /** Delete [activatorId]. Bindings cascade-delete via the schema foreign key. */
    suspend fun removeActivator(activatorId: Long) {
        activatorDao.deleteById(activatorId)
        configDirtyTick.value = configDirtyTick.value + 1
    }

    /**
     * Change [activatorId]'s [ActivatorType] without disturbing its bindings or
     * settingsJson. Type-specific settings that no longer apply stay in the JSON blob
     * for forward-compat — the evaluator only reads fields its current type cares about.
     */
    suspend fun updateActivatorType(activatorId: Long, type: ActivatorType) {
        val existing = activatorDao.getById(activatorId) ?: return
        if (existing.type == type) return
        activatorDao.update(existing.copy(type = type))
        configDirtyTick.value = configDirtyTick.value + 1
    }

    /**
     * Replace [activatorId]'s settingsJson with [settingsJson] verbatim. Called from the
     * activator settings editor; the editor builds the JSON via
     * `CompiledActivatorSettings.toJson()` so the keys stay in sync with the parser.
     */
    suspend fun updateActivatorSettings(activatorId: Long, settingsJson: String) {
        val existing = activatorDao.getById(activatorId) ?: return
        if (existing.settingsJson == settingsJson) return
        activatorDao.update(existing.copy(settingsJson = settingsJson))
        configDirtyTick.value = configDirtyTick.value + 1
    }

    /**
     * Replace the bindings on [activatorId] with a single binding from [output]. Convenience
     * for the legacy single-command-per-activator path; still used by callers that haven't
     * been migrated to the multi-command UI. Prefer [setCommand] when you have a
     * specific bindingId.
     */
    suspend fun setBinding(activatorId: Long, output: BindingOutput) {
        bindingDao.deleteByActivator(activatorId)
        val (type, args) = output.toEntity()
        bindingDao.insert(
            Binding(
                activatorId = activatorId,
                outputType = type,
                args = args,
                orderIndex = 0,
            )
        )
        configDirtyTick.value = configDirtyTick.value + 1
    }

    /**
     * Update a specific [Binding] row in place. Used by the multi-command UI (Brick 3.6):
     * each command row carries its bindingId, so the picker writes through to that exact
     * row instead of replacing all bindings on the activator.
     */
    suspend fun setCommand(bindingId: Long, output: BindingOutput) {
        val existing = bindingDao.getById(bindingId) ?: return
        val (type, args) = output.toEntity()
        if (existing.outputType == type && existing.args == args) return
        bindingDao.update(existing.copy(outputType = type, args = args))
        configDirtyTick.value = configDirtyTick.value + 1
    }

    /**
     * Append a new command (Binding row) to [activatorId] at the next orderIndex. The new
     * command is created as Unbound — the user picks its output via the standard picker
     * flow on the freshly-added row. Returns the new bindingId so the UI can scroll to /
     * focus the new row if desired.
     */
    suspend fun addCommand(activatorId: Long): Long {
        val existing = bindingDao.getByActivators(listOf(activatorId))
        val nextOrder = (existing.maxOfOrNull { it.orderIndex } ?: -1) + 1
        val newId = bindingDao.insert(
            Binding(
                activatorId = activatorId,
                outputType = BindingOutputType.UNBOUND,
                args = "",
                orderIndex = nextOrder,
            )
        )
        configDirtyTick.value = configDirtyTick.value + 1
        return newId
    }

    /**
     * Delete a specific command (Binding row). The UI guards against removing the last
     * command on an activator — at least one binding per activator is an invariant the
     * `primaryOutput` accessor and `addActivator` setup both assume.
     */
    suspend fun removeCommand(bindingId: Long) {
        bindingDao.deleteById(bindingId)
        configDirtyTick.value = configDirtyTick.value + 1
    }

    private suspend fun loadConfigSnapshot(controllerProfile: ControllerProfile): ControllerConfig {
        val sets = actionSetDao.getByControllerProfile(controllerProfile.id)
        if (sets.isEmpty()) return ControllerConfig(controllerProfile, emptyList())

        val setIds = sets.map { it.id }
        val layersByActionSet = actionLayerDao.getByActionSets(setIds).groupBy { it.parentActionSetId }
        val presetsByActionSet = presetBindingDao.getByActionSets(setIds).groupBy { it.actionSetId }

        val layerIds = layersByActionSet.values.flatten().map { it.id }
        val groupsByActionSet = bindingGroupDao.getByActionSets(setIds).groupBy { it.actionSetId!! }
        val groupsByActionLayer = if (layerIds.isNotEmpty()) {
            bindingGroupDao.getByActionLayers(layerIds).groupBy { it.actionLayerId!! }
        } else emptyMap()

        val allGroups = groupsByActionSet.values.flatten() + groupsByActionLayer.values.flatten()
        val groupsById = allGroups.associateBy { it.id }
        val groupIds = allGroups.map { it.id }
        val inputsByGroup = if (groupIds.isNotEmpty()) {
            groupInputDao.getByGroups(groupIds).groupBy { it.bindingGroupId }
        } else emptyMap()

        val inputIds = inputsByGroup.values.flatten().map { it.id }
        val activatorsByInput = if (inputIds.isNotEmpty()) {
            activatorDao.getByGroupInputs(inputIds).groupBy { it.groupInputId }
        } else emptyMap()

        val activatorIds = activatorsByInput.values.flatten().map { it.id }
        val bindingsByActivator = if (activatorIds.isNotEmpty()) {
            bindingDao.getByActivators(activatorIds).groupBy { it.activatorId }
        } else emptyMap()

        fun buildGroup(group: BindingGroup): BindingGroupGraph {
            val inputs = (inputsByGroup[group.id] ?: emptyList()).map { input ->
                val activators = (activatorsByInput[input.id] ?: emptyList()).map { activator ->
                    ActivatorGraph(activator, bindingsByActivator[activator.id] ?: emptyList())
                }
                GroupInputGraph(input, activators)
            }
            return BindingGroupGraph(group, inputs)
        }

        val actionSetGraphs = sets.map { actionSet ->
            val layerGraphs = (layersByActionSet[actionSet.id] ?: emptyList()).map { layer ->
                val groupsForLayer = (groupsByActionLayer[layer.id] ?: emptyList()).map(::buildGroup)
                ActionLayerGraph(layer, groupsForLayer)
            }
            val presetEntries = (presetsByActionSet[actionSet.id] ?: emptyList()).mapNotNull { pb ->
                val group = groupsById[pb.bindingGroupId] ?: return@mapNotNull null
                PresetEntry(pb.inputSource, pb.state, buildGroup(group))
            }
            ActionSetGraph(actionSet, layerGraphs, presetEntries)
        }

        return ControllerConfig(controllerProfile, actionSetGraphs)
    }

    private data class InputSourceSeed(
        val groupName: String,
        val mode: BindingMode,
        val inputKeys: List<String>,
    )

    companion object {
        /**
         * Default seed table for Generic Android. Trackpads, back paddles, and gyro are
         * intentionally excluded — they're in the schema for VDF import compatibility
         * but the AYN Thor (and most Android pads) don't expose them, so we'd be
         * seeding configurable-but-never-fireable groups.
         */
        private val DEFAULT_INPUT_SOURCE_SEEDS: Map<InputSource, InputSourceSeed> = linkedMapOf(
            InputSource.BUTTON_DIAMOND to InputSourceSeed(
                "face_buttons", BindingMode.BUTTON_PAD,
                listOf("button_a", "button_b", "button_x", "button_y"),
            ),
            InputSource.DPAD to InputSourceSeed(
                "dpad", BindingMode.DPAD,
                listOf("dpad_north", "dpad_south", "dpad_east", "dpad_west"),
            ),
            InputSource.LEFT_BUMPER to InputSourceSeed(
                "left_bumper", BindingMode.SINGLE_BUTTON, listOf("click"),
            ),
            InputSource.RIGHT_BUMPER to InputSourceSeed(
                "right_bumper", BindingMode.SINGLE_BUTTON, listOf("click"),
            ),
            InputSource.LEFT_TRIGGER to InputSourceSeed(
                "left_trigger", BindingMode.TRIGGER, listOf("click"),
            ),
            InputSource.RIGHT_TRIGGER to InputSourceSeed(
                "right_trigger", BindingMode.TRIGGER, listOf("click"),
            ),
            InputSource.LEFT_JOYSTICK to InputSourceSeed(
                "left_joystick", BindingMode.JOYSTICK_MOVE, listOf("click"),
            ),
            InputSource.RIGHT_JOYSTICK to InputSourceSeed(
                "right_joystick", BindingMode.JOYSTICK_MOVE, listOf("click"),
            ),
            InputSource.SWITCH_START to InputSourceSeed(
                "switch_start", BindingMode.SINGLE_BUTTON, listOf("click"),
            ),
            InputSource.SWITCH_SELECT to InputSourceSeed(
                "switch_select", BindingMode.SINGLE_BUTTON, listOf("click"),
            ),
        )
    }
}
