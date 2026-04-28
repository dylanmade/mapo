package com.mapo.data.model

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

@Entity(
    tableName = "key_layouts",
    foreignKeys = [ForeignKey(
        entity = Profile::class,
        parentColumns = ["id"],
        childColumns = ["profileId"],
        onDelete = ForeignKey.CASCADE
    )],
    indices = [Index("profileId")]
)
data class KeyLayout(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val profileId: Long,
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

fun GridLayout.toKeyLayout(profileId: Long): KeyLayout = KeyLayout(
    id = id,
    profileId = profileId,
    name = name,
    columns = columns,
    rows = rows,
    buttonsJson = gson.toJson(buttons)
)
