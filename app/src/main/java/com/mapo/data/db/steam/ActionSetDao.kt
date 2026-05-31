package com.mapo.data.db.steam

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.mapo.data.model.steam.ActionSet
import kotlinx.coroutines.flow.Flow

@Dao
interface ActionSetDao {

    @Query("SELECT * FROM action_set WHERE controllerProfileId = :controllerProfileId ORDER BY orderIndex")
    fun observeByControllerProfile(controllerProfileId: Long): Flow<List<ActionSet>>

    @Query("SELECT * FROM action_set WHERE controllerProfileId = :controllerProfileId ORDER BY orderIndex")
    suspend fun getByControllerProfile(controllerProfileId: Long): List<ActionSet>

    @Query("SELECT * FROM action_set WHERE id = :id")
    suspend fun getById(id: Long): ActionSet?

    @Query("SELECT * FROM action_set")
    suspend fun getAll(): List<ActionSet>

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(set: ActionSet): Long

    @Update
    suspend fun update(set: ActionSet)

    @Query("DELETE FROM action_set WHERE id = :id")
    suspend fun deleteById(id: Long)
}
