package com.mapo.data.db.steam

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.mapo.data.model.steam.Binding

@Dao
interface BindingDao {

    @Query("SELECT * FROM binding WHERE activatorId IN (:activatorIds) ORDER BY orderIndex")
    suspend fun getByActivators(activatorIds: List<Long>): List<Binding>

    @Query("SELECT * FROM binding WHERE id = :id")
    suspend fun getById(id: Long): Binding?

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(binding: Binding): Long

    @Update
    suspend fun update(binding: Binding)

    @Query("DELETE FROM binding WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("DELETE FROM binding WHERE activatorId = :activatorId")
    suspend fun deleteByActivator(activatorId: Long)
}
