package com.mapo.data.model.steam

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * An output binding fired by an [Activator]. Multiple bindings per activator are allowed
 * (Steam's `cycle_binding` — each activation rotates through the list).
 *
 * [args] is a Steam-VDF-style CSV payload whose interpretation depends on [outputType]:
 *  - KEY_PRESS:        "KEY_ENTER"
 *  - XINPUT_BUTTON:    "BUTTON_A"
 *  - MOUSE_BUTTON:     "MOUSE_LEFT"
 *  - MOUSE_WHEEL:      "SCROLL_UP" / "SCROLL_DOWN"
 *  - GAME_ACTION:      "<action_set_name>,<action_name>"
 *  - CONTROLLER_ACTION: "<verb>,<arg1>,<arg2>,..."   e.g. "CHANGE_PRESET,1,1"
 *  - UNBOUND:          "" (placeholder for a configured-but-empty slot)
 */
@Entity(
    tableName = "binding",
    foreignKeys = [
        ForeignKey(
            entity = Activator::class,
            parentColumns = ["id"],
            childColumns = ["activatorId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("activatorId")],
)
data class Binding(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val activatorId: Long,
    val outputType: BindingOutputType,
    val args: String = "",
    val label: String? = null,
    val iconRef: String? = null,
    val orderIndex: Int = 0,
)
