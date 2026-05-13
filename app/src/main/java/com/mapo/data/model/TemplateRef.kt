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
)
