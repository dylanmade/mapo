package com.mapo.data.model

import com.google.gson.Gson

data class LayoutSnapshot(
    val name: String,
    val columns: Int,
    val rows: Int,
    val backgroundColorArgb: Int?,
    val buttons: List<GridButton>
)

private val gson = Gson()

fun GridLayout.toSnapshot(): LayoutSnapshot = LayoutSnapshot(
    name = name,
    columns = columns,
    rows = rows,
    backgroundColorArgb = backgroundColorArgb,
    buttons = buttons
)

fun LayoutSnapshot.toGridLayout(id: Long = 0L): GridLayout = GridLayout(
    id = id,
    name = name,
    columns = columns,
    rows = rows,
    buttons = buttons,
    backgroundColorArgb = backgroundColorArgb
)

fun LayoutSnapshot.toJson(): String = gson.toJson(this)
