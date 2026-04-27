package com.pcpad.data.model

data class GridLayout(
    val id: Long = 0L,
    val name: String,
    val columns: Int,
    val rows: Int,
    val buttons: List<GridButton>
)
