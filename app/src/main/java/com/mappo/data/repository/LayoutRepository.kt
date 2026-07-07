package com.mappo.data.repository

import com.mappo.data.db.LayoutDao
import com.mappo.data.defaults.DefaultLayouts
import com.mappo.data.model.KeyLayout
import com.mappo.data.model.toKeyLayout
import com.mappo.data.model.toJson
import com.mappo.data.model.toSnapshot
import com.mappo.data.model.withFreshButtonIds
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class LayoutRepository @Inject constructor(private val dao: LayoutDao) {

    fun getLayoutsByProfile(profileId: Long): Flow<List<KeyLayout>> = dao.getByProfile(profileId)

    suspend fun getLayoutsByProfileOnce(profileId: Long): List<KeyLayout> =
        dao.getByProfileOnce(profileId)

    suspend fun getById(id: Long): KeyLayout? = dao.getById(id)

    suspend fun saveLayout(layout: KeyLayout): Long = dao.insert(layout)

    suspend fun updateLayout(layout: KeyLayout) = dao.update(layout)

    suspend fun deleteLayout(layout: KeyLayout) = dao.delete(layout)

    suspend fun deleteById(id: Long) = dao.deleteById(id)

    suspend fun reorder(profileId: Long, idToPosition: Map<Long, Int>) =
        dao.reorder(profileId, idToPosition)

    /**
     * Idempotent: only seeds when the profile has no persisted layouts. Used on first observation
     * of a profile created via the SQL seed callback (which doesn't insert default layouts).
     */
    suspend fun seedDefaultsIfEmpty(profileId: Long) {
        if (dao.getByProfileOnce(profileId).isEmpty()) seedDefaults(profileId)
    }

    /**
     * Insert the built-in default layouts for [profileId] with stable positions and a populated
     * originalSnapshotJson so a future Reset can revert to the as-seeded state.
     */
    suspend fun seedDefaults(profileId: Long) {
        DefaultLayouts.all.forEachIndexed { index, layout ->
            // DefaultLayouts.all holds singleton GridLayouts whose buttons get the same
            // UUIDs at app start; seeding from them as-is means every profile shares button
            // ids with every other profile and with the in-memory templates. Regenerate
            // per-profile so each seed is independent.
            val fresh = layout.withFreshButtonIds()
            val snapshotJson = fresh.toSnapshot().toJson()
            dao.insert(
                fresh.toKeyLayout(
                    profileId = profileId,
                    position = index,
                    originalSnapshotJson = snapshotJson
                )
            )
        }
    }
}
