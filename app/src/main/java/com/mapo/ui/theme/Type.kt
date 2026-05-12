package com.mapo.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import com.themestudio.core.rememberThemeFontResolver

/**
 * Baked-in defaults for the Display and Body font families. Both currently
 * resolve to PP Mori (asset-bundled, personal-use-only). Exposed as constants
 * so Theme Studio can show the actual default font in its pickers instead of
 * the generic "(theme default)" placeholder.
 */
const val DEFAULT_DISPLAY_FONT_NAME: String = "PP Mori"
const val DEFAULT_BODY_FONT_NAME: String = "PP Mori"

/**
 * Composable typography builder. PP Mori is bundled as assets, so resolving
 * its [androidx.compose.ui.text.font.FontFamily] needs an [android.content.res.AssetManager]
 * — which means we have to build the family inside Compose rather than as a
 * top-level val. The result is remembered against the resolver so it doesn't
 * rebuild on recompositions.
 */
@Composable
fun rememberAppTypography(): Typography {
    val resolve = rememberThemeFontResolver()
    return remember(resolve) {
        val displayFamily = resolve(DEFAULT_DISPLAY_FONT_NAME)
        val bodyFamily = resolve(DEFAULT_BODY_FONT_NAME)
        val baseline = Typography()
        Typography(
            displayLarge = baseline.displayLarge.copy(fontFamily = displayFamily),
            displayMedium = baseline.displayMedium.copy(fontFamily = displayFamily),
            displaySmall = baseline.displaySmall.copy(fontFamily = displayFamily),
            headlineLarge = baseline.headlineLarge.copy(fontFamily = displayFamily),
            headlineMedium = baseline.headlineMedium.copy(fontFamily = displayFamily),
            headlineSmall = baseline.headlineSmall.copy(fontFamily = displayFamily),
            titleLarge = baseline.titleLarge.copy(fontFamily = displayFamily),
            titleMedium = baseline.titleMedium.copy(fontFamily = displayFamily),
            titleSmall = baseline.titleSmall.copy(fontFamily = displayFamily),
            bodyLarge = baseline.bodyLarge.copy(fontFamily = bodyFamily),
            bodyMedium = baseline.bodyMedium.copy(fontFamily = bodyFamily),
            bodySmall = baseline.bodySmall.copy(fontFamily = bodyFamily),
            labelLarge = baseline.labelLarge.copy(fontFamily = bodyFamily),
            labelMedium = baseline.labelMedium.copy(fontFamily = bodyFamily),
            labelSmall = baseline.labelSmall.copy(fontFamily = bodyFamily),
        )
    }
}
