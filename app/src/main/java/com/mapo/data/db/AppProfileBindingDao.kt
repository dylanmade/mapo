package com.mapo.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.mapo.data.model.AppProfileBinding
import kotlinx.coroutines.flow.Flow

@Dao
interface AppProfileBindingDao {

    @Query("SELECT * FROM app_profile_bindings ORDER BY packageName ASC")
    fun getAll(): Flow<List<AppProfileBinding>>

    @Query("SELECT * FROM app_profile_bindings WHERE packageName = :packageName")
    fun getForPackage(packageName: String): Flow<List<AppProfileBinding>>

    @Query("SELECT * FROM app_profile_bindings WHERE packageName = :packageName AND subId = :subId LIMIT 1")
    suspend fun getForPackageOnce(packageName: String, subId: String = ""): AppProfileBinding?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(binding: AppProfileBinding)

    @Query("DELETE FROM app_profile_bindings WHERE packageName = :packageName AND subId = :subId")
    suspend fun delete(packageName: String, subId: String = "")

    @Query("DELETE FROM app_profile_bindings WHERE profileId = :profileId")
    suspend fun deleteAllForProfile(profileId: Long)
}
