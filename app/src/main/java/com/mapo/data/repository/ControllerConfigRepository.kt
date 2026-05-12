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

        configDirtyTick.value = configDirtyTick.value + 1
        return controllerProfileId
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
        }

        configDirtyTick.value = configDirtyTick.value + 1
    }

    /**
     * Replace the bindings on [activatorId] with a single binding from [output].
     * Multi-binding (cycle_binding) editing lands in Phase 3 — for now every
     * activator carries at most one binding.
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
