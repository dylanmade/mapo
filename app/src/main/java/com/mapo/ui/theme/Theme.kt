package com.mapo.ui.theme

import android.os.Build
import android.view.inputmethod.EditorInfo
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.gestures.BringIntoViewSpec
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
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
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.InterceptPlatformTextInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.PlatformTextInputInterceptor
import androidx.compose.ui.platform.PlatformTextInputMethodRequest
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import com.themestudio.core.LocalThemeStudioController
import com.themestudio.core.LocalThemeStudioVariantOverride
import com.themestudio.core.applyOverrides
import com.themestudio.core.rememberThemeFontResolver
import com.materialkolor.PaletteStyle
import com.materialkolor.dynamiccolor.ColorSpec
import com.materialkolor.rememberDynamicMaterialThemeState

/**
 * MaterialKolor seed — DodgerBlue (#1E90FF). The base color scheme is generated from this seed
 * (SPEC_2025 expressive, TonalSpot) rather than a hand-authored palette, via materialkolor.com.
 * Change the seed to re-tint the whole app.
 */
private val MapoSeedColor = Color(0xFF1E90FF)

/** Map a Theme-Studio palette-style name (see `ColorGenerationOverrides.STYLE_NAMES`) to MaterialKolor's. */
private fun paletteStyleFromName(name: String?): PaletteStyle = when (name) {
    "Neutral" -> PaletteStyle.Neutral
    "Vibrant" -> PaletteStyle.Vibrant
    "Expressive" -> PaletteStyle.Expressive
    "Rainbow" -> PaletteStyle.Rainbow
    "FruitSalad" -> PaletteStyle.FruitSalad
    "Monochrome" -> PaletteStyle.Monochrome
    "Fidelity" -> PaletteStyle.Fidelity
    "Content" -> PaletteStyle.Content
    else -> PaletteStyle.TonalSpot
}

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

@OptIn(
    ExperimentalMaterial3ExpressiveApi::class,
    ExperimentalComposeUiApi::class,
    ExperimentalFoundationApi::class,
)
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
    val controller = LocalThemeStudioController.current
    val overrides = controller.overrides
    // MaterialKolor generates the base scheme from a seed (SPEC_2025 expressive). The seed, palette
    // style, and contrast are live-editable from Theme Studio's Colors tab (colorGeneration
    // overrides), falling back to [MapoSeedColor] / TonalSpot / 0. This replaces the hand-authored
    // lightScheme/darkScheme (kept below as a fallback reference). Wallpaper dynamicColor still wins.
    val gen = overrides.colorGeneration
    val materialKolorState = rememberDynamicMaterialThemeState(
        isDark = effectiveDark,
        style = paletteStyleFromName(gen.style),
        contrastLevel = (gen.contrast ?: 0f).toDouble(),
        specVersion = ColorSpec.SpecVersion.SPEC_2025,
        seedColor = gen.seed ?: MapoSeedColor,
    )
    val baseScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (effectiveDark) dynamicDarkColorScheme(context)
            else dynamicLightColorScheme(context)
        }
        else -> materialKolorState.colorScheme
    }
    val variantColors =
        if (effectiveDark) overrides.colors.dark else overrides.colors.light
    val colorScheme = baseScheme.applyOverrides(variantColors)
    val fontResolver = rememberThemeFontResolver()
    val typography = rememberAppTypography().applyOverrides(overrides.typography, fontResolver)
    val shapes = Shapes().applyOverrides(overrides.shapes)

    val extraColors = if (effectiveDark) MapoExtraColors.Dark else MapoExtraColors.Light
    MaterialExpressiveTheme(
        colorScheme = colorScheme,
        // Expressive (bouncy) motion — enabled with the MaterialKolor expressive palette. NOTE:
        // expressive overshoot previously made the ModalNavigationDrawer slide past its open anchor
        // and snap back; re-verify the drawer + other overshoot-sensitive components on device.
        motionScheme = MotionScheme.expressive(),
        typography = typography,
        shapes = shapes,
    ) {
        // Force the soft keyboard to NOT go fullscreen ("extract" mode). On landscape
        // handhelds the IME defaults to a fullscreen editor that covers the app, and in
        // that mode Compose text fields don't refresh on delete (backspace stays stale
        // until the next keystroke). Compose has no per-field IME flag, so we intercept
        // the platform text-input request and OR IME_FLAG_NO_FULLSCREEN/NO_EXTRACT_UI
        // into the EditorInfo. Living in MapoTheme means every host (both activities +
        // the overlay windows) and every current/future field inherits it — one place,
        // no per-field or per-screen maintenance.
        val noFullscreenIme = remember {
            PlatformTextInputInterceptor { request, nextHandler ->
                val patched = PlatformTextInputMethodRequest { outAttrs ->
                    request.createInputConnection(outAttrs).also {
                        outAttrs.imeOptions = outAttrs.imeOptions or
                            EditorInfo.IME_FLAG_NO_FULLSCREEN or
                            EditorInfo.IME_FLAG_NO_EXTRACT_UI
                    }
                }
                nextHandler.startInputMethod(patched)
            }
        }
        InterceptPlatformTextInput(interceptor = noFullscreenIme) {
            // Reserve a small margin when a scroll container brings a focused child into
            // view, so a field scrolled up above the docked keyboard rests just above it
            // instead of flush against its top edge. This is a scroll offset, not a layout
            // gap, so there's no visible stripe. It also gives focused items a little
            // breathing room from any scroll-container edge generally.
            val bringIntoViewDensity = LocalDensity.current
            val bringIntoViewSpec = remember(bringIntoViewDensity) {
                val marginPx = with(bringIntoViewDensity) { 16.dp.toPx() }
                object : BringIntoViewSpec {
                    override fun calculateScrollDistance(
                        offset: Float,
                        size: Float,
                        containerSize: Float,
                    ): Float {
                        // Same logic as the default spec, but treats the item as [marginPx]
                        // larger on each edge so the scroll leaves that much margin.
                        val leadingEdge = offset - marginPx
                        val trailingEdge = offset + size + marginPx
                        return when {
                            leadingEdge >= 0 && trailingEdge <= containerSize -> 0f
                            leadingEdge < 0 && trailingEdge > containerSize -> 0f
                            abs(leadingEdge) < abs(trailingEdge - containerSize) -> leadingEdge
                            else -> trailingEdge - containerSize
                        }
                    }
                }
            }
            CompositionLocalProvider(
                LocalMapoExtraColors provides extraColors,
                LocalBringIntoViewSpec provides bringIntoViewSpec,
                content = content,
            )
        }
    }
}

/**
 * Project-specific colors that don't have a clean role in the M3 [ColorScheme]. Currently:
 *  - drag-and-drop zone indicators, where users expect literal green / red regardless of
 *    the active theme palette (theme tertiary/error read as "another accent / destructive
 *    action," not "valid / invalid drop target").
 *  - the in-editor button-selection outline, which is intentionally near-white in both
 *    modes so it reads as a high-contrast "overlay" rather than a color-keyed accent.
 */
data class MapoExtraColors(
    val dropZoneValid: Color,
    val dropZoneInvalid: Color,
    val selectionOutline: Color,
) {
    companion object {
        // One lightness step down from pure white — bright enough to read as luminous,
        // not so pure that it loses anti-aliasing on light surfaces.
        private val SelectionOutlineNearWhite = Color(0xFFF2F2F2)

        val Light = MapoExtraColors(
            dropZoneValid = Color(0xFF2E7D32),    // M-spec green 800 — readable on light fills
            dropZoneInvalid = Color(0xFFC62828),  // M-spec red 800
            selectionOutline = SelectionOutlineNearWhite,
        )
        val Dark = MapoExtraColors(
            dropZoneValid = Color(0xFF66BB6A),    // green 400 — lifts off dark surface
            dropZoneInvalid = Color(0xFFEF5350),  // red 400
            selectionOutline = SelectionOutlineNearWhite,
        )
    }
}

val LocalMapoExtraColors = compositionLocalOf { MapoExtraColors.Light }
