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
    val backgroundColorArgb: Int? = null
)

private val gson = Gson()
private val buttonsType = object : TypeToken<List<GridButton>>() {}.type

fun KeyboardTemplate.toUserTemplateRef(): TemplateRef.User = TemplateRef.User(
    id = id,
    name = name,
    columns = columns,
    rows = rows,
    buttons = gson.fromJson(buttonsJson, buttonsType),
    backgroundColorArgb = backgroundColorArgb
)

fun TemplateRef.User.toEntity(): KeyboardTemplate = KeyboardTemplate(
    id = id,
    name = name,
    columns = columns,
    rows = rows,
    buttonsJson = gson.toJson(buttons),
    backgroundColorArgb = backgroundColorArgb
)

fun GridLayout.toNewTemplateEntity(templateName: String): KeyboardTemplate = KeyboardTemplate(
    id = 0,
    name = templateName,
    columns = columns,
    rows = rows,
    buttonsJson = gson.toJson(buttons),
    backgroundColorArgb = backgroundColorArgb
)
