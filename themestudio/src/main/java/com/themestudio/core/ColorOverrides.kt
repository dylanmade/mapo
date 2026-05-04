package com.themestudio.core

import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color

/**
 * Per-role color overrides for one ColorScheme variant (light or dark). Any
 * field set to `null` falls through to the base ColorScheme's value when
 * applied via [applyOverrides].
 *
 * Field set mirrors Material 3 [ColorScheme]. New roles added to the upstream
 * scheme need to be appended here, in [ColorRoles.all], and in [applyOverrides].
 */
data class ColorOverrides(
    val primary: Color? = null,
    val onPrimary: Color? = null,
    val primaryContainer: Color? = null,
    val onPrimaryContainer: Color? = null,
    val inversePrimary: Color? = null,
    val secondary: Color? = null,
    val onSecondary: Color? = null,
    val secondaryContainer: Color? = null,
    val onSecondaryContainer: Color? = null,
    val tertiary: Color? = null,
    val onTertiary: Color? = null,
    val tertiaryContainer: Color? = null,
    val onTertiaryContainer: Color? = null,
    val background: Color? = null,
    val onBackground: Color? = null,
    val surface: Color? = null,
    val onSurface: Color? = null,
    val surfaceVariant: Color? = null,
    val onSurfaceVariant: Color? = null,
    val surfaceTint: Color? = null,
    val inverseSurface: Color? = null,
    val inverseOnSurface: Color? = null,
    val error: Color? = null,
    val onError: Color? = null,
    val errorContainer: Color? = null,
    val onErrorContainer: Color? = null,
    val outline: Color? = null,
    val outlineVariant: Color? = null,
    val scrim: Color? = null,
    val surfaceBright: Color? = null,
    val surfaceContainer: Color? = null,
    val surfaceContainerHigh: Color? = null,
    val surfaceContainerHighest: Color? = null,
    val surfaceContainerLow: Color? = null,
    val surfaceContainerLowest: Color? = null,
    val surfaceDim: Color? = null,
)

/** Returns a copy of [this] with any non-null override fields replacing the base value. */
fun ColorScheme.applyOverrides(o: ColorOverrides): ColorScheme = copy(
    primary = o.primary ?: primary,
    onPrimary = o.onPrimary ?: onPrimary,
    primaryContainer = o.primaryContainer ?: primaryContainer,
    onPrimaryContainer = o.onPrimaryContainer ?: onPrimaryContainer,
    inversePrimary = o.inversePrimary ?: inversePrimary,
    secondary = o.secondary ?: secondary,
    onSecondary = o.onSecondary ?: onSecondary,
    secondaryContainer = o.secondaryContainer ?: secondaryContainer,
    onSecondaryContainer = o.onSecondaryContainer ?: onSecondaryContainer,
    tertiary = o.tertiary ?: tertiary,
    onTertiary = o.onTertiary ?: onTertiary,
    tertiaryContainer = o.tertiaryContainer ?: tertiaryContainer,
    onTertiaryContainer = o.onTertiaryContainer ?: onTertiaryContainer,
    background = o.background ?: background,
    onBackground = o.onBackground ?: onBackground,
    surface = o.surface ?: surface,
    onSurface = o.onSurface ?: onSurface,
    surfaceVariant = o.surfaceVariant ?: surfaceVariant,
    onSurfaceVariant = o.onSurfaceVariant ?: onSurfaceVariant,
    surfaceTint = o.surfaceTint ?: surfaceTint,
    inverseSurface = o.inverseSurface ?: inverseSurface,
    inverseOnSurface = o.inverseOnSurface ?: inverseOnSurface,
    error = o.error ?: error,
    onError = o.onError ?: onError,
    errorContainer = o.errorContainer ?: errorContainer,
    onErrorContainer = o.onErrorContainer ?: onErrorContainer,
    outline = o.outline ?: outline,
    outlineVariant = o.outlineVariant ?: outlineVariant,
    scrim = o.scrim ?: scrim,
    surfaceBright = o.surfaceBright ?: surfaceBright,
    surfaceContainer = o.surfaceContainer ?: surfaceContainer,
    surfaceContainerHigh = o.surfaceContainerHigh ?: surfaceContainerHigh,
    surfaceContainerHighest = o.surfaceContainerHighest ?: surfaceContainerHighest,
    surfaceContainerLow = o.surfaceContainerLow ?: surfaceContainerLow,
    surfaceContainerLowest = o.surfaceContainerLowest ?: surfaceContainerLowest,
    surfaceDim = o.surfaceDim ?: surfaceDim,
)

/**
 * Reflective-ish role descriptor table. The editor UI iterates this list to
 * render rows; persistence iterates it to read/write each role. Adding a new
 * role means: add a field to [ColorOverrides], add a copy-clause to
 * [applyOverrides], and add an entry here.
 */
data class ColorRole(
    val name: String,
    val read: (ColorScheme) -> Color,
    val readOverride: (ColorOverrides) -> Color?,
    val withOverride: (ColorOverrides, Color?) -> ColorOverrides,
)

object ColorRoles {
    val all: List<ColorRole> = listOf(
        ColorRole("primary", { it.primary }, { it.primary }, { o, c -> o.copy(primary = c) }),
        ColorRole("onPrimary", { it.onPrimary }, { it.onPrimary }, { o, c -> o.copy(onPrimary = c) }),
        ColorRole("primaryContainer", { it.primaryContainer }, { it.primaryContainer }, { o, c -> o.copy(primaryContainer = c) }),
        ColorRole("onPrimaryContainer", { it.onPrimaryContainer }, { it.onPrimaryContainer }, { o, c -> o.copy(onPrimaryContainer = c) }),
        ColorRole("inversePrimary", { it.inversePrimary }, { it.inversePrimary }, { o, c -> o.copy(inversePrimary = c) }),
        ColorRole("secondary", { it.secondary }, { it.secondary }, { o, c -> o.copy(secondary = c) }),
        ColorRole("onSecondary", { it.onSecondary }, { it.onSecondary }, { o, c -> o.copy(onSecondary = c) }),
        ColorRole("secondaryContainer", { it.secondaryContainer }, { it.secondaryContainer }, { o, c -> o.copy(secondaryContainer = c) }),
        ColorRole("onSecondaryContainer", { it.onSecondaryContainer }, { it.onSecondaryContainer }, { o, c -> o.copy(onSecondaryContainer = c) }),
        ColorRole("tertiary", { it.tertiary }, { it.tertiary }, { o, c -> o.copy(tertiary = c) }),
        ColorRole("onTertiary", { it.onTertiary }, { it.onTertiary }, { o, c -> o.copy(onTertiary = c) }),
        ColorRole("tertiaryContainer", { it.tertiaryContainer }, { it.tertiaryContainer }, { o, c -> o.copy(tertiaryContainer = c) }),
        ColorRole("onTertiaryContainer", { it.onTertiaryContainer }, { it.onTertiaryContainer }, { o, c -> o.copy(onTertiaryContainer = c) }),
        ColorRole("background", { it.background }, { it.background }, { o, c -> o.copy(background = c) }),
        ColorRole("onBackground", { it.onBackground }, { it.onBackground }, { o, c -> o.copy(onBackground = c) }),
        ColorRole("surface", { it.surface }, { it.surface }, { o, c -> o.copy(surface = c) }),
        ColorRole("onSurface", { it.onSurface }, { it.onSurface }, { o, c -> o.copy(onSurface = c) }),
        ColorRole("surfaceVariant", { it.surfaceVariant }, { it.surfaceVariant }, { o, c -> o.copy(surfaceVariant = c) }),
        ColorRole("onSurfaceVariant", { it.onSurfaceVariant }, { it.onSurfaceVariant }, { o, c -> o.copy(onSurfaceVariant = c) }),
        ColorRole("surfaceTint", { it.surfaceTint }, { it.surfaceTint }, { o, c -> o.copy(surfaceTint = c) }),
        ColorRole("inverseSurface", { it.inverseSurface }, { it.inverseSurface }, { o, c -> o.copy(inverseSurface = c) }),
        ColorRole("inverseOnSurface", { it.inverseOnSurface }, { it.inverseOnSurface }, { o, c -> o.copy(inverseOnSurface = c) }),
        ColorRole("error", { it.error }, { it.error }, { o, c -> o.copy(error = c) }),
        ColorRole("onError", { it.onError }, { it.onError }, { o, c -> o.copy(onError = c) }),
        ColorRole("errorContainer", { it.errorContainer }, { it.errorContainer }, { o, c -> o.copy(errorContainer = c) }),
        ColorRole("onErrorContainer", { it.onErrorContainer }, { it.onErrorContainer }, { o, c -> o.copy(onErrorContainer = c) }),
        ColorRole("outline", { it.outline }, { it.outline }, { o, c -> o.copy(outline = c) }),
        ColorRole("outlineVariant", { it.outlineVariant }, { it.outlineVariant }, { o, c -> o.copy(outlineVariant = c) }),
        ColorRole("scrim", { it.scrim }, { it.scrim }, { o, c -> o.copy(scrim = c) }),
        ColorRole("surfaceBright", { it.surfaceBright }, { it.surfaceBright }, { o, c -> o.copy(surfaceBright = c) }),
        ColorRole("surfaceContainer", { it.surfaceContainer }, { it.surfaceContainer }, { o, c -> o.copy(surfaceContainer = c) }),
        ColorRole("surfaceContainerHigh", { it.surfaceContainerHigh }, { it.surfaceContainerHigh }, { o, c -> o.copy(surfaceContainerHigh = c) }),
        ColorRole("surfaceContainerHighest", { it.surfaceContainerHighest }, { it.surfaceContainerHighest }, { o, c -> o.copy(surfaceContainerHighest = c) }),
        ColorRole("surfaceContainerLow", { it.surfaceContainerLow }, { it.surfaceContainerLow }, { o, c -> o.copy(surfaceContainerLow = c) }),
        ColorRole("surfaceContainerLowest", { it.surfaceContainerLowest }, { it.surfaceContainerLowest }, { o, c -> o.copy(surfaceContainerLowest = c) }),
        ColorRole("surfaceDim", { it.surfaceDim }, { it.surfaceDim }, { o, c -> o.copy(surfaceDim = c) }),
    )
}
