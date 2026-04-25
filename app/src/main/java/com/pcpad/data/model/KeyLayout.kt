package com.pcpad.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "key_layouts")
data class KeyLayout(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val keysJson: String
)
