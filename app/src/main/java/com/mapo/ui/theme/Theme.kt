// Generated using MaterialKolor Builder version 1.3.0 (103)
// https://materialkolor.com/?color_seed=FFFFF4EF&color_primary=FFFFF4EF&color_secondary=FF7D766F&color_tertiary=FFFFF4EF&color_neutral=FFFFFBFF&dark_mode=true&style=Neutral&contrast=1.0&color_spec=SPEC_2025&package_name=com.example.app&expressive=true

package com.mapo.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable

private val highContrastLightColorScheme = lightColorScheme(
    primary = PrimaryLightHighContrast,
    onPrimary = OnPrimaryLightHighContrast,
    primaryContainer = PrimaryContainerLightHighContrast,
    onPrimaryContainer = OnPrimaryContainerLightHighContrast,
    inversePrimary = InversePrimaryLightHighContrast,
    secondary = SecondaryLightHighContrast,
    onSecondary = OnSecondaryLightHighContrast,
    secondaryContainer = SecondaryContainerLightHighContrast,
    onSecondaryContainer = OnSecondaryContainerLightHighContrast,
    tertiary = TertiaryLightHighContrast,
    onTertiary = OnTertiaryLightHighContrast,
    tertiaryContainer = TertiaryContainerLightHighContrast,
    onTertiaryContainer = OnTertiaryContainerLightHighContrast,
    background = BackgroundLightHighContrast,
    onBackground = OnBackgroundLightHighContrast,
    surface = SurfaceLightHighContrast,
    onSurface = OnSurfaceLightHighContrast,
    surfaceVariant = SurfaceVariantLightHighContrast,
    onSurfaceVariant = OnSurfaceVariantLightHighContrast,
    surfaceTint = SurfaceTintLightHighContrast,
    inverseSurface = InverseSurfaceLightHighContrast,
    inverseOnSurface = InverseOnSurfaceLightHighContrast,
    error = ErrorLightHighContrast,
    onError = OnErrorLightHighContrast,
    errorContainer = ErrorContainerLightHighContrast,
    onErrorContainer = OnErrorContainerLightHighContrast,
    outline = OutlineLightHighContrast,
    outlineVariant = OutlineVariantLightHighContrast,
    scrim = ScrimLightHighContrast,
    surfaceBright = SurfaceBrightLightHighContrast,
    surfaceContainer = SurfaceContainerLightHighContrast,
    surfaceContainerHigh = SurfaceContainerHighLightHighContrast,
    surfaceContainerHighest = SurfaceContainerHighestLightHighContrast,
    surfaceContainerLow = SurfaceContainerLowLightHighContrast,
    surfaceContainerLowest = SurfaceContainerLowestLightHighContrast,
    surfaceDim = SurfaceDimLightHighContrast,
    primaryFixed = PrimaryFixedHighContrast,
    primaryFixedDim = PrimaryFixedDimHighContrast,
    onPrimaryFixed = OnPrimaryFixedHighContrast,
    onPrimaryFixedVariant = OnPrimaryFixedVariantHighContrast,
    secondaryFixed = SecondaryFixedHighContrast,
    secondaryFixedDim = SecondaryFixedDimHighContrast,
    onSecondaryFixed = OnSecondaryFixedHighContrast,
    onSecondaryFixedVariant = OnSecondaryFixedVariantHighContrast,
    tertiaryFixed = TertiaryFixedHighContrast,
    tertiaryFixedDim = TertiaryFixedDimHighContrast,
    onTertiaryFixed = OnTertiaryFixedHighContrast,
    onTertiaryFixedVariant = OnTertiaryFixedVariantHighContrast,
)

private val highContrastDarkColorScheme = darkColorScheme(
    primary = PrimaryDarkHighContrast,
    onPrimary = OnPrimaryDarkHighContrast,
    primaryContainer = PrimaryContainerDarkHighContrast,
    onPrimaryContainer = OnPrimaryContainerDarkHighContrast,
    inversePrimary = InversePrimaryDarkHighContrast,
    secondary = SecondaryDarkHighContrast,
    onSecondary = OnSecondaryDarkHighContrast,
    secondaryContainer = SecondaryContainerDarkHighContrast,
    onSecondaryContainer = OnSecondaryContainerDarkHighContrast,
    tertiary = TertiaryDarkHighContrast,
    onTertiary = OnTertiaryDarkHighContrast,
    tertiaryContainer = TertiaryContainerDarkHighContrast,
    onTertiaryContainer = OnTertiaryContainerDarkHighContrast,
    background = BackgroundDarkHighContrast,
    onBackground = OnBackgroundDarkHighContrast,
    surface = SurfaceDarkHighContrast,
    onSurface = OnSurfaceDarkHighContrast,
    surfaceVariant = SurfaceVariantDarkHighContrast,
    onSurfaceVariant = OnSurfaceVariantDarkHighContrast,
    surfaceTint = SurfaceTintDarkHighContrast,
    inverseSurface = InverseSurfaceDarkHighContrast,
    inverseOnSurface = InverseOnSurfaceDarkHighContrast,
    error = ErrorDarkHighContrast,
    onError = OnErrorDarkHighContrast,
    errorContainer = ErrorContainerDarkHighContrast,
    onErrorContainer = OnErrorContainerDarkHighContrast,
    outline = OutlineDarkHighContrast,
    outlineVariant = OutlineVariantDarkHighContrast,
    scrim = ScrimDarkHighContrast,
    surfaceBright = SurfaceBrightDarkHighContrast,
    surfaceContainer = SurfaceContainerDarkHighContrast,
    surfaceContainerHigh = SurfaceContainerHighDarkHighContrast,
    surfaceContainerHighest = SurfaceContainerHighestDarkHighContrast,
    surfaceContainerLow = SurfaceContainerLowDarkHighContrast,
    surfaceContainerLowest = SurfaceContainerLowestDarkHighContrast,
    surfaceDim = SurfaceDimDarkHighContrast,
    primaryFixed = PrimaryFixedHighContrast,
    primaryFixedDim = PrimaryFixedDimHighContrast,
    onPrimaryFixed = OnPrimaryFixedHighContrast,
    onPrimaryFixedVariant = OnPrimaryFixedVariantHighContrast,
    secondaryFixed = SecondaryFixedHighContrast,
    secondaryFixedDim = SecondaryFixedDimHighContrast,
    onSecondaryFixed = OnSecondaryFixedHighContrast,
    onSecondaryFixedVariant = OnSecondaryFixedVariantHighContrast,
    tertiaryFixed = TertiaryFixedHighContrast,
    tertiaryFixedDim = TertiaryFixedDimHighContrast,
    onTertiaryFixed = OnTertiaryFixedHighContrast,
    onTertiaryFixedVariant = OnTertiaryFixedVariantHighContrast,
)

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun MapoTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val colorScheme = if (darkTheme) highContrastDarkColorScheme else highContrastLightColorScheme

    MaterialExpressiveTheme(
        colorScheme = colorScheme,
        motionScheme = MotionScheme.expressive(),
        typography = Typography,
        content = content,
    )
}
