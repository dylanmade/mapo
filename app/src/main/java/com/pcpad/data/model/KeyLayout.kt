package com.pcpad.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

@Entity(tableName = "key_layouts")
data class KeyLayout(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val columns: Int,
    val rows: Int,
    val buttonsJson: String
)

private val gson = Gson()

fun KeyLayout.toGridLayout(): GridLayout = GridLayout(
    id = id,
    name = name,
    columns = columns,
    rows = rows,
    buttons = gson.fromJson(buttonsJson, object : TypeToken<List<GridButton>>() {}.type)
)

fun GridLayout.toKeyLayout(): KeyLayout = KeyLayout(
    id = id,
    name = name,
    columns = columns,
    rows = rows,
    buttonsJson = gson.toJson(buttons)
)
