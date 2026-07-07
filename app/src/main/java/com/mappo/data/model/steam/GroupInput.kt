package com.mappo.data.model.steam

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * A sub-input within a [BindingGroup]. The set of valid [inputKey] values
 * depends on the group's [BindingMode]:
 *
 *  - SINGLE_BUTTON: "click"
 *  - DPAD: "dpad_north" / "_south" / "_east" / "_west" / "click"
 *  - BUTTON_PAD: "button_a" / "_b" / "_x" / "_y"
 *  - JOYSTICK_*: "click", "edge"
 *  - TRIGGER: "click", "soft_pull"
 *  - SCROLL_WHEEL: "scroll_clockwise", "scroll_counter_clockwise", "click"
 *  - RADIAL_MENU / TOUCH_MENU: "touch_menu_button_<N>" (0-indexed)
 *  - REFERENCE: (none — alias group)
 */
@Entity(
    tableName = "group_input",
    foreignKeys = [
        ForeignKey(
            entity = BindingGroup::class,
            parentColumns = ["id"],
            childColumns = ["bindingGroupId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("bindingGroupId")],
)
data class GroupInput(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val bindingGroupId: Long,
    val inputKey: String,
    val orderIndex: Int = 0,
)
