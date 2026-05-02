package com.mapo.data.db

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.mapo.data.model.Profile
import kotlinx.coroutines.flow.Flow

@Dao
interface ProfileDao {

    @Query("SELECT * FROM profiles ORDER BY name ASC")
    fun getAll(): Flow<List<Profile>>

    @Query("SELECT * FROM profiles WHERE isDefault = 1 LIMIT 1")
    fun getDefault(): Flow<Profile?>

    @Query("SELECT * FROM profiles WHERE id = :id LIMIT 1")
    suspend fun getById(id: Long): Profile?

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(profile: Profile): Long

    @Delete
    suspend fun delete(profile: Profile)
}
