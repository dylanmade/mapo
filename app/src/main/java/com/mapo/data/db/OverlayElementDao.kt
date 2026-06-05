package com.mapo.data.db

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.mapo.data.model.OverlayElement
import kotlinx.coroutines.flow.Flow

@Dao
interface OverlayElementDao {

    @Query("SELECT * FROM overlay_elements WHERE profileId = :profileId ORDER BY zIndex ASC, id ASC")
    fun getByProfile(profileId: Long): Flow<List<OverlayElement>>

    @Query("SELECT * FROM overlay_elements WHERE profileId = :profileId ORDER BY zIndex ASC, id ASC")
    suspend fun getByProfileOnce(profileId: Long): List<OverlayElement>

    @Query("SELECT * FROM overlay_elements WHERE id = :id LIMIT 1")
    suspend fun getById(id: Long): OverlayElement?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(element: OverlayElement): Long

    @Update
    suspend fun update(element: OverlayElement)

    @Delete
    suspend fun delete(element: OverlayElement)

    @Query("DELETE FROM overlay_elements WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("DELETE FROM overlay_elements WHERE profileId = :profileId")
    suspend fun deleteAllForProfile(profileId: Long)
}
