package com.mappo.data.db

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import com.mappo.data.model.OverlayElement
import kotlinx.coroutines.flow.Flow

@Dao
interface OverlayElementDao {

    @Query("SELECT * FROM overlay_elements WHERE profileId = :profileId ORDER BY zIndex ASC, id ASC")
    fun getByProfile(profileId: Long): Flow<List<OverlayElement>>

    @Query("SELECT * FROM overlay_elements WHERE profileId = :profileId ORDER BY zIndex ASC, id ASC")
    suspend fun getByProfileOnce(profileId: Long): List<OverlayElement>

    /** Set-owned elements (the editor's set scope; run mode's active-set overlay). */
    @Query("SELECT * FROM overlay_elements WHERE actionSetId = :actionSetId ORDER BY zIndex ASC, id ASC")
    fun getBySet(actionSetId: Long): Flow<List<OverlayElement>>

    /** Layer-owned elements (the editor's layer scope). */
    @Query("SELECT * FROM overlay_elements WHERE actionLayerId = :actionLayerId ORDER BY zIndex ASC, id ASC")
    fun getByLayer(actionLayerId: Long): Flow<List<OverlayElement>>

    @Query("SELECT * FROM overlay_elements WHERE actionSetId = :actionSetId ORDER BY zIndex ASC, id ASC")
    suspend fun getBySetOnce(actionSetId: Long): List<OverlayElement>

    @Query("SELECT * FROM overlay_elements WHERE actionLayerId = :actionLayerId ORDER BY zIndex ASC, id ASC")
    suspend fun getByLayerOnce(actionLayerId: Long): List<OverlayElement>

    @Query("SELECT * FROM overlay_elements WHERE id = :id LIMIT 1")
    suspend fun getById(id: Long): OverlayElement?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(element: OverlayElement): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(elements: List<OverlayElement>): List<Long>

    /**
     * Replace one scope's (set OR layer) elements with [elements] in a single transaction: delete the
     * rows not present in the snapshot, then upsert the snapshot (its rows keep their original ids, so
     * windows/selection stay valid across undo). One transaction → observers re-emit once (no flash).
     */
    @Transaction
    suspend fun replaceScope(actionSetId: Long?, actionLayerId: Long?, elements: List<OverlayElement>) {
        val current = when {
            actionLayerId != null -> getByLayerOnce(actionLayerId)
            actionSetId != null -> getBySetOnce(actionSetId)
            else -> return
        }
        val keep = elements.mapTo(HashSet()) { it.id }
        current.forEach { if (it.id !in keep) deleteById(it.id) }
        insertAll(elements)
    }

    @Update
    suspend fun update(element: OverlayElement)

    /**
     * Batch update. Room runs all rows in a single transaction, so the invalidation tracker
     * fires once and observers ([getBySet] / [getByLayer]) re-emit a single time with every new
     * value — used to commit a multi-button drag atomically and avoid a per-row reposition flash.
     */
    @Update
    suspend fun update(elements: List<OverlayElement>)

    @Delete
    suspend fun delete(element: OverlayElement)

    @Query("DELETE FROM overlay_elements WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("DELETE FROM overlay_elements WHERE profileId = :profileId")
    suspend fun deleteAllForProfile(profileId: Long)
}
