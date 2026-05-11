package com.mapo.data.db.steam

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.mapo.data.model.steam.GroupInput

@Dao
interface GroupInputDao {

    @Query("SELECT * FROM group_input WHERE bindingGroupId IN (:bindingGroupIds) ORDER BY orderIndex")
    suspend fun getByGroups(bindingGroupIds: List<Long>): List<GroupInput>

    @Query("SELECT * FROM group_input WHERE id = :id")
    suspend fun getById(id: Long): GroupInput?

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(input: GroupInput): Long

    @Query("DELETE FROM group_input WHERE id = :id")
    suspend fun deleteById(id: Long)
}
