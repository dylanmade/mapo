package com.mappo.data.model.steam

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * A binding payload with a [mode]. Lives under either an [ActionSet] (base bindings)
 * or an [ActionLayer] (overrides). Exactly one of [actionSetId] and [actionLayerId]
 * is non-null — Room expresses polymorphic ownership via two nullable FKs so cascades
 * still work on the SQL side.
 *
 * [settingsJson] holds mode-specific settings (deadzones, sensitivities, response
 * curves, etc.) as a single JSON blob. Kept as a string here; parsed by sealed-class
 * wrappers in the repository layer (Phase 1 brick 1.2 onward).
 */
@Entity(
    tableName = "binding_group",
    foreignKeys = [
        ForeignKey(
            entity = ActionSet::class,
            parentColumns = ["id"],
            childColumns = ["actionSetId"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = ActionLayer::class,
            parentColumns = ["id"],
            childColumns = ["actionLayerId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("actionSetId"), Index("actionLayerId")],
)
data class BindingGroup(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val actionSetId: Long? = null,
    val actionLayerId: Long? = null,
    val name: String,
    val mode: BindingMode,
    val settingsJson: String = "{}",
)
