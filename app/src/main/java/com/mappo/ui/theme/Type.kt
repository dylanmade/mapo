package com.mappo.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp
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
 *
 * Beyond setting `fontFamily`, this builder also strips M3's Roboto-tuned
 * `letterSpacing` and `lineHeight` from every role. Those baseline values
 * (e.g. -0.25sp tracking on `displayLarge`, 0.5sp on `bodyLarge`, 64sp line
 * height on `displayLarge`) are calibrated for Roboto and don't translate
 * to other typefaces — leaving them in place would silently impose Roboto's
 * "look" on PP Mori (or any other font the user picks). Instead each role
 * uses `letterSpacing = 0.sp` (no extra tracking, font-natural kerning
 * applies) and `lineHeight = Unspecified` (font-natural line height
 * computed from intrinsic ascent/descent metrics). Users can opt back into
 * specific tracking via the Theme Studio picker if they want.
 */
@Composable
fun rememberAppTypography(): Typography {
    val resolve = rememberThemeFontResolver()
    return remember(resolve) {
        val displayFamily = resolve(DEFAULT_DISPLAY_FONT_NAME)
        val bodyFamily = resolve(DEFAULT_BODY_FONT_NAME)
        val baseline = Typography()
        Typography(
            displayLarge = baseline.displayLarge.fontRespecting(displayFamily),
            displayMedium = baseline.displayMedium.fontRespecting(displayFamily),
            displaySmall = baseline.displaySmall.fontRespecting(displayFamily),
            headlineLarge = baseline.headlineLarge.fontRespecting(displayFamily),
            headlineMedium = baseline.headlineMedium.fontRespecting(displayFamily),
            headlineSmall = baseline.headlineSmall.fontRespecting(displayFamily),
            titleLarge = baseline.titleLarge.fontRespecting(displayFamily),
            titleMedium = baseline.titleMedium.fontRespecting(displayFamily),
            titleSmall = baseline.titleSmall.fontRespecting(displayFamily),
            bodyLarge = baseline.bodyLarge.fontRespecting(bodyFamily),
            bodyMedium = baseline.bodyMedium.fontRespecting(bodyFamily),
            bodySmall = baseline.bodySmall.fontRespecting(bodyFamily),
            labelLarge = baseline.labelLarge.fontRespecting(bodyFamily),
            labelMedium = baseline.labelMedium.fontRespecting(bodyFamily),
            labelSmall = baseline.labelSmall.fontRespecting(bodyFamily),
        )
    }
}

/**
 * Swap in [family] and strip M3's Roboto-calibrated tracking + leading so
 * the font's own metrics drive rendering. See [rememberAppTypography] for
 * the philosophy.
 */
private fun TextStyle.fontRespecting(family: FontFamily): TextStyle = copy(
    fontFamily = family,
    letterSpacing = 0.sp,
    lineHeight = TextUnit.Unspecified,
)
