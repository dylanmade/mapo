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
import com.mapo.data.model.steam.ActionLayer
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
import com.mapo.data.model.steam.LayerPresetBinding
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
    private val layerPresetBindingDao: com.mapo.data.db.steam.LayerPresetBindingDao,
    private val sourceModeShiftDao: com.mapo.data.db.steam.SourceModeShiftDao,
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
                title = "Default Map",
                legacy = true,
                orderIndex = 0,
            )
        )
        seedDefaultSetContents(actionSetId)

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
            seedSingleInputSource(actionSetId, inputSource, spec)
        }
    }

    /**
     * Insert a [BindingGroup] (+ child GroupInputs / Activators / Bindings) and
     * the matching `active`-state [PresetBinding] for [inputSource] under
     * [actionSetId]. Extracted from [seedDefaultSetContents] so
     * [ensureSeededInputSources] can call it for missing seeds without
     * re-creating already-present groups.
     */
    private suspend fun seedSingleInputSource(
        actionSetId: Long,
        inputSource: InputSource,
        spec: InputSourceSeed,
    ) {
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

    /**
     * Back-fill any [DEFAULT_INPUT_SOURCE_SEEDS] entry that's missing from
     * each existing action set. Idempotent — runs on every app start and is a
     * no-op once every set has been retrofitted. Generic for the next time we
     * add an input source to the seed table (gyro was the first such addition
     * after D.3 enabled the runtime), so we don't repeatedly rediscover the
     * "user's pre-existing profile shows no picker for the new source"
     * problem.
     *
     * Compares each action set's `active`-state PresetBindings to the seed
     * table's keys and seeds the missing ones. Existing rows are untouched.
     */
    suspend fun ensureSeededInputSources() {
        val sets = actionSetDao.getAll()
        if (sets.isEmpty()) return
        val setIds = sets.map { it.id }
        val activePresetsBySetId = presetBindingDao.getByActionSets(setIds)
            .filter { it.state == "active" }
            .groupBy { it.actionSetId }
        var retrofittedAny = false
        for (set in sets) {
            val present = activePresetsBySetId[set.id]?.map { it.inputSource }?.toSet().orEmpty()
            val missing = DEFAULT_INPUT_SOURCE_SEEDS.keys - present
            if (missing.isEmpty()) continue
            android.util.Log.i(
                "ControllerConfigRepo",
                "ensureSeededInputSources: set ${set.id} missing $missing — retrofitting",
            )
            for (inputSource in missing) {
                val spec = DEFAULT_INPUT_SOURCE_SEEDS.getValue(inputSource)
                seedSingleInputSource(set.id, inputSource, spec)
            }
            retrofittedAny = true
        }
        val flippedAny = migrateBumperSwitchToDeviceDefault()
        if (retrofittedAny || flippedAny) {
            // Bump the dirty tick so any live config subscriber refreshes.
            configDirtyTick.value = configDirtyTick.value + 1
        }
    }

    /**
     * 2026-06-01 destructive migration — flip every bumper / switch
     * binding_group in `SINGLE_BUTTON` mode to `DEVICE_DEFAULT`. The seed
     * defaults moved from SINGLE_BUTTON+UNBOUND-click to DEVICE_DEFAULT,
     * and pre-release users who hit the old seed shouldn't be left with
     * silent bumpers/Start/Select under EVIOCGRAB.
     *
     * **Destructive note:** if a user had bound the click sub-input to
     * something useful (e.g., L1 → ENTER remap), this migration drops
     * back to DEVICE_DEFAULT and the binding becomes inert until they
     * switch the source back to SINGLE_BUTTON in the picker. Sanctioned
     * by the user under pre-release tolerance + the explicit "feel free
     * to destructively transition" instruction.
     *
     * Idempotent — re-runs are no-ops because there's nothing left in
     * SINGLE_BUTTON mode after the first sweep.
     */
    private suspend fun migrateBumperSwitchToDeviceDefault(): Boolean {
        val targetSources = setOf(
            InputSource.LEFT_BUMPER,
            InputSource.RIGHT_BUMPER,
            InputSource.SWITCH_START,
            InputSource.SWITCH_SELECT,
        )
        val sets = actionSetDao.getAll()
        if (sets.isEmpty()) return false
        val presets = presetBindingDao.getByActionSets(sets.map { it.id })
            .filter { it.state == "active" && it.inputSource in targetSources }
        var flipped = false
        for (preset in presets) {
            val group = bindingGroupDao.getById(preset.bindingGroupId) ?: continue
            if (group.mode != BindingMode.SINGLE_BUTTON) continue
            bindingGroupDao.update(group.copy(mode = BindingMode.DEVICE_DEFAULT))
            flipped = true
            android.util.Log.i(
                "ControllerConfigRepo",
                "migrateBumperSwitchToDeviceDefault: flipped group ${group.id} " +
                    "(${preset.inputSource}) from SINGLE_BUTTON → DEVICE_DEFAULT",
            )
        }
        return flipped
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
     * controller_profile (returns false). The starting set is implicitly the first
     * set by orderIndex on whatever remains, so no reassignment is needed.
     * Returns true on successful deletion.
     */
    suspend fun deleteActionSet(actionSetId: Long): Boolean {
        val existing = actionSetDao.getById(actionSetId) ?: return false
        val siblings = actionSetDao.getByControllerProfile(existing.controllerProfileId)
        if (siblings.size <= 1) return false

        actionSetDao.deleteById(actionSetId)
        configDirtyTick.value = configDirtyTick.value + 1
        return true
    }

    /**
     * Append a new empty [ActionLayer] under [actionSetId] (Brick 5.2). Layers carry no
     * overlay binding_groups at create time — the user fills overlays in by overriding
     * specific bindings from the parent set (Brick 5.5). The new layer's [ActionLayer.orderIndex]
     * is the next slot after existing siblings; returns the new layer id.
     */
    suspend fun addLayer(actionSetId: Long, name: String, title: String): Long {
        val existing = actionLayerDao.getByActionSets(listOf(actionSetId))
        val nextOrder = (existing.maxOfOrNull { it.orderIndex } ?: -1) + 1
        val newLayerId = actionLayerDao.insert(
            ActionLayer(
                parentActionSetId = actionSetId,
                name = name,
                title = title,
                orderIndex = nextOrder,
            )
        )
        configDirtyTick.value = configDirtyTick.value + 1
        return newLayerId
    }

    /** Update [layerId]'s human-facing [name] / [title]. No-op when [layerId] is unknown. */
    suspend fun renameLayer(layerId: Long, name: String, title: String) {
        val existing = actionLayerDao.getById(layerId) ?: return
        if (existing.name == name && existing.title == title) return
        actionLayerDao.update(existing.copy(name = name, title = title))
        configDirtyTick.value = configDirtyTick.value + 1
    }

    /**
     * Deep-clone [sourceLayerId] (and any binding_groups / inputs / activators / bindings
     * beneath it) into a sibling layer with [name] / [title] on the same parent action set.
     * Per `feedback_duplicates_own_their_data`, every cloned row gets a fresh autogenerated
     * id so the duplicate is independently addressable. Returns the new layer id.
     *
     * Today layers carry no overlay groups (5.1 + 5.2 only create empty layers); the
     * cloning logic exists so 5.5's overlay-authoring writes don't have to retrofit it.
     */
    suspend fun duplicateLayer(sourceLayerId: Long, name: String, title: String): Long {
        val source = actionLayerDao.getById(sourceLayerId)
            ?: error("Source layer $sourceLayerId not found")

        val siblings = actionLayerDao.getByActionSets(listOf(source.parentActionSetId))
        val nextOrder = (siblings.maxOfOrNull { it.orderIndex } ?: -1) + 1
        val newLayerId = actionLayerDao.insert(
            ActionLayer(
                parentActionSetId = source.parentActionSetId,
                name = name,
                title = title,
                orderIndex = nextOrder,
            )
        )

        val sourceGroups = bindingGroupDao.getByActionLayers(listOf(sourceLayerId))
        val groupIdMap = HashMap<Long, Long>(sourceGroups.size)
        for (sourceGroup in sourceGroups) {
            val newGroupId = bindingGroupDao.insert(
                sourceGroup.copy(id = 0, actionLayerId = newLayerId)
            )
            groupIdMap[sourceGroup.id] = newGroupId
        }

        if (sourceGroups.isNotEmpty()) {
            val sourceInputs = groupInputDao.getByGroups(sourceGroups.map { it.id })
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

            if (sourceInputs.isNotEmpty()) {
                val sourceActivators = activatorDao.getByGroupInputs(sourceInputs.map { it.id })
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
            }
        }

        // 5.5.a: clone layer preset entries so the duplicate's overrides point at the
        // cloned binding_groups. Without this, the copy would have orphan layer rows
        // and no actual override mapping.
        for (sourcePreset in layerPresetBindingDao.getByActionLayers(listOf(sourceLayerId))) {
            val mappedGroupId = groupIdMap[sourcePreset.bindingGroupId] ?: continue
            layerPresetBindingDao.insert(
                sourcePreset.copy(
                    id = 0,
                    actionLayerId = newLayerId,
                    bindingGroupId = mappedGroupId,
                )
            )
        }

        configDirtyTick.value = configDirtyTick.value + 1
        return newLayerId
    }

    /**
     * Delete [layerId]. Schema cascades drop any binding_groups (→ group_inputs →
     * activators → bindings) beneath it. Unlike `deleteActionSet`, there's no "last
     * layer must remain" guard — an action set with zero layers is valid. Returns
     * true if a row was deleted.
     */
    suspend fun deleteLayer(layerId: Long): Boolean {
        actionLayerDao.getById(layerId) ?: return false
        actionLayerDao.deleteById(layerId)
        configDirtyTick.value = configDirtyTick.value + 1
        return true
    }

    // ── Brick 5.5.b: layer override materialization ──────────────────────────

    /**
     * Find-or-create the override chain for `(layerId, inputSource, groupInputKey)`.
     *
     * When the user taps a "ghost" row in overlay-editing mode, the row has no
     * persisted override yet — only the parent set's binding inherits through. This
     * call materializes the scaffolding: an overlay [BindingGroup] on the layer (one
     * per input source, shared across that source's sub-inputs to mirror the base
     * preset grain), a [GroupInput] for the requested key, a default `FULL_PRESS`
     * activator, and a single unbound [Binding]. Subsequent `setBinding` calls write
     * the real output.
     *
     * **Inherits from base**: the new overlay group copies the base set's group's
     * `mode` and `settingsJson` for the same input source — so e.g. an overlay on a
     * trackpad starts with the same deadzone settings the base has. The user can
     * diverge later (Phase 6).
     *
     * Idempotent on the sub-input level: calling twice with identical args returns
     * the same group_input id. Returns the [GroupInput.id] of the materialized row —
     * callers can immediately use it for further wiring (e.g., opening the picker).
     */
    suspend fun materializeLayerOverride(
        layerId: Long,
        inputSource: InputSource,
        groupInputKey: String,
    ): Long {
        val layer = actionLayerDao.getById(layerId)
            ?: error("Unknown layer $layerId")

        // 1. Find or create the overlay binding_group for this input source on the layer.
        val existingPreset = layerPresetBindingDao.getByActionLayers(listOf(layerId))
            .firstOrNull { it.inputSource == inputSource && it.state == "active" }

        val overlayGroupId: Long = if (existingPreset != null) {
            existingPreset.bindingGroupId
        } else {
            // Inherit mode + settings from the base set's group for the same input source.
            // Falls back to BUTTON_PAD + "{}" if the base has no preset entry — shouldn't
            // normally happen for seeded sources, but defensive.
            val basePresetRow = presetBindingDao.getByActionSets(listOf(layer.parentActionSetId))
                .firstOrNull { it.inputSource == inputSource && it.state == "active" }
            val baseGroup = basePresetRow?.let { bindingGroupDao.getById(it.bindingGroupId) }
            val newGroupId = bindingGroupDao.insert(
                BindingGroup(
                    actionSetId = null,
                    actionLayerId = layerId,
                    name = baseGroup?.name ?: inputSource.name.lowercase(),
                    mode = baseGroup?.mode ?: BindingMode.BUTTON_PAD,
                    settingsJson = baseGroup?.settingsJson ?: "{}",
                )
            )
            layerPresetBindingDao.insert(
                LayerPresetBinding(
                    actionLayerId = layerId,
                    inputSource = inputSource,
                    state = "active",
                    bindingGroupId = newGroupId,
                )
            )
            newGroupId
        }

        // 2. Idempotency on the sub-input — return the existing row if already materialized.
        groupInputDao.getByGroups(listOf(overlayGroupId))
            .firstOrNull { it.inputKey == groupInputKey }
            ?.let { return it.id }

        // 3. Fresh group_input + default activator + unbound binding.
        val nextOrder = groupInputDao.getByGroups(listOf(overlayGroupId))
            .maxOfOrNull { it.orderIndex }?.plus(1) ?: 0
        val newInputId = groupInputDao.insert(
            GroupInput(
                bindingGroupId = overlayGroupId,
                inputKey = groupInputKey,
                orderIndex = nextOrder,
            )
        )
        val activatorId = activatorDao.insert(
            Activator(
                groupInputId = newInputId,
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

        configDirtyTick.value = configDirtyTick.value + 1
        return newInputId
    }

    /**
     * Reverse of [materializeLayerOverride]. Drops the [GroupInput] chain on the
     * layer for `(inputSource, groupInputKey)`, returning the row to inheritance
     * from the parent set. If the overlay [BindingGroup] is left with no remaining
     * inputs, the group itself is also deleted — FK cascade on `layer_preset_binding`
     * drops the orphan preset row. Sibling sub-inputs (e.g., other buttons on the
     * same diamond) are left untouched.
     *
     * No-op when no override exists at `(layerId, inputSource, groupInputKey)`.
     */
    suspend fun clearLayerOverride(
        layerId: Long,
        inputSource: InputSource,
        groupInputKey: String,
    ) {
        val presetRow = layerPresetBindingDao.getByActionLayers(listOf(layerId))
            .firstOrNull { it.inputSource == inputSource && it.state == "active" }
            ?: return

        val target = groupInputDao.getByGroups(listOf(presetRow.bindingGroupId))
            .firstOrNull { it.inputKey == groupInputKey }
            ?: return

        groupInputDao.deleteById(target.id)

        // If we just deleted the last sub-input on this overlay group, drop the group
        // and its preset pointer — the layer no longer has any reason to hold an
        // overlay for this source. Explicit cleanup (rather than relying on FK
        // cascade) so the path stays portable to test fakes and the intent is
        // obvious at the call site.
        val remaining = groupInputDao.getByGroups(listOf(presetRow.bindingGroupId))
        if (remaining.isEmpty()) {
            layerPresetBindingDao.deleteById(presetRow.id)
            bindingGroupDao.deleteById(presetRow.bindingGroupId)
        }

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
        // Retrofit any DEFAULT_INPUT_SOURCE_SEEDS entries that were added to the
        // table after this profile's action sets were first seeded (e.g. GYRO,
        // added 2026-05-31 after D.3 lit up the gyro runtime). Idempotent —
        // no-op once every set has every seed. Runs before the first emission
        // so the consumer's compiled config sees the retrofitted groups on the
        // very first frame.
        ensureSeededInputSources()
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
     * Change [bindingGroupId]'s [BindingMode] without touching its group inputs or
     * activator wiring. Phase 6 Brick 1: the Remap Controls subheader's mode dropdown
     * routes here. Sub-input keys that aren't valid for the new mode are silently
     * filtered by the compile step's `SourceMode.accepts()` check rather than deleted
     * — leaves the rows intact in case the user picks back to the original mode.
     */
    suspend fun updateBindingGroupMode(bindingGroupId: Long, mode: BindingMode) {
        val existing = bindingGroupDao.getById(bindingGroupId) ?: return
        if (existing.mode == mode) return
        bindingGroupDao.update(existing.copy(mode = mode))
        // 2026-05-31 — auto-seed any sub-inputs the new mode needs that the
        // group doesn't already carry. Without this, the dynamic-row UI
        // renders sub-inputs (from validInputsFor) that have no backing
        // GroupInput record, and BindingRow's `ready` gate disables tap.
        // Most visible failure: GYRO source switched to DPAD or
        // DIRECTIONAL_SWIPE — GYRO's seed has no sub-inputs at all, so
        // every dpad direction row would otherwise be inert.
        //
        // Scoped to set-owned (base) groups; layer-owned overlays keep the
        // existing materialize-on-tap pattern (creating sub-inputs eagerly
        // on a layer would turn every layer into a full mode replacement
        // rather than the targeted overlay Steam treats them as).
        if (existing.actionSetId != null) {
            val source = findInputSourceForSetBindingGroup(bindingGroupId)
            if (source != null) {
                ensureSubInputsForMode(bindingGroupId, source, mode)
            }
        }
        configDirtyTick.value = configDirtyTick.value + 1
    }

    /**
     * Replace [bindingGroupId]'s mode-specific `settingsJson` blob. The settings
     * cog on each source row in Remap Controls routes here; the runtime mode
     * handlers parse this JSON (tolerant of missing keys). No-op when unchanged.
     */
    suspend fun updateBindingGroupSettings(bindingGroupId: Long, settingsJson: String) {
        val existing = bindingGroupDao.getById(bindingGroupId) ?: return
        if (existing.settingsJson == settingsJson) return
        bindingGroupDao.update(existing.copy(settingsJson = settingsJson))
        configDirtyTick.value = configDirtyTick.value + 1
    }

    /**
     * Find the [InputSource] that owns [bindingGroupId] when it's a
     * set-owned (base) group. Walks the active `preset_binding` rows whose
     * bindingGroupId matches. Returns null if the group isn't preset-linked
     * (shouldn't happen for normally-seeded groups but is defensive).
     */
    private suspend fun findInputSourceForSetBindingGroup(bindingGroupId: Long): InputSource? {
        val group = bindingGroupDao.getById(bindingGroupId) ?: return null
        val actionSetId = group.actionSetId ?: return null
        val presets = presetBindingDao.getByActionSets(listOf(actionSetId))
        return presets.firstOrNull { it.bindingGroupId == bindingGroupId && it.state == "active" }
            ?.inputSource
    }

    /**
     * Create any missing [GroupInput] rows under [bindingGroupId] for each
     * sub-input that the (source, mode) pair surfaces. Each new GroupInput
     * gets a default FULL_PRESS Activator + Unbound Binding so the row is
     * immediately editable from the binding picker. Sub-inputs that already
     * exist on the group are untouched.
     */
    private suspend fun ensureSubInputsForMode(
        bindingGroupId: Long,
        source: InputSource,
        mode: BindingMode,
    ) {
        val wanted = com.mapo.service.input.modes.validInputsFor(source, mode)
        if (wanted.isEmpty()) return
        val existing = groupInputDao.getByGroups(listOf(bindingGroupId))
            .map { it.inputKey }
            .toSet()
        val missing = wanted - existing
        if (missing.isEmpty()) return
        val nextOrderStart = groupInputDao.getByGroups(listOf(bindingGroupId))
            .maxOfOrNull { it.orderIndex }?.plus(1) ?: 0
        for ((idx, inputKey) in missing.withIndex()) {
            val newInputId = groupInputDao.insert(
                GroupInput(
                    bindingGroupId = bindingGroupId,
                    inputKey = inputKey,
                    orderIndex = nextOrderStart + idx,
                )
            )
            val activatorId = activatorDao.insert(
                Activator(
                    groupInputId = newInputId,
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

    // ── Phase 6: unified "input rows" ────────────────────────────────────────
    // An input row in the UI = one Binding, bucketed under an Activator by press type. Same-type
    // rows are multiple Bindings under one Activator (the runtime fires them together or cycles per
    // the activator's settings). This replaces the separate "extra command" / "sub command" model
    // with a single "add additional input" affordance — no schema change.

    /**
     * Add a new input row of [type] to [groupInputId]. Appends a Binding to the existing same-type
     * Activator, or creates that Activator (with its seeded Unbound binding) if absent. Returns the
     * new bindingId.
     */
    suspend fun addInputRow(groupInputId: Long, type: ActivatorType): Long {
        val existing = activatorDao.getByGroupInputs(listOf(groupInputId)).firstOrNull { it.type == type }
        return if (existing != null) {
            addCommand(existing.id)
        } else {
            val newActivatorId = addActivator(groupInputId, type)
            bindingDao.getByActivators(listOf(newActivatorId)).first().id
        }
    }

    /**
     * Change an input row's press type by reparenting its Binding into the [newType] Activator
     * bucket for the same group input (created without a seed when absent). The old Activator is
     * deleted if the move leaves it empty.
     */
    suspend fun setInputRowPressType(bindingId: Long, newType: ActivatorType) {
        val binding = bindingDao.getById(bindingId) ?: return
        val oldActivator = activatorDao.getById(binding.activatorId) ?: return
        if (oldActivator.type == newType) return
        val groupInputId = oldActivator.groupInputId
        val siblings = activatorDao.getByGroupInputs(listOf(groupInputId))
        val targetId = siblings.firstOrNull { it.type == newType }?.id ?: run {
            val nextOrder = (siblings.maxOfOrNull { it.orderIndex } ?: -1) + 1
            // Create the bucket directly (no seeded binding — the moved binding fills it).
            activatorDao.insert(
                Activator(groupInputId = groupInputId, type = newType, settingsJson = "{}", orderIndex = nextOrder)
            )
        }
        val nextBindingOrder = (bindingDao.getByActivators(listOf(targetId)).maxOfOrNull { it.orderIndex } ?: -1) + 1
        bindingDao.update(binding.copy(activatorId = targetId, orderIndex = nextBindingOrder))
        if (bindingDao.getByActivators(listOf(oldActivator.id)).isEmpty()) {
            activatorDao.deleteById(oldActivator.id)
        }
        configDirtyTick.value = configDirtyTick.value + 1
    }

    /** Set an input row's user label ([Binding.label]); a blank value clears it. */
    suspend fun setInputRowLabel(bindingId: Long, label: String) {
        val binding = bindingDao.getById(bindingId) ?: return
        val normalized = label.trim().ifEmpty { null }
        if (binding.label == normalized) return
        bindingDao.update(binding.copy(label = normalized))
        configDirtyTick.value = configDirtyTick.value + 1
    }

    /**
     * Delete an input row (Binding); its Activator is removed too if now empty. Callers disable this
     * when it's the group input's last remaining row.
     */
    suspend fun deleteInputRow(bindingId: Long) {
        val binding = bindingDao.getById(bindingId) ?: return
        val activatorId = binding.activatorId
        bindingDao.deleteById(bindingId)
        if (bindingDao.getByActivators(listOf(activatorId)).isEmpty()) {
            activatorDao.deleteById(activatorId)
        }
        configDirtyTick.value = configDirtyTick.value + 1
    }

    // ── Phase 7 Brick B.5: Source Mode Shifts ────────────────────────────────

    /**
     * Add a new mode shift to [ownerSource] on the action set identified by
     * [actionSetId]. Creates a fresh target [BindingGroup] in
     * [BindingMode.DEVICE_DEFAULT] (configurable by the user via the UI) and
     * the matching [com.mapo.data.model.steam.SourceModeShift] row with no
     * trigger assigned yet (user assigns via the mode-shift settings UI).
     * Returns the new mode shift's id so the UI can scroll to / open its row.
     */
    suspend fun addModeShiftToSet(actionSetId: Long, ownerSource: InputSource): Long {
        val newGroupId = bindingGroupDao.insert(
            BindingGroup(
                actionSetId = actionSetId,
                actionLayerId = null,
                name = "mode_shift_${ownerSource.name.lowercase()}",
                mode = BindingMode.DEVICE_DEFAULT,
                settingsJson = "{}",
            )
        )
        val order = sourceModeShiftDao.nextDisplayOrderForSet(actionSetId, ownerSource)
        val id = sourceModeShiftDao.insert(
            com.mapo.data.model.steam.SourceModeShift(
                actionSetId = actionSetId,
                actionLayerId = null,
                ownerSource = ownerSource,
                bindingGroupId = newGroupId,
                displayOrder = order,
            )
        )
        configDirtyTick.value = configDirtyTick.value + 1
        return id
    }

    /**
     * Add a new mode shift owned by an action layer (only active while that
     * layer is in the stack). Same shape as [addModeShiftToSet].
     */
    suspend fun addModeShiftToLayer(actionLayerId: Long, ownerSource: InputSource): Long {
        val newGroupId = bindingGroupDao.insert(
            BindingGroup(
                actionSetId = null,
                actionLayerId = actionLayerId,
                name = "mode_shift_${ownerSource.name.lowercase()}",
                mode = BindingMode.DEVICE_DEFAULT,
                settingsJson = "{}",
            )
        )
        val order = sourceModeShiftDao.nextDisplayOrderForLayer(actionLayerId, ownerSource)
        val id = sourceModeShiftDao.insert(
            com.mapo.data.model.steam.SourceModeShift(
                actionSetId = null,
                actionLayerId = actionLayerId,
                ownerSource = ownerSource,
                bindingGroupId = newGroupId,
                displayOrder = order,
            )
        )
        configDirtyTick.value = configDirtyTick.value + 1
        return id
    }

    /**
     * Delete a mode shift and its target binding group (cascade-delete via the
     * schema FK). The action set / layer is untouched.
     */
    suspend fun removeModeShift(modeShiftId: Long) {
        val existing = sourceModeShiftDao.getById(modeShiftId) ?: return
        // Schema declares ON DELETE CASCADE from binding_group → source_mode_shift;
        // deleting the group cleans both rows. Doing it this way (vs deleting the
        // mode-shift row directly) also frees the orphaned target group's child
        // bindings, which is the user-expected outcome.
        bindingGroupDao.deleteById(existing.bindingGroupId)
        configDirtyTick.value = configDirtyTick.value + 1
    }

    /**
     * Assign or clear the physical input that triggers [modeShiftId]. Pass a
     * non-null `(source, subInput)` to assign; pass nulls to clear. The
     * trigger is the input whose press activates the shift and whose release
     * deactivates it.
     */
    suspend fun setModeShiftTrigger(
        modeShiftId: Long,
        triggerSource: InputSource?,
        triggerSubInput: String?,
    ) {
        val existing = sourceModeShiftDao.getById(modeShiftId) ?: return
        if (existing.triggerSource == triggerSource && existing.triggerSubInput == triggerSubInput) return
        sourceModeShiftDao.update(
            existing.copy(triggerSource = triggerSource, triggerSubInput = triggerSubInput)
        )
        configDirtyTick.value = configDirtyTick.value + 1
    }

    /**
     * Phase 7 Brick B.6 — materialize a sub-input row on [modeShiftId]'s target
     * binding group. Mode-shift target groups are created empty by
     * [addModeShiftToSet]/[addModeShiftToLayer]; their per-sub-input rows are
     * created on demand the first time the user taps one in the editor. Same
     * pattern as [materializeLayerOverride] for layer overrides — keeps the
     * shift's group small until the user actually configures bindings.
     *
     * Idempotent: returns the existing row if already materialized. Returns 0L
     * if [modeShiftId] doesn't resolve (e.g. just deleted).
     */
    suspend fun materializeModeShiftInput(
        modeShiftId: Long,
        groupInputKey: String,
    ): Long {
        val shift = sourceModeShiftDao.getById(modeShiftId) ?: return 0L
        val targetGroupId = shift.bindingGroupId

        // Idempotency: if the sub-input already exists on the group, return it.
        groupInputDao.getByGroups(listOf(targetGroupId))
            .firstOrNull { it.inputKey == groupInputKey }
            ?.let { return it.id }

        val nextOrder = groupInputDao.getByGroups(listOf(targetGroupId))
            .maxOfOrNull { it.orderIndex }?.plus(1) ?: 0
        val newInputId = groupInputDao.insert(
            GroupInput(
                bindingGroupId = targetGroupId,
                inputKey = groupInputKey,
                orderIndex = nextOrder,
            )
        )
        val activatorId = activatorDao.insert(
            Activator(
                groupInputId = newInputId,
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
        configDirtyTick.value = configDirtyTick.value + 1
        return newInputId
    }

    private suspend fun loadConfigSnapshot(controllerProfile: ControllerProfile): ControllerConfig {
        val sets = actionSetDao.getByControllerProfile(controllerProfile.id)
        if (sets.isEmpty()) return ControllerConfig(controllerProfile, emptyList())

        val setIds = sets.map { it.id }
        val layersByActionSet = actionLayerDao.getByActionSets(setIds).groupBy { it.parentActionSetId }
        val presetsByActionSet = presetBindingDao.getByActionSets(setIds).groupBy { it.actionSetId }

        val layerIds = layersByActionSet.values.flatten().map { it.id }
        val layerPresetsByLayer = if (layerIds.isNotEmpty()) {
            layerPresetBindingDao.getByActionLayers(layerIds).groupBy { it.actionLayerId }
        } else emptyMap()
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

        // Phase 7 Brick B.5 — mode shifts (per-source while-held overlays).
        // One pass per owner (set vs. layer); they're queried separately because
        // each row is either set- or layer-owned, never both.
        val modeShiftsByActionSet = sourceModeShiftDao.getByActionSets(setIds)
            .groupBy { it.actionSetId!! }
        val modeShiftsByActionLayer = if (layerIds.isNotEmpty()) {
            sourceModeShiftDao.getByActionLayers(layerIds).groupBy { it.actionLayerId!! }
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

        // Resolve a SourceModeShift to its graph form. Drops rows whose target
        // bindingGroup vanished (defensive against stale FKs in dev).
        fun resolveModeShift(shift: com.mapo.data.model.steam.SourceModeShift): com.mapo.data.model.steam.SourceModeShiftGraph? {
            val targetGroup = groupsById[shift.bindingGroupId] ?: return null
            return com.mapo.data.model.steam.SourceModeShiftGraph(shift, buildGroup(targetGroup))
        }

        val actionSetGraphs = sets.map { actionSet ->
            val layerGraphs = (layersByActionSet[actionSet.id] ?: emptyList()).map { layer ->
                val groupsForLayer = (groupsByActionLayer[layer.id] ?: emptyList()).map(::buildGroup)
                val layerPresetEntries = (layerPresetsByLayer[layer.id] ?: emptyList()).mapNotNull { lpb ->
                    val group = groupsById[lpb.bindingGroupId] ?: return@mapNotNull null
                    PresetEntry(lpb.inputSource, lpb.state, buildGroup(group))
                }
                val layerModeShifts = (modeShiftsByActionLayer[layer.id] ?: emptyList())
                    .mapNotNull(::resolveModeShift)
                ActionLayerGraph(layer, groupsForLayer, layerPresetEntries, layerModeShifts)
            }
            val presetEntries = (presetsByActionSet[actionSet.id] ?: emptyList()).mapNotNull { pb ->
                val group = groupsById[pb.bindingGroupId] ?: return@mapNotNull null
                PresetEntry(pb.inputSource, pb.state, buildGroup(group))
            }
            val setModeShifts = (modeShiftsByActionSet[actionSet.id] ?: emptyList())
                .mapNotNull(::resolveModeShift)
            ActionSetGraph(actionSet, layerGraphs, presetEntries, setModeShifts)
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
         * Default seed table for Generic Android. Trackpads and back paddles are
         * intentionally excluded — they're in the schema for VDF import
         * compatibility but the AYN Thor (and most Android pads) don't expose
         * them, so we'd be seeding configurable-but-never-fireable groups.
         *
         * Gyro is included as of D.3: most target handhelds (Thor, Odin 2 Mini,
         * Retroid) ship with a real gyro, and the runtime needs a BindingGroup
         * on the GYRO source for the picker to surface its mode dropdown.
         * Devices that lack a gyro will see the row but the
         * [com.mapo.service.input.GyroLifecycleCoordinator] short-circuits at
         * the hardware-presence check, so the group is inert there.
         */
        private val DEFAULT_INPUT_SOURCE_SEEDS: Map<InputSource, InputSourceSeed> = linkedMapOf(
            InputSource.BUTTON_DIAMOND to InputSourceSeed(
                "face_buttons", BindingMode.BUTTON_PAD,
                listOf("button_a", "button_b", "button_x", "button_y"),
            ),
            // Analog-capable sources default to DEVICE_DEFAULT: Mapo does not
            // intercept until the user explicitly opts into a Mapo-managed mode
            // (which is also what gates the Shizuku motion-capture pipeline).
            InputSource.DPAD to InputSourceSeed(
                "dpad", BindingMode.DEVICE_DEFAULT,
                listOf("dpad_up", "dpad_down", "dpad_left", "dpad_right"),
            ),
            // 2026-06-01: bumpers default to DEVICE_DEFAULT — out of the box
            // Mapo doesn't intercept, so a pristine install behaves like a
            // normal hardware controller (the OS dispatches BTN_TL / BTN_TR
            // directly, and under EVIOCGRAB the passthrough path forwards
            // them through the virtual gamepad). Users who want a remap pick
            // SINGLE_BUTTON in the source picker, which re-surfaces the
            // "click" sub-input row for binding. Pre-2026-06-01 the seed
            // shipped SINGLE_BUTTON + UNBOUND click, which made bumpers
            // silent under grab — a real UX gotcha.
            InputSource.LEFT_BUMPER to InputSourceSeed(
                "left_bumper", BindingMode.DEVICE_DEFAULT, listOf("click"),
            ),
            InputSource.RIGHT_BUMPER to InputSourceSeed(
                "right_bumper", BindingMode.DEVICE_DEFAULT, listOf("click"),
            ),
            // Triggers carry two sub-inputs: "full_pull" (hardware threshold —
            // KEYCODE_BUTTON_L2 / R2) and "soft_pull" (analog soft-pull,
            // fired via TriggerMode.evaluate's hysteresis on the Shizuku
            // motion stream). The mode defaults to DEVICE_DEFAULT on a fresh
            // profile; switching to TRIGGER mode is what activates both rows.
            InputSource.LEFT_TRIGGER to InputSourceSeed(
                "left_trigger", BindingMode.DEVICE_DEFAULT, listOf("full_pull", "soft_pull"),
            ),
            InputSource.RIGHT_TRIGGER to InputSourceSeed(
                "right_trigger", BindingMode.DEVICE_DEFAULT, listOf("full_pull", "soft_pull"),
            ),
            InputSource.LEFT_JOYSTICK to InputSourceSeed(
                "left_joystick", BindingMode.DEVICE_DEFAULT, listOf("click", "outer_ring"),
            ),
            InputSource.RIGHT_JOYSTICK to InputSourceSeed(
                "right_joystick", BindingMode.DEVICE_DEFAULT, listOf("click", "outer_ring"),
            ),
            // 2026-06-01: switches default to DEVICE_DEFAULT for the same
            // reason as bumpers — Start / Select should "just work" out of
            // the box. The SourceModeCatalog gained a dropdown for these
            // sources at the same time so users can opt into SINGLE_BUTTON
            // mode if they want a remap. Steam-parity divergence
            // intentional: Steam hides the switch picker entirely, but
            // Steam doesn't EVIOCGRAB the controller either, so users
            // there get OS pass-through for free.
            InputSource.SWITCH_START to InputSourceSeed(
                "switch_start", BindingMode.DEVICE_DEFAULT, listOf("click"),
            ),
            InputSource.SWITCH_SELECT to InputSourceSeed(
                "switch_select", BindingMode.DEVICE_DEFAULT, listOf("click"),
            ),
            // Gyro: no sub-inputs (gyro modes emit continuous output, not
            // bindable directional rows). Picker on the subheader is what the
            // user interacts with; settings live in the Cog menu.
            InputSource.GYRO to InputSourceSeed(
                "gyro", BindingMode.DEVICE_DEFAULT, emptyList(),
            ),
        )
    }
}
