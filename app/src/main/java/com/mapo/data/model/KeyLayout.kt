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

    // Default-button template fields — see [GridLayout] for semantics.
    val defaultButtonColSpan: Int = 1,
    val defaultButtonRowSpan: Int = 1,

    val defaultButtonFillEnabled: Boolean = true,
    val defaultButtonFillColorArgb: Int? = null,
    val defaultButtonFillIsAuto: Boolean = true,

    val defaultButtonOutlineEnabled: Boolean = false,
    val defaultButtonOutlineColorArgb: Int? = null,
    val defaultButtonOutlineIsAuto: Boolean = true,

    val defaultButtonBevelEnabled: Boolean = true,
    val defaultButtonBevelColorArgb: Int? = null,
    val defaultButtonBevelIsAuto: Boolean = true,

    val defaultButtonShadowEnabled: Boolean = true,
    val defaultButtonShadowColorArgb: Int? = null,
    val defaultButtonShadowIsAuto: Boolean = true,

    val defaultButtonAnimationEnabled: Boolean = true,
    val defaultButtonAnimationColorArgb: Int? = null,
    val defaultButtonAnimationIsAuto: Boolean = true,
    val defaultButtonAnimationMotionEnabled: Boolean = true,

    // Regions are a Map keyed by RegionPosition.name; persisted as JSON like buttonsJson
    // since Room doesn't natively support Map columns.
    val defaultButtonRegionsJson: String = "{}",

    val originalSnapshotJson: String? = null,
)

private val gson = Gson()
private val buttonsType = object : TypeToken<List<GridButton>>() {}.type
private val regionsType = object : TypeToken<Map<String, ButtonRegion>>() {}.type

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
    defaultButtonColSpan = defaultButtonColSpan,
    defaultButtonRowSpan = defaultButtonRowSpan,
    defaultButtonFillEnabled = defaultButtonFillEnabled,
    defaultButtonFillColorArgb = defaultButtonFillColorArgb,
    defaultButtonFillIsAuto = defaultButtonFillIsAuto,
    defaultButtonOutlineEnabled = defaultButtonOutlineEnabled,
    defaultButtonOutlineColorArgb = defaultButtonOutlineColorArgb,
    defaultButtonOutlineIsAuto = defaultButtonOutlineIsAuto,
    defaultButtonBevelEnabled = defaultButtonBevelEnabled,
    defaultButtonBevelColorArgb = defaultButtonBevelColorArgb,
    defaultButtonBevelIsAuto = defaultButtonBevelIsAuto,
    defaultButtonShadowEnabled = defaultButtonShadowEnabled,
    defaultButtonShadowColorArgb = defaultButtonShadowColorArgb,
    defaultButtonShadowIsAuto = defaultButtonShadowIsAuto,
    defaultButtonAnimationEnabled = defaultButtonAnimationEnabled,
    defaultButtonAnimationColorArgb = defaultButtonAnimationColorArgb,
    defaultButtonAnimationIsAuto = defaultButtonAnimationIsAuto,
    defaultButtonAnimationMotionEnabled = defaultButtonAnimationMotionEnabled,
    defaultButtonRegions = gson.fromJson(defaultButtonRegionsJson, regionsType) ?: emptyMap(),
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
    defaultButtonColSpan = defaultButtonColSpan,
    defaultButtonRowSpan = defaultButtonRowSpan,
    defaultButtonFillEnabled = defaultButtonFillEnabled,
    defaultButtonFillColorArgb = defaultButtonFillColorArgb,
    defaultButtonFillIsAuto = defaultButtonFillIsAuto,
    defaultButtonOutlineEnabled = defaultButtonOutlineEnabled,
    defaultButtonOutlineColorArgb = defaultButtonOutlineColorArgb,
    defaultButtonOutlineIsAuto = defaultButtonOutlineIsAuto,
    defaultButtonBevelEnabled = defaultButtonBevelEnabled,
    defaultButtonBevelColorArgb = defaultButtonBevelColorArgb,
    defaultButtonBevelIsAuto = defaultButtonBevelIsAuto,
    defaultButtonShadowEnabled = defaultButtonShadowEnabled,
    defaultButtonShadowColorArgb = defaultButtonShadowColorArgb,
    defaultButtonShadowIsAuto = defaultButtonShadowIsAuto,
    defaultButtonAnimationEnabled = defaultButtonAnimationEnabled,
    defaultButtonAnimationColorArgb = defaultButtonAnimationColorArgb,
    defaultButtonAnimationIsAuto = defaultButtonAnimationIsAuto,
    defaultButtonAnimationMotionEnabled = defaultButtonAnimationMotionEnabled,
    defaultButtonRegionsJson = gson.toJson(defaultButtonRegions),
    originalSnapshotJson = originalSnapshotJson,
)

fun KeyLayout.parseOriginalSnapshot(): LayoutSnapshot? =
    originalSnapshotJson?.let { gson.fromJson(it, LayoutSnapshot::class.java) }
