package com.mapo.ui.component.colorpicker

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb

/**
 * Color conversion + hex helpers for the Mapo color picker.
 *
 * The picker uses the **HSL** model (hue / saturation / lightness): the third channel runs
 * black → full color → white, so maximum lightness is always pure white regardless of hue.
 */

/** HSL components of this color: `[hue 0..360, saturation 0..1, lightness 0..1]`. Alpha ignored. */
internal fun Color.toHsl(): FloatArray {
    val r = red
    val g = green
    val b = blue
    val max = maxOf(r, g, b)
    val min = minOf(r, g, b)
    val l = (max + min) / 2f
    if (max == min) return floatArrayOf(0f, 0f, l)
    val d = max - min
    val s = if (l > 0.5f) d / (2f - max - min) else d / (max + min)
    val h = when (max) {
        r -> (g - b) / d + if (g < b) 6f else 0f
        g -> (b - r) / d + 2f
        else -> (r - g) / d + 4f
    } * 60f
    return floatArrayOf(h, s, l)
}

/** Build a color from HSL (hue 0..360, sat/lightness 0..1) plus an 0..255 alpha. */
internal fun hslToColor(hue: Float, saturation: Float, lightness: Float, alpha: Int): Color {
    val h = ((hue % 360f) + 360f) % 360f
    val s = saturation.coerceIn(0f, 1f)
    val l = lightness.coerceIn(0f, 1f)
    val a = alpha.coerceIn(0, 255) / 255f
    if (s == 0f) return Color(red = l, green = l, blue = l, alpha = a)
    val q = if (l < 0.5f) l * (1f + s) else l + s - l * s
    val p = 2f * l - q
    val hk = h / 360f
    return Color(
        red = hueToRgb(p, q, hk + 1f / 3f),
        green = hueToRgb(p, q, hk),
        blue = hueToRgb(p, q, hk - 1f / 3f),
        alpha = a,
    )
}

private fun hueToRgb(p: Float, q: Float, tIn: Float): Float {
    var t = tIn
    if (t < 0f) t += 1f
    if (t > 1f) t -= 1f
    return when {
        t < 1f / 6f -> p + (q - p) * 6f * t
        t < 1f / 2f -> q
        t < 2f / 3f -> p + (q - p) * (2f / 3f - t) * 6f
        else -> p
    }
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
