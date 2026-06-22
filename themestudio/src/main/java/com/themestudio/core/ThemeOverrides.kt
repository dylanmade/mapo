package com.themestudio.core

import androidx.compose.ui.graphics.Color

/**
 * Top-level container for runtime theme overrides — covers colors,
 * typography, and shapes. The three are independent: editing one doesn't
 * affect the others, and `EMPTY` for any sub-tree falls through to the
 * consumer's compiled-in theme.
 */
data class ThemeOverrides(
    val colors: ColorThemeOverrides = ColorThemeOverrides(),
    val typography: TypographyOverrides = TypographyOverrides(),
    val shapes: ShapeOverrides = ShapeOverrides(),
    val colorGeneration: ColorGenerationOverrides = ColorGenerationOverrides(),
) {
    companion object {
        val EMPTY: ThemeOverrides = ThemeOverrides()
    }
}

/**
 * Seed-based color-generation options for consumers whose base scheme is generated from a seed
 * (e.g. MaterialKolor). All null → the consumer's compiled-in defaults. The consumer maps [style]
 * (a palette-style name string, kept generic so this module stays library-agnostic) and
 * [contrast] (−1..1) onto its generator. Per-role [ColorThemeOverrides] still layer on top.
 */
data class ColorGenerationOverrides(
    val seed: Color? = null,
    val style: String? = null,
    val contrast: Float? = null,
) {
    companion object {
        /** Palette-style names offered in the editor; the consumer maps these to its generator. */
        val STYLE_NAMES: List<String> = listOf(
            "TonalSpot", "Neutral", "Vibrant", "Expressive", "Rainbow", "FruitSalad",
            "Monochrome", "Fidelity", "Content",
        )
    }
}

/**
 * Color-specific overrides. Holds independent light/dark variants because
 * Material 3 themes typically define both, and the editor lets the dev
 * tweak each side separately.
 */
data class ColorThemeOverrides(
    val light: ColorOverrides = ColorOverrides(),
    val dark: ColorOverrides = ColorOverrides(),
)
