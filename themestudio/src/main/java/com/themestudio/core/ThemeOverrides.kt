package com.themestudio.core

/**
 * Top-level container for theme overrides. Holds independent light/dark
 * variants because Material 3 themes typically define both, and the editor
 * lets the dev tweak each side separately.
 */
data class ThemeOverrides(
    val light: ColorOverrides = ColorOverrides(),
    val dark: ColorOverrides = ColorOverrides(),
) {
    companion object {
        val EMPTY: ThemeOverrides = ThemeOverrides()
    }
}
