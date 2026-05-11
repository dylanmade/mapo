package com.mapo.data.model.steam

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Action-based mode only (`ActionSet.legacy = false`). Names the action namespace
 * an action set exposes — bindings then target these actions instead of concrete outputs.
 * Mapo defers action-based imports until an action-manifest registry is in place,
 * so in practice this table stays empty in legacy configs.
 *
 * Kept in Phase 1's schema for forward compatibility and faithful VDF parsing.
 */
@Entity(
    tableName = "game_action",
    foreignKeys = [
        ForeignKey(
            entity = ActionSet::class,
            parentColumns = ["id"],
            childColumns = ["actionSetId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("actionSetId")],
)
data class GameAction(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val actionSetId: Long,
    val category: String,
    val name: String,
    val inputMode: String? = null,
    val localizationToken: String? = null,
)
