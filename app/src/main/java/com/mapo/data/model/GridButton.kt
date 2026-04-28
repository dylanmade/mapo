package com.mapo.data.model

import java.util.UUID

data class GridButton(
    val id: String = UUID.randomUUID().toString(),
    val label: String,
    val code: String,
    val col: Int,
    val row: Int,
    val colSpan: Int = 1,
    val rowSpan: Int = 1
)
