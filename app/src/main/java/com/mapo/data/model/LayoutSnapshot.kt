package com.mapo.data.model

import com.google.gson.Gson

data class LayoutSnapshot(
    val name: String,
    val columns: Int,
    val rows: Int,
    val buttons: List<GridButton>,

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

    val defaultButtonAnimationEnabled: Boolean = true,
    val defaultButtonAnimationColorArgb: Int? = null,
    val defaultButtonAnimationIsAuto: Boolean = true,
    val defaultButtonAnimationMotionEnabled: Boolean = true,

    val defaultButtonRegions: Map<String, ButtonRegion> = emptyMap(),
)

private val gson = Gson()

fun GridLayout.toSnapshot(): LayoutSnapshot = LayoutSnapshot(
    name = name,
    columns = columns,
    rows = rows,
    buttons = buttons,
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
    defaultButtonRegions = defaultButtonRegions,
)

fun LayoutSnapshot.toGridLayout(id: Long = 0L): GridLayout = GridLayout(
    id = id,
    name = name,
    columns = columns,
    rows = rows,
    buttons = buttons,
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
    defaultButtonRegions = defaultButtonRegions,
)

fun LayoutSnapshot.toJson(): String = gson.toJson(this)
