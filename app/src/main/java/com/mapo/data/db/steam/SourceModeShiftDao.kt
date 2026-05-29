package com.mapo.data.db.steam

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.mapo.data.model.steam.InputSource
import com.mapo.data.model.steam.SourceModeShift

@Dao
interface SourceModeShiftDao {

    @Query("SELECT * FROM source_mode_shift WHERE actionSetId IN (:actionSetIds) ORDER BY displayOrder ASC, id ASC")
    suspend fun getByActionSets(actionSetIds: List<Long>): List<SourceModeShift>

    @Query("SELECT * FROM source_mode_shift WHERE actionLayerId IN (:actionLayerIds) ORDER BY displayOrder ASC, id ASC")
    suspend fun getByActionLayers(actionLayerIds: List<Long>): List<SourceModeShift>

    @Query("SELECT * FROM source_mode_shift WHERE id = :id")
    suspend fun getById(id: Long): SourceModeShift?

    @Query("SELECT COALESCE(MAX(displayOrder), -1) + 1 FROM source_mode_shift WHERE actionSetId = :actionSetId AND ownerSource = :source")
    suspend fun nextDisplayOrderForSet(actionSetId: Long, source: InputSource): Int

    @Query("SELECT COALESCE(MAX(displayOrder), -1) + 1 FROM source_mode_shift WHERE actionLayerId = :actionLayerId AND ownerSource = :source")
    suspend fun nextDisplayOrderForLayer(actionLayerId: Long, source: InputSource): Int

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(shift: SourceModeShift): Long

    @Update
    suspend fun update(shift: SourceModeShift)

    @Query("DELETE FROM source_mode_shift WHERE id = :id")
    suspend fun deleteById(id: Long)
}
