package com.mappo.data.db.steam

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.mappo.data.model.steam.LayerPresetBinding

@Dao
interface LayerPresetBindingDao {

    @Query("SELECT * FROM layer_preset_binding WHERE actionLayerId IN (:actionLayerIds)")
    suspend fun getByActionLayers(actionLayerIds: List<Long>): List<LayerPresetBinding>

    @Query("SELECT * FROM layer_preset_binding WHERE id = :id")
    suspend fun getById(id: Long): LayerPresetBinding?

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(layerPresetBinding: LayerPresetBinding): Long

    @Update
    suspend fun update(layerPresetBinding: LayerPresetBinding)

    @Query("DELETE FROM layer_preset_binding WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query(
        "DELETE FROM layer_preset_binding " +
            "WHERE actionLayerId = :actionLayerId " +
            "AND inputSource = :inputSource " +
            "AND state = :state"
    )
    suspend fun deleteByLayerAndSource(
        actionLayerId: Long,
        inputSource: com.mappo.data.model.steam.InputSource,
        state: String = "active",
    )
}
