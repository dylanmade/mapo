package com.mappo.data.db.steam

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.mappo.data.model.steam.BindingGroup

@Dao
interface BindingGroupDao {

    @Query("SELECT * FROM binding_group WHERE actionSetId IN (:actionSetIds)")
    suspend fun getByActionSets(actionSetIds: List<Long>): List<BindingGroup>

    @Query("SELECT * FROM binding_group WHERE actionLayerId IN (:actionLayerIds)")
    suspend fun getByActionLayers(actionLayerIds: List<Long>): List<BindingGroup>

    @Query("SELECT * FROM binding_group WHERE id = :id")
    suspend fun getById(id: Long): BindingGroup?

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(group: BindingGroup): Long

    @Update
    suspend fun update(group: BindingGroup)

    @Query("DELETE FROM binding_group WHERE id = :id")
    suspend fun deleteById(id: Long)
}
