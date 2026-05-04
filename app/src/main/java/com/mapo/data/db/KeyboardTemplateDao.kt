package com.mapo.data.db

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.mapo.data.model.KeyboardTemplate
import kotlinx.coroutines.flow.Flow

@Dao
interface KeyboardTemplateDao {

    @Query("SELECT * FROM keyboard_templates ORDER BY name ASC")
    fun getAll(): Flow<List<KeyboardTemplate>>

    @Query("SELECT * FROM keyboard_templates ORDER BY name ASC")
    suspend fun getAllOnce(): List<KeyboardTemplate>

    @Query("SELECT * FROM keyboard_templates WHERE name = :name LIMIT 1")
    suspend fun getByNameOnce(name: String): KeyboardTemplate?

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(template: KeyboardTemplate): Long

    @Update
    suspend fun update(template: KeyboardTemplate)

    @Delete
    suspend fun delete(template: KeyboardTemplate)
}
