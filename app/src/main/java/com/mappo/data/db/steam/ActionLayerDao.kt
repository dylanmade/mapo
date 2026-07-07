package com.mappo.data.db.steam

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.mappo.data.model.steam.ActionLayer

@Dao
interface ActionLayerDao {

    @Query("SELECT * FROM action_layer WHERE parentActionSetId IN (:actionSetIds) ORDER BY orderIndex")
    suspend fun getByActionSets(actionSetIds: List<Long>): List<ActionLayer>

    @Query("SELECT * FROM action_layer WHERE id = :id")
    suspend fun getById(id: Long): ActionLayer?

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(layer: ActionLayer): Long

    @Update
    suspend fun update(layer: ActionLayer)

    @Query("DELETE FROM action_layer WHERE id = :id")
    suspend fun deleteById(id: Long)
}
