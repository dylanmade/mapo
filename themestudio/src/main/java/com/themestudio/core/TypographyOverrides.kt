package com.themestudio.core

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.isUnspecified

/**
 * Per-property override for a single Material 3 text style. Any field set to
 * `null` falls through to the base style's value.
 */
data class TextStyleOverride(
    val fontSize: TextUnit? = null,
    val fontWeight: FontWeight? = null,
    val letterSpacing: TextUnit? = null,
)

/**
 * Override container for all 15 Material 3 typography roles. Each role is a
 * [TextStyleOverride]; absent fields fall through to the base [Typography].
 */
data class TypographyOverrides(
    val displayLarge: TextStyleOverride = TextStyleOverride(),
    val displayMedium: TextStyleOverride = TextStyleOverride(),
    val displaySmall: TextStyleOverride = TextStyleOverride(),
    val headlineLarge: TextStyleOverride = TextStyleOverride(),
    val headlineMedium: TextStyleOverride = TextStyleOverride(),
    val headlineSmall: TextStyleOverride = TextStyleOverride(),
    val titleLarge: TextStyleOverride = TextStyleOverride(),
    val titleMedium: TextStyleOverride = TextStyleOverride(),
    val titleSmall: TextStyleOverride = TextStyleOverride(),
    val bodyLarge: TextStyleOverride = TextStyleOverride(),
    val bodyMedium: TextStyleOverride = TextStyleOverride(),
    val bodySmall: TextStyleOverride = TextStyleOverride(),
    val labelLarge: TextStyleOverride = TextStyleOverride(),
    val labelMedium: TextStyleOverride = TextStyleOverride(),
    val labelSmall: TextStyleOverride = TextStyleOverride(),
)

/** Returns a copy of [this] with non-null override fields applied. */
fun Typography.applyOverrides(o: TypographyOverrides): Typography = copy(
    displayLarge = displayLarge.applyOverride(o.displayLarge),
    displayMedium = displayMedium.applyOverride(o.displayMedium),
    displaySmall = displaySmall.applyOverride(o.displaySmall),
    headlineLarge = headlineLarge.applyOverride(o.headlineLarge),
    headlineMedium = headlineMedium.applyOverride(o.headlineMedium),
    headlineSmall = headlineSmall.applyOverride(o.headlineSmall),
    titleLarge = titleLarge.applyOverride(o.titleLarge),
    titleMedium = titleMedium.applyOverride(o.titleMedium),
    titleSmall = titleSmall.applyOverride(o.titleSmall),
    bodyLarge = bodyLarge.applyOverride(o.bodyLarge),
    bodyMedium = bodyMedium.applyOverride(o.bodyMedium),
    bodySmall = bodySmall.applyOverride(o.bodySmall),
    labelLarge = labelLarge.applyOverride(o.labelLarge),
    labelMedium = labelMedium.applyOverride(o.labelMedium),
    labelSmall = labelSmall.applyOverride(o.labelSmall),
)

private fun TextStyle.applyOverride(o: TextStyleOverride): TextStyle = copy(
    fontSize = o.fontSize ?: fontSize,
    fontWeight = o.fontWeight ?: fontWeight,
    letterSpacing = o.letterSpacing ?: letterSpacing,
)

/**
 * Reflective table for the editor / persistence to iterate over typography
 * roles by name. Adding a new role means: add a field to [TypographyOverrides],
 * add a clause to [applyOverrides], and add an entry here.
 */
data class TypographyRole(
    val name: String,
    val read: (Typography) -> TextStyle,
    val readOverride: (TypographyOverrides) -> TextStyleOverride,
    val withOverride: (TypographyOverrides, TextStyleOverride) -> TypographyOverrides,
)

object TypographyRoles {
    val all: List<TypographyRole> = listOf(
        TypographyRole("displayLarge", { it.displayLarge }, { it.displayLarge }, { o, v -> o.copy(displayLarge = v) }),
        TypographyRole("displayMedium", { it.displayMedium }, { it.displayMedium }, { o, v -> o.copy(displayMedium = v) }),
        TypographyRole("displaySmall", { it.displaySmall }, { it.displaySmall }, { o, v -> o.copy(displaySmall = v) }),
        TypographyRole("headlineLarge", { it.headlineLarge }, { it.headlineLarge }, { o, v -> o.copy(headlineLarge = v) }),
        TypographyRole("headlineMedium", { it.headlineMedium }, { it.headlineMedium }, { o, v -> o.copy(headlineMedium = v) }),
        TypographyRole("headlineSmall", { it.headlineSmall }, { it.headlineSmall }, { o, v -> o.copy(headlineSmall = v) }),
        TypographyRole("titleLarge", { it.titleLarge }, { it.titleLarge }, { o, v -> o.copy(titleLarge = v) }),
        TypographyRole("titleMedium", { it.titleMedium }, { it.titleMedium }, { o, v -> o.copy(titleMedium = v) }),
        TypographyRole("titleSmall", { it.titleSmall }, { it.titleSmall }, { o, v -> o.copy(titleSmall = v) }),
        TypographyRole("bodyLarge", { it.bodyLarge }, { it.bodyLarge }, { o, v -> o.copy(bodyLarge = v) }),
        TypographyRole("bodyMedium", { it.bodyMedium }, { it.bodyMedium }, { o, v -> o.copy(bodyMedium = v) }),
        TypographyRole("bodySmall", { it.bodySmall }, { it.bodySmall }, { o, v -> o.copy(bodySmall = v) }),
        TypographyRole("labelLarge", { it.labelLarge }, { it.labelLarge }, { o, v -> o.copy(labelLarge = v) }),
        TypographyRole("labelMedium", { it.labelMedium }, { it.labelMedium }, { o, v -> o.copy(labelMedium = v) }),
        TypographyRole("labelSmall", { it.labelSmall }, { it.labelSmall }, { o, v -> o.copy(labelSmall = v) }),
    )
}

/** Curated set of font weights the editor surfaces. */
val EDITABLE_FONT_WEIGHTS: List<Pair<String, FontWeight>> = listOf(
    "Thin (100)" to FontWeight.Thin,
    "ExtraLight (200)" to FontWeight.ExtraLight,
    "Light (300)" to FontWeight.Light,
    "Normal (400)" to FontWeight.Normal,
    "Medium (500)" to FontWeight.Medium,
    "SemiBold (600)" to FontWeight.SemiBold,
    "Bold (700)" to FontWeight.Bold,
    "ExtraBold (800)" to FontWeight.ExtraBold,
    "Black (900)" to FontWeight.Black,
)

/** Convenience: read sp as Float, defaulting to 14 when the unit is unspecified. */
internal fun TextUnit.toSpFloat(): Float = if (isUnspecified) 14f else value
