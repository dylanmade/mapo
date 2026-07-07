package com.mappo.data.model.steam

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * A stacking overlay on an [ActionSet]. Bindings draw from the parent set; the layer
 * only specifies what's overridden. Multiple layers can be active simultaneously
 * (a stack), with later-activated layers winning conflicts.
 *
 * Layer lifecycle (add/remove/hold) is implemented in Phase 5.
 */
@Entity(
    tableName = "action_layer",
    foreignKeys = [
        ForeignKey(
            entity = ActionSet::class,
            parentColumns = ["id"],
            childColumns = ["parentActionSetId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("parentActionSetId")],
)
data class ActionLayer(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val parentActionSetId: Long,
    val name: String,
    val title: String,
    val orderIndex: Int = 0,
)
