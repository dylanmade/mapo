package com.mapo.data.db.steam

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.mapo.data.model.steam.PresetBinding

@Dao
interface PresetBindingDao {

    @Query("SELECT * FROM preset_binding WHERE actionSetId IN (:actionSetIds)")
    suspend fun getByActionSets(actionSetIds: List<Long>): List<PresetBinding>

    @Query("SELECT * FROM preset_binding WHERE id = :id")
    suspend fun getById(id: Long): PresetBinding?

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(presetBinding: PresetBinding): Long

    @Update
    suspend fun update(presetBinding: PresetBinding)

    @Query("DELETE FROM preset_binding WHERE id = :id")
    suspend fun deleteById(id: Long)
}
