package com.mapo.data.model

sealed class TemplateRef {
    abstract val name: String
    abstract val columns: Int
    abstract val rows: Int
    abstract val buttons: List<GridButton>

    abstract val fillEnabled: Boolean
    abstract val fillColorArgb: Int?
    abstract val fillIsAuto: Boolean

    abstract val outlineEnabled: Boolean
    abstract val outlineColorArgb: Int?
    abstract val outlineIsAuto: Boolean

    abstract val bevelEnabled: Boolean
    abstract val bevelColorArgb: Int?
    abstract val bevelIsAuto: Boolean

    abstract val shadowEnabled: Boolean
    abstract val shadowColorArgb: Int?
    abstract val shadowIsAuto: Boolean

    // Default-button template carried through save-as-template / instantiate so a
    // keyboard's customized "Buttons tab" defaults survive the round-trip.
    abstract val defaultButtonColSpan: Int
    abstract val defaultButtonRowSpan: Int

    abstract val defaultButtonFillEnabled: Boolean
    abstract val defaultButtonFillColorArgb: Int?
    abstract val defaultButtonFillIsAuto: Boolean

    abstract val defaultButtonOutlineEnabled: Boolean
    abstract val defaultButtonOutlineColorArgb: Int?
    abstract val defaultButtonOutlineIsAuto: Boolean

    abstract val defaultButtonBevelEnabled: Boolean
    abstract val defaultButtonBevelColorArgb: Int?
    abstract val defaultButtonBevelIsAuto: Boolean

    abstract val defaultButtonShadowEnabled: Boolean
    abstract val defaultButtonShadowColorArgb: Int?
    abstract val defaultButtonShadowIsAuto: Boolean

    abstract val defaultButtonRegions: Map<String, ButtonRegion>

    data class BuiltIn(
        val key: String,
        override val name: String,
        override val columns: Int,
        override val rows: Int,
        override val buttons: List<GridButton>,
        override val fillEnabled: Boolean = true,
        override val fillColorArgb: Int? = null,
        override val fillIsAuto: Boolean = true,
        override val outlineEnabled: Boolean = false,
        override val outlineColorArgb: Int? = null,
        override val outlineIsAuto: Boolean = true,
        override val bevelEnabled: Boolean = false,
        override val bevelColorArgb: Int? = null,
        override val bevelIsAuto: Boolean = true,
        override val shadowEnabled: Boolean = false,
        override val shadowColorArgb: Int? = null,
        override val shadowIsAuto: Boolean = true,
        override val defaultButtonColSpan: Int = 1,
        override val defaultButtonRowSpan: Int = 1,
        override val defaultButtonFillEnabled: Boolean = true,
        override val defaultButtonFillColorArgb: Int? = null,
        override val defaultButtonFillIsAuto: Boolean = true,
        override val defaultButtonOutlineEnabled: Boolean = false,
        override val defaultButtonOutlineColorArgb: Int? = null,
        override val defaultButtonOutlineIsAuto: Boolean = true,
        override val defaultButtonBevelEnabled: Boolean = true,
        override val defaultButtonBevelColorArgb: Int? = null,
        override val defaultButtonBevelIsAuto: Boolean = true,
        override val defaultButtonShadowEnabled: Boolean = true,
        override val defaultButtonShadowColorArgb: Int? = null,
        override val defaultButtonShadowIsAuto: Boolean = true,
        override val defaultButtonRegions: Map<String, ButtonRegion> = emptyMap(),
    ) : TemplateRef()

    data class User(
        val id: Long,
        override val name: String,
        override val columns: Int,
        override val rows: Int,
        override val buttons: List<GridButton>,
        override val fillEnabled: Boolean = true,
        override val fillColorArgb: Int? = null,
        override val fillIsAuto: Boolean = true,
        override val outlineEnabled: Boolean = false,
        override val outlineColorArgb: Int? = null,
        override val outlineIsAuto: Boolean = true,
        override val bevelEnabled: Boolean = false,
        override val bevelColorArgb: Int? = null,
        override val bevelIsAuto: Boolean = true,
        override val shadowEnabled: Boolean = false,
        override val shadowColorArgb: Int? = null,
        override val shadowIsAuto: Boolean = true,
        override val defaultButtonColSpan: Int = 1,
        override val defaultButtonRowSpan: Int = 1,
        override val defaultButtonFillEnabled: Boolean = true,
        override val defaultButtonFillColorArgb: Int? = null,
        override val defaultButtonFillIsAuto: Boolean = true,
        override val defaultButtonOutlineEnabled: Boolean = false,
        override val defaultButtonOutlineColorArgb: Int? = null,
        override val defaultButtonOutlineIsAuto: Boolean = true,
        override val defaultButtonBevelEnabled: Boolean = true,
        override val defaultButtonBevelColorArgb: Int? = null,
        override val defaultButtonBevelIsAuto: Boolean = true,
        override val defaultButtonShadowEnabled: Boolean = true,
        override val defaultButtonShadowColorArgb: Int? = null,
        override val defaultButtonShadowIsAuto: Boolean = true,
        override val defaultButtonRegions: Map<String, ButtonRegion> = emptyMap(),
    ) : TemplateRef()
}

fun TemplateRef.toGridLayout(id: Long = 0L): GridLayout = GridLayout(
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
    defaultButtonRegions = defaultButtonRegions,
)

fun TemplateRef.toSnapshot(): LayoutSnapshot = LayoutSnapshot(
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
    defaultButtonRegions = defaultButtonRegions,
)
