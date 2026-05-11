package com.mapo.data.model.steam

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * A mutually-exclusive grouping of bindings within a [ControllerProfile].
 * At any moment, exactly one action set is "active" per controller profile.
 * Switching action sets (via a `CHANGE_PRESET` binding — Phase 4) clears all active layers.
 */
@Entity(
    tableName = "action_set",
    foreignKeys = [
        ForeignKey(
            entity = ControllerProfile::class,
            parentColumns = ["id"],
            childColumns = ["controllerProfileId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("controllerProfileId")],
)
data class ActionSet(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val controllerProfileId: Long,
    val name: String,
    val title: String,
    val legacy: Boolean = true,
    val orderIndex: Int = 0,
)
