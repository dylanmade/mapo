package com.mappo.data.model.steam

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Maps a physical [InputSource] on an [ActionSet] to a [BindingGroup], state-qualified.
 *
 * [state] mirrors Steam's preset-binding state qualifier. Common values:
 *  - "active"     — applies while the action set is active and not mode-shifted
 *  - "inactive"   — applies when the action set is inactive (rare)
 *  - "modeshift"  — applies while this source is mode-shifted
 *  - comma-joined combinations for compound states
 */
@Entity(
    tableName = "preset_binding",
    foreignKeys = [
        ForeignKey(
            entity = ActionSet::class,
            parentColumns = ["id"],
            childColumns = ["actionSetId"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = BindingGroup::class,
            parentColumns = ["id"],
            childColumns = ["bindingGroupId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("actionSetId"), Index("bindingGroupId")],
)
data class PresetBinding(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val actionSetId: Long,
    val inputSource: InputSource,
    val state: String = "active",
    val bindingGroupId: Long,
)
