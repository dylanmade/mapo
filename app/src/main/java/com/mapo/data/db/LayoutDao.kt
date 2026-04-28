package com.mapo.data.db

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.mapo.data.model.KeyLayout
import kotlinx.coroutines.flow.Flow

@Dao
interface LayoutDao {

    @Query("SELECT * FROM key_layouts WHERE profileId = :profileId ORDER BY name ASC")
    fun getByProfile(profileId: Long): Flow<List<KeyLayout>>

    @Query("SELECT * FROM key_layouts WHERE profileId = :profileId ORDER BY name ASC")
    suspend fun getByProfileOnce(profileId: Long): List<KeyLayout>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(layout: KeyLayout)

    @Delete
    suspend fun delete(layout: KeyLayout)
}
