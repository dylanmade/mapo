package com.pcpad.data.repository

import com.pcpad.data.db.GamepadMappingDao
import com.pcpad.data.model.DeviceButton
import com.pcpad.data.model.GamepadMapping
import com.pcpad.data.model.RemapTarget
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class GamepadMappingRepository @Inject constructor(
    private val dao: GamepadMappingDao
) {

    fun getMappingsForProfile(profileId: Long): Flow<List<GamepadMapping>> =
        dao.getByProfile(profileId)

    suspend fun saveMappings(profileId: Long, mappings: Map<DeviceButton, RemapTarget>) {
        dao.deleteAllForProfile(profileId)
        val rows = mappings.entries
            .filter { it.value !is RemapTarget.Unbound }
            .map { (btn, target) -> GamepadMapping(profileId, btn.name, target.encode()) }
        if (rows.isNotEmpty()) dao.insertAll(rows)
    }

    suspend fun copyMappings(sourceProfileId: Long, destProfileId: Long) {
        val source = dao.getByProfileOnce(sourceProfileId)
        if (source.isNotEmpty()) {
            dao.insertAll(source.map { it.copy(profileId = destProfileId) })
        }
    }
}
