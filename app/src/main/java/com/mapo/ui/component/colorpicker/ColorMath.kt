package com.mapo.ui.component.colorpicker

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb

/**
 * Color conversion + hex helpers for the Mapo color picker.
 *
 * HSV conversions delegate to `android.graphics.Color` (battle-tested platform math) rather
 * than re-deriving the algorithm by hand, so the wheel/HSV controls stay numerically exact.
 */

/** HSV components of this color: `[hue 0..360, saturation 0..1, value 0..1]`. Alpha ignored. */
internal fun Color.toHsv(): FloatArray {
    val hsv = FloatArray(3)
    android.graphics.Color.colorToHSV(toArgb(), hsv)
    return hsv
}

/** Build a color from HSV (hue 0..360, sat/value 0..1) plus an 0..255 alpha. */
internal fun hsvToColor(hue: Float, saturation: Float, value: Float, alpha: Int): Color {
    val argb = android.graphics.Color.HSVToColor(
        alpha.coerceIn(0, 255),
        floatArrayOf(hue.coerceIn(0f, 360f), saturation.coerceIn(0f, 1f), value.coerceIn(0f, 1f)),
    )
    return Color(argb)
}

/** `#RRGGBB` when fully opaque, otherwise `#AARRGGBB`. Uppercase, leading `#`. */
internal fun Color.toHexString(): String {
    val argb = toArgb()
    val a = (argb ushr 24) and 0xFF
    return if (a == 0xFF) "#%06X".format(argb and 0xFFFFFF)
    else "#%08X".format(argb)
}

/** Parse `RRGGBB` / `AARRGGBB` (optional leading `#`). Null when malformed. */
internal fun parseHexColor(input: String): Color? {
    val s = input.trim().removePrefix("#")
    return when (s.length) {
        6 -> runCatching { Color(0xFF000000.toInt() or s.toInt(16)) }.getOrNull()
        8 -> runCatching { Color(s.toLong(16).toInt()) }.getOrNull()
        else -> null
    }
}
