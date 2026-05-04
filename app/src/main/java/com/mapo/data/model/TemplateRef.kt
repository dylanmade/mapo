package com.mapo.data.model

sealed class TemplateRef {
    abstract val name: String
    abstract val columns: Int
    abstract val rows: Int
    abstract val buttons: List<GridButton>
    abstract val backgroundColorArgb: Int?

    data class BuiltIn(
        val key: String,
        override val name: String,
        override val columns: Int,
        override val rows: Int,
        override val buttons: List<GridButton>,
        override val backgroundColorArgb: Int? = null
    ) : TemplateRef()

    data class User(
        val id: Long,
        override val name: String,
        override val columns: Int,
        override val rows: Int,
        override val buttons: List<GridButton>,
        override val backgroundColorArgb: Int?
    ) : TemplateRef()
}

fun TemplateRef.toGridLayout(id: Long = 0L): GridLayout = GridLayout(
    id = id,
    name = name,
    columns = columns,
    rows = rows,
    buttons = buttons,
    backgroundColorArgb = backgroundColorArgb
)

fun TemplateRef.toSnapshot(): LayoutSnapshot = LayoutSnapshot(
    name = name,
    columns = columns,
    rows = rows,
    backgroundColorArgb = backgroundColorArgb,
    buttons = buttons
)
