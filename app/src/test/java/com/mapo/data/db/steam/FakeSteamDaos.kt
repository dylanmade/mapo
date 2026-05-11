package com.mapo.data.db.steam

import com.mapo.data.model.steam.ActionLayer
import com.mapo.data.model.steam.ActionSet
import com.mapo.data.model.steam.Activator
import com.mapo.data.model.steam.Binding
import com.mapo.data.model.steam.BindingGroup
import com.mapo.data.model.steam.ControllerProfile
import com.mapo.data.model.steam.GameAction
import com.mapo.data.model.steam.GroupInput
import com.mapo.data.model.steam.PresetBinding
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map

class FakeControllerProfileDao : ControllerProfileDao {
    val rows = MutableStateFlow<List<ControllerProfile>>(emptyList())
    private var nextId = 1L

    override fun observeByProfile(profileId: Long): Flow<List<ControllerProfile>> =
        rows.map { all -> all.filter { it.profileId == profileId } }

    override suspend fun getByProfile(profileId: Long): List<ControllerProfile> =
        rows.value.filter { it.profileId == profileId }

    override suspend fun getById(id: Long): ControllerProfile? = rows.value.firstOrNull { it.id == id }

    override suspend fun insert(profile: ControllerProfile): Long {
        val id = nextId++
        rows.value = rows.value + profile.copy(id = id)
        return id
    }

    override suspend fun update(profile: ControllerProfile) {
        rows.value = rows.value.map { if (it.id == profile.id) profile else it }
    }

    override suspend fun deleteById(id: Long) {
        rows.value = rows.value.filterNot { it.id == id }
    }
}

class FakeActionSetDao : ActionSetDao {
    val rows = MutableStateFlow<List<ActionSet>>(emptyList())
    private var nextId = 1L

    override fun observeByControllerProfile(controllerProfileId: Long): Flow<List<ActionSet>> =
        rows.map { all -> all.filter { it.controllerProfileId == controllerProfileId }.sortedBy { it.orderIndex } }

    override suspend fun getByControllerProfile(controllerProfileId: Long): List<ActionSet> =
        rows.value.filter { it.controllerProfileId == controllerProfileId }.sortedBy { it.orderIndex }

    override suspend fun getById(id: Long): ActionSet? = rows.value.firstOrNull { it.id == id }

    override suspend fun insert(set: ActionSet): Long {
        val id = nextId++
        rows.value = rows.value + set.copy(id = id)
        return id
    }

    override suspend fun update(set: ActionSet) {
        rows.value = rows.value.map { if (it.id == set.id) set else it }
    }

    override suspend fun deleteById(id: Long) {
        rows.value = rows.value.filterNot { it.id == id }
    }
}

class FakeActionLayerDao : ActionLayerDao {
    val rows = MutableStateFlow<List<ActionLayer>>(emptyList())
    private var nextId = 1L

    override suspend fun getByActionSets(actionSetIds: List<Long>): List<ActionLayer> =
        rows.value.filter { it.parentActionSetId in actionSetIds }.sortedBy { it.orderIndex }

    override suspend fun getById(id: Long): ActionLayer? = rows.value.firstOrNull { it.id == id }

    override suspend fun insert(layer: ActionLayer): Long {
        val id = nextId++
        rows.value = rows.value + layer.copy(id = id)
        return id
    }

    override suspend fun update(layer: ActionLayer) {
        rows.value = rows.value.map { if (it.id == layer.id) layer else it }
    }

    override suspend fun deleteById(id: Long) {
        rows.value = rows.value.filterNot { it.id == id }
    }
}

class FakeGameActionDao : GameActionDao {
    val rows = MutableStateFlow<List<GameAction>>(emptyList())
    private var nextId = 1L

    override suspend fun getByActionSets(actionSetIds: List<Long>): List<GameAction> =
        rows.value.filter { it.actionSetId in actionSetIds }

    override suspend fun insert(action: GameAction): Long {
        val id = nextId++
        rows.value = rows.value + action.copy(id = id)
        return id
    }

    override suspend fun deleteById(id: Long) {
        rows.value = rows.value.filterNot { it.id == id }
    }
}

class FakeBindingGroupDao : BindingGroupDao {
    val rows = MutableStateFlow<List<BindingGroup>>(emptyList())
    private var nextId = 1L

    override suspend fun getByActionSets(actionSetIds: List<Long>): List<BindingGroup> =
        rows.value.filter { it.actionSetId in actionSetIds }

    override suspend fun getByActionLayers(actionLayerIds: List<Long>): List<BindingGroup> =
        rows.value.filter { it.actionLayerId in actionLayerIds }

    override suspend fun getById(id: Long): BindingGroup? = rows.value.firstOrNull { it.id == id }

    override suspend fun insert(group: BindingGroup): Long {
        val id = nextId++
        rows.value = rows.value + group.copy(id = id)
        return id
    }

    override suspend fun update(group: BindingGroup) {
        rows.value = rows.value.map { if (it.id == group.id) group else it }
    }

    override suspend fun deleteById(id: Long) {
        rows.value = rows.value.filterNot { it.id == id }
    }
}

class FakeGroupInputDao : GroupInputDao {
    val rows = MutableStateFlow<List<GroupInput>>(emptyList())
    private var nextId = 1L

    override suspend fun getByGroups(bindingGroupIds: List<Long>): List<GroupInput> =
        rows.value.filter { it.bindingGroupId in bindingGroupIds }.sortedBy { it.orderIndex }

    override suspend fun getById(id: Long): GroupInput? = rows.value.firstOrNull { it.id == id }

    override suspend fun insert(input: GroupInput): Long {
        val id = nextId++
        rows.value = rows.value + input.copy(id = id)
        return id
    }

    override suspend fun deleteById(id: Long) {
        rows.value = rows.value.filterNot { it.id == id }
    }
}

class FakeActivatorDao : ActivatorDao {
    val rows = MutableStateFlow<List<Activator>>(emptyList())
    private var nextId = 1L

    override suspend fun getByGroupInputs(groupInputIds: List<Long>): List<Activator> =
        rows.value.filter { it.groupInputId in groupInputIds }.sortedBy { it.orderIndex }

    override suspend fun getById(id: Long): Activator? = rows.value.firstOrNull { it.id == id }

    override suspend fun insert(activator: Activator): Long {
        val id = nextId++
        rows.value = rows.value + activator.copy(id = id)
        return id
    }

    override suspend fun update(activator: Activator) {
        rows.value = rows.value.map { if (it.id == activator.id) activator else it }
    }

    override suspend fun deleteById(id: Long) {
        rows.value = rows.value.filterNot { it.id == id }
    }
}

class FakeBindingDao : BindingDao {
    val rows = MutableStateFlow<List<Binding>>(emptyList())
    private var nextId = 1L

    override suspend fun getByActivators(activatorIds: List<Long>): List<Binding> =
        rows.value.filter { it.activatorId in activatorIds }.sortedBy { it.orderIndex }

    override suspend fun getById(id: Long): Binding? = rows.value.firstOrNull { it.id == id }

    override suspend fun insert(binding: Binding): Long {
        val id = nextId++
        rows.value = rows.value + binding.copy(id = id)
        return id
    }

    override suspend fun update(binding: Binding) {
        rows.value = rows.value.map { if (it.id == binding.id) binding else it }
    }

    override suspend fun deleteById(id: Long) {
        rows.value = rows.value.filterNot { it.id == id }
    }

    override suspend fun deleteByActivator(activatorId: Long) {
        rows.value = rows.value.filterNot { it.activatorId == activatorId }
    }
}

class FakePresetBindingDao : PresetBindingDao {
    val rows = MutableStateFlow<List<PresetBinding>>(emptyList())
    private var nextId = 1L

    override suspend fun getByActionSets(actionSetIds: List<Long>): List<PresetBinding> =
        rows.value.filter { it.actionSetId in actionSetIds }

    override suspend fun getById(id: Long): PresetBinding? = rows.value.firstOrNull { it.id == id }

    override suspend fun insert(presetBinding: PresetBinding): Long {
        val id = nextId++
        rows.value = rows.value + presetBinding.copy(id = id)
        return id
    }

    override suspend fun update(presetBinding: PresetBinding) {
        rows.value = rows.value.map { if (it.id == presetBinding.id) presetBinding else it }
    }

    override suspend fun deleteById(id: Long) {
        rows.value = rows.value.filterNot { it.id == id }
    }
}
