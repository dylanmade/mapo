package com.mapo.data.model.steam

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Maps a physical [InputSource] on an [ActionLayer] to a [BindingGroup], state-qualified.
 *
 * The layer-side parallel to [PresetBinding] (Brick 5.5.a). Each row represents one
 * override on a layer — a single input source pointing at a layer-owned
 * [BindingGroup]. At runtime the evaluator walks the active layer stack top-down,
 * preferring a layer's override (if present for the address) over the underlying
 * layer or base set's binding.
 *
 * **Why a separate table** (rather than nullable layer id on `preset_binding`): keeps
 * each table's purpose unambiguous, and lets both base + layer paths have real
 * FK enforcement. The "polymorphic owner" alternative (cf. `BindingGroup.ownerKind`)
 * was considered; the inconsistency is logged in
 * `project_schema_polymorphic_owner_audit_deferred` for post-parity review.
 *
 * [state] mirrors [PresetBinding.state] semantics (active/inactive/modeshift). For
 * Phase 5 only "active" is meaningful for layers; mode-shift is Phase 6.
 */
@Entity(
    tableName = "layer_preset_binding",
    foreignKeys = [
        ForeignKey(
            entity = ActionLayer::class,
            parentColumns = ["id"],
            childColumns = ["actionLayerId"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = BindingGroup::class,
            parentColumns = ["id"],
            childColumns = ["bindingGroupId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("actionLayerId"), Index("bindingGroupId")],
)
data class LayerPresetBinding(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val actionLayerId: Long,
    val inputSource: InputSource,
    val state: String = "active",
    val bindingGroupId: Long,
)
