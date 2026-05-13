package com.mapo.data.model

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

@Entity(
    tableName = "keyboard_templates",
    indices = [Index("name", unique = true)]
)
data class KeyboardTemplate(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val columns: Int,
    val rows: Int,
    val buttonsJson: String,

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

    // Default-button template — see [GridLayout] for semantics.
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

    val defaultButtonRegionsJson: String = "{}",
)

private val gson = Gson()
private val buttonsType = object : TypeToken<List<GridButton>>() {}.type
private val regionsType = object : TypeToken<Map<String, ButtonRegion>>() {}.type

fun KeyboardTemplate.toUserTemplateRef(): TemplateRef.User = TemplateRef.User(
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
    defaultButtonRegions = gson.fromJson(defaultButtonRegionsJson, regionsType) ?: emptyMap(),
)

fun TemplateRef.User.toEntity(): KeyboardTemplate = KeyboardTemplate(
    id = id,
    name = name,
    columns = columns,
    rows = rows,
    buttonsJson = gson.toJson(buttons),
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
    defaultButtonRegionsJson = gson.toJson(defaultButtonRegions),
)

fun GridLayout.toNewTemplateEntity(templateName: String): KeyboardTemplate = KeyboardTemplate(
    id = 0,
    name = templateName,
    columns = columns,
    rows = rows,
    buttonsJson = gson.toJson(buttons),
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
    defaultButtonRegionsJson = gson.toJson(defaultButtonRegions),
)
