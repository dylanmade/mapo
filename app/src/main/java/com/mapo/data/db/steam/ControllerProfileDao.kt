package com.mapo.data.db.steam

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.mapo.data.model.steam.ControllerProfile
import kotlinx.coroutines.flow.Flow

@Dao
interface ControllerProfileDao {

    @Query("SELECT * FROM controller_profile WHERE profileId = :profileId")
    fun observeByProfile(profileId: Long): Flow<List<ControllerProfile>>

    @Query("SELECT * FROM controller_profile WHERE profileId = :profileId")
    suspend fun getByProfile(profileId: Long): List<ControllerProfile>

    @Query("SELECT * FROM controller_profile WHERE id = :id")
    suspend fun getById(id: Long): ControllerProfile?

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(profile: ControllerProfile): Long

    @Update
    suspend fun update(profile: ControllerProfile)

    @Query("DELETE FROM controller_profile WHERE id = :id")
    suspend fun deleteById(id: Long)
}
