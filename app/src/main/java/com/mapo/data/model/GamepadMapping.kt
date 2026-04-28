package com.mapo.data.model

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index

@Entity(
    tableName = "gamepad_mappings",
    primaryKeys = ["profileId", "gamepadButton"],
    foreignKeys = [ForeignKey(
        entity = Profile::class,
        parentColumns = ["id"],
        childColumns = ["profileId"],
        onDelete = ForeignKey.CASCADE
    )],
    indices = [Index("profileId")]
)
data class GamepadMapping(
    val profileId: Long,
    val gamepadButton: String,
    val targetEncoded: String
)
