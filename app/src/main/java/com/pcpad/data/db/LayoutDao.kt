package com.pcpad.data.db

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.pcpad.data.model.KeyLayout
import kotlinx.coroutines.flow.Flow

@Dao
interface LayoutDao {

    @Query("SELECT * FROM key_layouts ORDER BY name ASC")
    fun getAll(): Flow<List<KeyLayout>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(layout: KeyLayout)

    @Delete
    suspend fun delete(layout: KeyLayout)
}
