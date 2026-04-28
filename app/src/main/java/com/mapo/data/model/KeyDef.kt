package com.mapo.data.model

data class KeyDef(
    val label: String,
    val code: String,
    val weight: Float = 1f,
    val topText: String = ""
)
