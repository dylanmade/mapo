package com.mapo.data.db.steam

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.mapo.data.model.steam.Activator

@Dao
interface ActivatorDao {

    @Query("SELECT * FROM activator WHERE groupInputId IN (:groupInputIds) ORDER BY orderIndex")
    suspend fun getByGroupInputs(groupInputIds: List<Long>): List<Activator>

    @Query("SELECT * FROM activator WHERE id = :id")
    suspend fun getById(id: Long): Activator?

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(activator: Activator): Long

    @Update
    suspend fun update(activator: Activator)

    @Query("DELETE FROM activator WHERE id = :id")
    suspend fun deleteById(id: Long)
}
