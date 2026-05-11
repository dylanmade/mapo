package com.mapo.data.model.steam

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * One activator on a [GroupInput]. Multiple activators per input are allowed
 * (e.g., a FULL_PRESS + a LONG_PRESS on the same button); the runtime evaluator
 * (Phase 2+) handles interruption and timing semantics.
 *
 * [settingsJson] carries universal settings (toggle, fire_start_delay, fire_end_delay,
 * haptic_intensity, cycle_binding) plus type-specific settings (long_press_time,
 * double_tap_time, interruptable, hold_to_repeat, repeat_rate). Parsed by
 * sealed-class wrappers in the repository layer.
 */
@Entity(
    tableName = "activator",
    foreignKeys = [
        ForeignKey(
            entity = GroupInput::class,
            parentColumns = ["id"],
            childColumns = ["groupInputId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("groupInputId")],
)
data class Activator(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val groupInputId: Long,
    val type: ActivatorType,
    val settingsJson: String = "{}",
    val orderIndex: Int = 0,
)
