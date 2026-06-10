package com.mapo.ui.component.colorpicker

import androidx.compose.runtime.mutableStateListOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb

/**
 * Process-lifetime store of recently picked colors, surfaced as the "Recent" section of the
 * Colors tab. Updated on Save. In-memory only for now (survives dialog reopen + navigation, not
 * process death); a persistent backing can be swapped in when the picker is wired app-wide.
 */
internal object ColorPickerRecents {
    private const val LIMIT = 24

    /** Most-recent-first. A [androidx.compose.runtime.snapshots.SnapshotStateList] so the UI recomposes. */
    val colors = mutableStateListOf<Color>()

    /** Record [color] as most-recent, de-duping by ARGB and capping at [LIMIT]. */
    fun add(color: Color) {
        val key = color.toArgb()
        colors.removeAll { it.toArgb() == key }
        colors.add(0, color)
        while (colors.size > LIMIT) colors.removeAt(colors.lastIndex)
    }
}

/**
 * A Photoshop-style "Common" palette: each row is one hue ramped light → dark (tints toward
 * white, then the base, then shades toward black), plus a neutral grayscale row. Generated from a
 * compact set of base hues so there are no hand-maintained hex tables.
 */
internal object CommonSwatches {
    private fun lerp(a: Color, b: Color, t: Float): Color = Color(
        red = a.red + (b.red - a.red) * t,
        green = a.green + (b.green - a.green) * t,
        blue = a.blue + (b.blue - a.blue) * t,
        alpha = 1f,
    )

    /** 4 tints (→white) + base + 4 shades (→black) = a 9-swatch light→dark row. */
    private fun ramp(base: Color): List<Color> {
        val tints = listOf(0.82f, 0.62f, 0.40f, 0.20f).map { lerp(base, Color.White, it) }
        val shades = listOf(0.18f, 0.34f, 0.50f, 0.66f).map { lerp(base, Color.Black, it) }
        return tints + base + shades
    }

    private val bases = listOf(
        Color(0xFFF44336), // red
        Color(0xFFE91E63), // pink
        Color(0xFF9C27B0), // purple
        Color(0xFF673AB7), // deep purple
        Color(0xFF3F51B5), // indigo
        Color(0xFF2196F3), // blue
        Color(0xFF03A9F4), // light blue
        Color(0xFF00BCD4), // cyan
        Color(0xFF009688), // teal
        Color(0xFF4CAF50), // green
        Color(0xFF8BC34A), // light green
        Color(0xFFCDDC39), // lime
        Color(0xFFFFEB3B), // yellow
        Color(0xFFFFC107), // amber
        Color(0xFFFF9800), // orange
        Color(0xFFFF5722), // deep orange
        Color(0xFF795548), // brown
        Color(0xFF607D8B), // blue grey
    )

    private val grayscaleRow =
        listOf(1f, 0.88f, 0.74f, 0.60f, 0.46f, 0.32f, 0.20f, 0.10f, 0f).map { Color(it, it, it, 1f) }

    /** All rows, light→dark per hue, with the neutral ramp last. */
    val rows: List<List<Color>> = bases.map { ramp(it) } + listOf(grayscaleRow)
}
