package com.mapo.data.model

import java.util.UUID

data class GridButton(
    val id: String = UUID.randomUUID().toString(),
    val label: String,
    val code: String,
    val col: Int,
    val row: Int,
    val colSpan: Int = 1,
    val rowSpan: Int = 1,
    // Nullable so Gson deserializing old data without these fields gives null (not NPE)
    val topText: String? = null,
    val topAlign: String? = null,    // null / "CENTER" / "LEFT" / "RIGHT"
    val bottomText: String? = null,
    val bottomAlign: String? = null
)
