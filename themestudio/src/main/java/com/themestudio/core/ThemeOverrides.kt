package com.themestudio.core

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
) {
    companion object {
        val EMPTY: ThemeOverrides = ThemeOverrides()
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
