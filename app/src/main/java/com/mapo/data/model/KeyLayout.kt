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
    val buttonsJson: String,
    val position: Int = 0,

    val fillEnabled: Boolean = true,
    val fillColorArgb: Int? = null,
    val fillIsAuto: Boolean = true,

    val outlineEnabled: Boolean = false,
    val outlineColorArgb: Int? = null,
    val outlineIsAuto: Boolean = true,

    val bevelEnabled: Boolean = false,
    val bevelColorArgb: Int? = null,
    val bevelIsAuto: Boolean = true,

    val shadowEnabled: Boolean = false,
    val shadowColorArgb: Int? = null,
    val shadowIsAuto: Boolean = true,

    val originalSnapshotJson: String? = null,
)

private val gson = Gson()
private val buttonsType = object : TypeToken<List<GridButton>>() {}.type

fun KeyLayout.toGridLayout(): GridLayout = GridLayout(
    id = id,
    name = name,
    columns = columns,
    rows = rows,
    buttons = gson.fromJson(buttonsJson, buttonsType),
    fillEnabled = fillEnabled,
    fillColorArgb = fillColorArgb,
    fillIsAuto = fillIsAuto,
    outlineEnabled = outlineEnabled,
    outlineColorArgb = outlineColorArgb,
    outlineIsAuto = outlineIsAuto,
    bevelEnabled = bevelEnabled,
    bevelColorArgb = bevelColorArgb,
    bevelIsAuto = bevelIsAuto,
    shadowEnabled = shadowEnabled,
    shadowColorArgb = shadowColorArgb,
    shadowIsAuto = shadowIsAuto,
)

fun GridLayout.toKeyLayout(
    profileId: Long,
    position: Int = 0,
    originalSnapshotJson: String? = null
): KeyLayout = KeyLayout(
    id = id,
    profileId = profileId,
    name = name,
    columns = columns,
    rows = rows,
    buttonsJson = gson.toJson(buttons),
    position = position,
    fillEnabled = fillEnabled,
    fillColorArgb = fillColorArgb,
    fillIsAuto = fillIsAuto,
    outlineEnabled = outlineEnabled,
    outlineColorArgb = outlineColorArgb,
    outlineIsAuto = outlineIsAuto,
    bevelEnabled = bevelEnabled,
    bevelColorArgb = bevelColorArgb,
    bevelIsAuto = bevelIsAuto,
    shadowEnabled = shadowEnabled,
    shadowColorArgb = shadowColorArgb,
    shadowIsAuto = shadowIsAuto,
    originalSnapshotJson = originalSnapshotJson,
)

fun KeyLayout.parseOriginalSnapshot(): LayoutSnapshot? =
    originalSnapshotJson?.let { gson.fromJson(it, LayoutSnapshot::class.java) }
