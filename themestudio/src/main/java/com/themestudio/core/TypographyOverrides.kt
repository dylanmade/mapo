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
 * Override container for all 15 Material 3 typography roles plus two
 * umbrella overrides (Display and Body) that cascade across role groups.
 *
 * Family overrides are umbrella-level because that mirrors how Material 3
 * typescales are usually structured: one family for display/headline/title,
 * another for body/label. The umbrella [TextStyleOverride]s extend that
 * pattern to size/weight/tracking — set them once and they cascade across
 * every role in the group.
 *
 * Cascade order (in [applyOverrides], strongest first):
 *   1. umbrella override field (display* or body* depending on role)
 *   2. per-role override field
 *   3. base [Typography] value
 *
 * In other words an umbrella value, when set, overrides any per-role value
 * for the same field. Per-role overrides remain stored — they reassert if
 * the umbrella field is later cleared.
 */
data class TypographyOverrides(
    val displayFontFamilyName: String? = null,
    val bodyFontFamilyName: String? = null,
    val displayUmbrella: TextStyleOverride = TextStyleOverride(),
    val bodyUmbrella: TextStyleOverride = TextStyleOverride(),
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

/**
 * Returns a copy of [this] with non-null override fields applied.
 *
 * Cascade for each [TextStyle] field is umbrella → per-role → base: when an
 * umbrella value is set it wins over any per-role value for the same field.
 * Family override is umbrella-only (no per-role family today).
 *
 * [resolveFamily] turns a stored name into a [FontFamily]. The default
 * resolves through GMS Fonts, which keeps non-Composable callers and unit
 * tests working unchanged. Composable call sites (notably MappoTheme) should
 * pass `rememberThemeFontResolver()` so asset-bundled families in
 * [LocalFontRegistry] resolve too.
 */
fun Typography.applyOverrides(
    o: TypographyOverrides,
    resolveFamily: ThemeFontResolver = ::googleFontFamily,
): Typography {
    val displayFamily = o.displayFontFamilyName?.let(resolveFamily)
    val bodyFamily = o.bodyFontFamilyName?.let(resolveFamily)
    val du = o.displayUmbrella
    val bu = o.bodyUmbrella
    return copy(
        displayLarge = displayLarge.applyOverride(o.displayLarge, du, displayFamily),
        displayMedium = displayMedium.applyOverride(o.displayMedium, du, displayFamily),
        displaySmall = displaySmall.applyOverride(o.displaySmall, du, displayFamily),
        headlineLarge = headlineLarge.applyOverride(o.headlineLarge, du, displayFamily),
        headlineMedium = headlineMedium.applyOverride(o.headlineMedium, du, displayFamily),
        headlineSmall = headlineSmall.applyOverride(o.headlineSmall, du, displayFamily),
        titleLarge = titleLarge.applyOverride(o.titleLarge, du, displayFamily),
        titleMedium = titleMedium.applyOverride(o.titleMedium, du, displayFamily),
        titleSmall = titleSmall.applyOverride(o.titleSmall, du, displayFamily),
        bodyLarge = bodyLarge.applyOverride(o.bodyLarge, bu, bodyFamily),
        bodyMedium = bodyMedium.applyOverride(o.bodyMedium, bu, bodyFamily),
        bodySmall = bodySmall.applyOverride(o.bodySmall, bu, bodyFamily),
        labelLarge = labelLarge.applyOverride(o.labelLarge, bu, bodyFamily),
        labelMedium = labelMedium.applyOverride(o.labelMedium, bu, bodyFamily),
        labelSmall = labelSmall.applyOverride(o.labelSmall, bu, bodyFamily),
    )
}

private fun TextStyle.applyOverride(
    role: TextStyleOverride,
    umbrella: TextStyleOverride = TextStyleOverride(),
    familyOverride: androidx.compose.ui.text.font.FontFamily? = null,
): TextStyle = copy(
    fontSize = umbrella.fontSize ?: role.fontSize ?: fontSize,
    fontWeight = umbrella.fontWeight ?: role.fontWeight ?: fontWeight,
    letterSpacing = umbrella.letterSpacing ?: role.letterSpacing ?: letterSpacing,
    fontFamily = familyOverride ?: fontFamily,
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

/**
 * Umbrella "set-all" roles. These don't correspond to a real Material 3
 * typography role — they hook into [TypographyOverrides.displayUmbrella] /
 * [TypographyOverrides.bodyUmbrella] and cascade across an entire role
 * group via [applyOverrides]. The [read] callback returns a representative
 * base style (titleLarge / bodyLarge) so the editor's live-preview line in
 * the existing [TypographyRole]-shaped picker has something sensible to
 * render against.
 */
object UmbrellaRoles {
    val display: TypographyRole = TypographyRole(
        name = "displayUmbrella",
        read = { it.titleLarge },
        readOverride = { it.displayUmbrella },
        withOverride = { o, v -> o.copy(displayUmbrella = v) },
    )
    val body: TypographyRole = TypographyRole(
        name = "bodyUmbrella",
        read = { it.bodyLarge },
        readOverride = { it.bodyUmbrella },
        withOverride = { o, v -> o.copy(bodyUmbrella = v) },
    )
    val all: List<TypographyRole> = listOf(display, body)
}

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
