package com.pcpad.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.pcpad.data.model.GamepadMapping
import kotlinx.coroutines.flow.Flow

@Dao
interface GamepadMappingDao {

    @Query("SELECT * FROM gamepad_mappings WHERE profileId = :profileId")
    fun getByProfile(profileId: Long): Flow<List<GamepadMapping>>

    @Query("SELECT * FROM gamepad_mappings WHERE profileId = :profileId")
    suspend fun getByProfileOnce(profileId: Long): List<GamepadMapping>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(mappings: List<GamepadMapping>)

    @Query("DELETE FROM gamepad_mappings WHERE profileId = :profileId")
    suspend fun deleteAllForProfile(profileId: Long)
}
