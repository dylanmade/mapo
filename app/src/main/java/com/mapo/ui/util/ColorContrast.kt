package com.mapo.ui.util

import androidx.compose.ui.graphics.Color
import com.mapo.data.model.GridButton
import com.mapo.data.model.GridLayout
import kotlin.math.max
import kotlin.math.min

/**
 * HSL-based color helpers used by the virtual-keyboard button renderer and
 * the appearance-tab swatch UI to derive auto-calculated colors that contrast
 * with their parent. The resolver here is the single source of truth so the
 * swatch in the configure screen always shows the same color the renderer draws.
 */

/** Perceived-darkness test using HSL lightness. Below 0.5 counts as "dark". */
fun Color.isDark(): Boolean = toHsl().third < 0.5f

/**
 * Shift lightness *away from* the parent: lighter for dark colors, darker for
 * light colors. Used for auto fill/outline so they contrast with their source.
 */
fun Color.contrastShift(amount: Float = 0.18f): Color {
    val (h, s, l) = toHsl()
    val newL = if (isDark()) (l + amount).coerceAtMost(1f) else (l - amount).coerceAtLeast(0f)
    return fromHsl(h, s, newL, alpha)
}

/** Always-darker shift, used for bevels (which imitate a physical button's shaded bottom). */
fun Color.darkened(amount: Float = 0.18f): Color {
    val (h, s, l) = toHsl()
    return fromHsl(h, s, (l - amount).coerceAtLeast(0f), alpha)
}

/**
 * Convert any color into a conventional UI drop-shadow color: keep a hint of
 * the parent's hue, but force it dark, desaturated, and partially transparent.
 * The visual result is similar regardless of parent.
 */
fun Color.toShadowColor(): Color {
    val (h, s, _) = toHsl()
    return fromHsl(h, (s * 0.25f).coerceAtMost(0.15f), 0.05f, 0.55f)
}

/**
 * Final per-slot colors for one button, with auto-resolution applied. Both the
 * renderer and the swatch UI read from this so they never disagree on what the
 * button actually looks like.
 */
data class ResolvedButtonColors(
    val fillEnabled: Boolean,
    val fill: Color,
    val outlineEnabled: Boolean,
    val outline: Color,
    val bevelEnabled: Boolean,
    val bevel: Color,
    val shadowEnabled: Boolean,
    val shadow: Color,
)

/**
 * Resolve every color slot on [button], using [keyboardTheme] as the top of the
 * hierarchy and applying the documented fallbacks for each slot's "Auto" state:
 *
 *  - Auto fill   ← theme (contrast-shifted)
 *  - Auto outline ← fill (or theme if fill disabled), contrast-shifted
 *  - Auto bevel  ← fill (or theme if fill disabled), darkened
 *  - Auto shadow ← fill (or theme if fill disabled), shadowified
 */
fun resolveAutoColors(button: GridButton, keyboardTheme: Color): ResolvedButtonColors {
    val fill = if (button.fillIsAuto || button.fillColorArgb == null) {
        keyboardTheme.contrastShift()
    } else {
        Color(button.fillColorArgb)
    }

    // Higher-in-hierarchy color that bevel/outline/shadow derive from when in auto mode.
    val parent = if (button.fillEnabled) fill else keyboardTheme

    val outline = if (button.outlineIsAuto || button.outlineColorArgb == null) {
        parent.contrastShift()
    } else {
        Color(button.outlineColorArgb)
    }

    val bevel = if (button.bevelIsAuto || button.bevelColorArgb == null) {
        // Subtle 8% darken: enough to read as a 3D bottom edge without converging on
        // the keyboard background. (Higher values collide with the bg in dark themes
        // because `parent` is already +18% lightness from theme via `contrastShift`.)
        parent.darkened(amount = 0.08f)
    } else {
        Color(button.bevelColorArgb)
    }

    val shadow = if (button.shadowIsAuto || button.shadowColorArgb == null) {
        parent.toShadowColor()
    } else {
        Color(button.shadowColorArgb)
    }

    return ResolvedButtonColors(
        fillEnabled = button.fillEnabled, fill = fill,
        outlineEnabled = button.outlineEnabled, outline = outline,
        bevelEnabled = button.bevelEnabled, bevel = bevel,
        shadowEnabled = button.shadowEnabled, shadow = shadow,
    )
}

// ── Layout (keyboard surface) ─────────────────────────────────────────────────

/**
 * Final per-slot colors for one keyboard layout's outer surface, with auto-resolution
 * applied. Mirrors [ResolvedButtonColors]; the keyboard renderer and the swatch UI
 * in ConfigureKeyboardScreen both read from this so they agree on what the surface
 * actually looks like.
 */
data class ResolvedLayoutColors(
    val fillEnabled: Boolean,
    val fill: Color,
    val outlineEnabled: Boolean,
    val outline: Color,
    val bevelEnabled: Boolean,
    val bevel: Color,
    val shadowEnabled: Boolean,
    val shadow: Color,
)

/**
 * Resolve every color slot on the keyboard [layout]'s outer surface, using
 * [themeFallback] (typically `MaterialTheme.colorScheme.surface`) as the top of the
 * hierarchy. The auto-derivation rules mirror [resolveAutoColors] for buttons,
 * except the layout's auto fill is the theme color *identity* (no contrast-shift):
 * a brand-new keyboard with all defaults paints exactly the M3 surface, matching
 * the pre-refactor appearance.
 *
 *  - Auto fill   ← themeFallback (identity)
 *  - Auto outline ← fill (or themeFallback if fill disabled), contrast-shifted
 *  - Auto bevel  ← fill (or themeFallback if fill disabled), darkened
 *  - Auto shadow ← fill (or themeFallback if fill disabled), shadowified
 */
fun resolveAutoLayoutColors(layout: GridLayout, themeFallback: Color): ResolvedLayoutColors {
    val fill = if (layout.fillIsAuto || layout.fillColorArgb == null) {
        themeFallback
    } else {
        Color(layout.fillColorArgb)
    }

    val parent = if (layout.fillEnabled) fill else themeFallback

    val outline = if (layout.outlineIsAuto || layout.outlineColorArgb == null) {
        parent.contrastShift()
    } else {
        Color(layout.outlineColorArgb)
    }

    val bevel = if (layout.bevelIsAuto || layout.bevelColorArgb == null) {
        parent.darkened(amount = 0.08f)
    } else {
        Color(layout.bevelColorArgb)
    }

    val shadow = if (layout.shadowIsAuto || layout.shadowColorArgb == null) {
        parent.toShadowColor()
    } else {
        Color(layout.shadowColorArgb)
    }

    return ResolvedLayoutColors(
        fillEnabled = layout.fillEnabled, fill = fill,
        outlineEnabled = layout.outlineEnabled, outline = outline,
        bevelEnabled = layout.bevelEnabled, bevel = bevel,
        shadowEnabled = layout.shadowEnabled, shadow = shadow,
    )
}

/**
 * The color that buttons should treat as their parent in the auto-color hierarchy.
 * When the keyboard's fill slot is enabled, this is the resolved fill (auto or manual).
 * When fill is disabled, the keyboard surface is transparent and the bottom-screen
 * surface shows through — so buttons should derive from [themeFallback] instead.
 */
fun keyboardButtonParentColor(layout: GridLayout, themeFallback: Color): Color {
    val resolved = resolveAutoLayoutColors(layout, themeFallback)
    return if (resolved.fillEnabled) resolved.fill else themeFallback
}

// ── HSL conversion ────────────────────────────────────────────────────────────
// Standard RGB↔HSL. H in 0..360, S/L in 0..1.

internal fun Color.toHsl(): Triple<Float, Float, Float> {
    val r = red; val g = green; val b = blue
    val mx = max(r, max(g, b))
    val mn = min(r, min(g, b))
    val l = (mx + mn) / 2f
    if (mx == mn) return Triple(0f, 0f, l)
    val d = mx - mn
    val s = if (l > 0.5f) d / (2f - mx - mn) else d / (mx + mn)
    val h = when (mx) {
        r -> ((g - b) / d + if (g < b) 6f else 0f) * 60f
        g -> ((b - r) / d + 2f) * 60f
        else -> ((r - g) / d + 4f) * 60f
    }
    return Triple(h, s, l)
}

internal fun fromHsl(h: Float, s: Float, l: Float, alpha: Float = 1f): Color {
    if (s == 0f) return Color(red = l, green = l, blue = l, alpha = alpha)
    val q = if (l < 0.5f) l * (1f + s) else l + s - l * s
    val p = 2f * l - q
    val hk = ((h % 360f + 360f) % 360f) / 360f
    return Color(
        red = hueToRgb(p, q, hk + 1f / 3f),
        green = hueToRgb(p, q, hk),
        blue = hueToRgb(p, q, hk - 1f / 3f),
        alpha = alpha,
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
