package com.mapo.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import com.themestudio.core.LocalThemeStudioController
import com.themestudio.core.LocalThemeStudioVariantOverride
import com.themestudio.core.applyOverrides
import com.themestudio.core.rememberThemeFontResolver

private val lightScheme = lightColorScheme(
    primary = primaryLight,
    onPrimary = onPrimaryLight,
    primaryContainer = primaryContainerLight,
    onPrimaryContainer = onPrimaryContainerLight,
    secondary = secondaryLight,
    onSecondary = onSecondaryLight,
    secondaryContainer = secondaryContainerLight,
    onSecondaryContainer = onSecondaryContainerLight,
    tertiary = tertiaryLight,
    onTertiary = onTertiaryLight,
    tertiaryContainer = tertiaryContainerLight,
    onTertiaryContainer = onTertiaryContainerLight,
    error = errorLight,
    onError = onErrorLight,
    errorContainer = errorContainerLight,
    onErrorContainer = onErrorContainerLight,
    background = backgroundLight,
    onBackground = onBackgroundLight,
    surface = surfaceLight,
    onSurface = onSurfaceLight,
    surfaceVariant = surfaceVariantLight,
    onSurfaceVariant = onSurfaceVariantLight,
    outline = outlineLight,
    outlineVariant = outlineVariantLight,
    scrim = scrimLight,
    inverseSurface = inverseSurfaceLight,
    inverseOnSurface = inverseOnSurfaceLight,
    inversePrimary = inversePrimaryLight,
    surfaceDim = surfaceDimLight,
    surfaceBright = surfaceBrightLight,
    surfaceContainerLowest = surfaceContainerLowestLight,
    surfaceContainerLow = surfaceContainerLowLight,
    surfaceContainer = surfaceContainerLight,
    surfaceContainerHigh = surfaceContainerHighLight,
    surfaceContainerHighest = surfaceContainerHighestLight,
)

private val darkScheme = darkColorScheme(
    primary = primaryDark,
    onPrimary = onPrimaryDark,
    primaryContainer = primaryContainerDark,
    onPrimaryContainer = onPrimaryContainerDark,
    secondary = secondaryDark,
    onSecondary = onSecondaryDark,
    secondaryContainer = secondaryContainerDark,
    onSecondaryContainer = onSecondaryContainerDark,
    tertiary = tertiaryDark,
    onTertiary = onTertiaryDark,
    tertiaryContainer = tertiaryContainerDark,
    onTertiaryContainer = onTertiaryContainerDark,
    error = errorDark,
    onError = onErrorDark,
    errorContainer = errorContainerDark,
    onErrorContainer = onErrorContainerDark,
    background = backgroundDark,
    onBackground = onBackgroundDark,
    surface = surfaceDark,
    onSurface = onSurfaceDark,
    surfaceVariant = surfaceVariantDark,
    onSurfaceVariant = onSurfaceVariantDark,
    outline = outlineDark,
    outlineVariant = outlineVariantDark,
    scrim = scrimDark,
    inverseSurface = inverseSurfaceDark,
    inverseOnSurface = inverseOnSurfaceDark,
    inversePrimary = inversePrimaryDark,
    surfaceDim = surfaceDimDark,
    surfaceBright = surfaceBrightDark,
    surfaceContainerLowest = surfaceContainerLowestDark,
    surfaceContainerLow = surfaceContainerLowDark,
    surfaceContainer = surfaceContainerDark,
    surfaceContainerHigh = surfaceContainerHighDark,
    surfaceContainerHighest = surfaceContainerHighestDark,
)

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun MapoTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    // Default off: Mapo's Material Theme Builder palette is a deliberate brand choice;
    // dynamic color (Android 12+) replaces it with system-derived colors from the
    // user's wallpaper. Caller can opt in by passing dynamicColor = true.
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit,
) {
    // Theme Studio integration: when the editor forces a variant, honor it;
    // when it has overrides, merge them onto the chosen base scheme/typography/shapes.
    val variantOverride = LocalThemeStudioVariantOverride.current
    val effectiveDark = variantOverride ?: darkTheme
    val baseScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (effectiveDark) dynamicDarkColorScheme(context)
            else dynamicLightColorScheme(context)
        }
        effectiveDark -> darkScheme
        else -> lightScheme
    }
    val controller = LocalThemeStudioController.current
    val overrides = controller.overrides
    val variantColors =
        if (effectiveDark) overrides.colors.dark else overrides.colors.light
    val colorScheme = baseScheme.applyOverrides(variantColors)
    val fontResolver = rememberThemeFontResolver()
    val typography = AppTypography.applyOverrides(overrides.typography, fontResolver)
    val shapes = Shapes().applyOverrides(overrides.shapes)

    MaterialExpressiveTheme(
        colorScheme = colorScheme,
        // Standard (critically-damped) springs instead of expressive (bouncy):
        // expressive's intentional overshoot caused the drawer to slide past its
        // open anchor and snap back, looking like a glitch. Switch back any time.
        motionScheme = MotionScheme.standard(),
        typography = typography,
        shapes = shapes,
        content = content,
    )
}
