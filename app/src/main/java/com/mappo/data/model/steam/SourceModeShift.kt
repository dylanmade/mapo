package com.mappo.data.model.steam

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Phase 7 Brick B.5 — a Steam-Input-style mode shift definition.
 *
 * A mode shift is a *while-held* override on a single source. When the
 * physical input identified by `(triggerSource, triggerSubInput)` is pressed,
 * the [ownerSource] swaps from whatever its base mode/bindings are to the
 * [bindingGroupId] target group's mode/bindings. On release, it reverts.
 *
 * Mode shifts are owned by either an [ActionSet] (always-available within
 * that set) or an [ActionLayer] (only available while that layer is in the
 * active stack). Exactly one of [actionSetId] / [actionLayerId] is non-null
 * — mirrors the polymorphic ownership pattern in [BindingGroup].
 *
 * A source can have multiple mode shifts ([displayOrder] gives stable
 * ordering for the editor UI; runtime activates each one whose trigger
 * matches the current press). A single trigger can drive multiple shifts
 * targeting different owner sources (e.g. RB shifts both LJ and RJ).
 *
 * **Trigger nullability.** A freshly-created mode shift starts with
 * `triggerSource = null, triggerSubInput = null`. The editor UI displays
 * "(Trigger unassigned)" in that state. Until both are non-null, the
 * compile path skips the shift — it has no addressable trigger, so it can
 * never activate.
 *
 * **Why not encoded on a binding output.** Steam Input's UI configures
 * mode shifts on the source side ("+ Add Mode Shift" next to the source's
 * mode dropdown), not as an output of a button binding. A button can have
 * its normal bindings AND simultaneously trigger a mode shift — the
 * binding and the mode shift are orthogonal data, not the same row.
 */
@Entity(
    tableName = "source_mode_shift",
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
        ForeignKey(
            entity = BindingGroup::class,
            parentColumns = ["id"],
            childColumns = ["bindingGroupId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index("actionSetId"),
        Index("actionLayerId"),
        Index("bindingGroupId"),
    ],
)
data class SourceModeShift(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val actionSetId: Long? = null,
    val actionLayerId: Long? = null,
    val ownerSource: InputSource,
    val triggerSource: InputSource? = null,
    val triggerSubInput: String? = null,
    val bindingGroupId: Long,
    val displayOrder: Int = 0,
)
