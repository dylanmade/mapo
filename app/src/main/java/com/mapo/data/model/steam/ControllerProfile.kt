package com.mapo.data.model.steam

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.mapo.data.model.Profile

/**
 * A controller-specific binding configuration belonging to a [Profile].
 * One [Profile] can hold multiple [ControllerProfile]s (e.g., separate configs
 * for the device's built-in pad and an attached Xbox controller). Per-profile
 * "active" controller is tracked separately at runtime (Phase 1 ships one per profile).
 *
 * `legacySet=true` (the common case) means action sets contain concrete output bindings.
 * `legacySet=false` means action sets reference an action manifest; Mapo defers
 * action-manifest hosting (see project_steam_input_features_we_must_reimplement.md).
 */
@Entity(
    tableName = "controller_profile",
    foreignKeys = [
        ForeignKey(
            entity = Profile::class,
            parentColumns = ["id"],
            childColumns = ["profileId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("profileId")],
)
data class ControllerProfile(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val profileId: Long,
    val controllerType: ControllerType,
    val name: String,
    val legacySet: Boolean = true,
)
