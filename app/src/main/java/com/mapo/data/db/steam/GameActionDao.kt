package com.mapo.data.db.steam

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.mapo.data.model.steam.GameAction

@Dao
interface GameActionDao {

    @Query("SELECT * FROM game_action WHERE actionSetId IN (:actionSetIds)")
    suspend fun getByActionSets(actionSetIds: List<Long>): List<GameAction>

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(action: GameAction): Long

    @Query("DELETE FROM game_action WHERE id = :id")
    suspend fun deleteById(id: Long)
}
