package com.mapo.ui.theme

import android.os.Build
import android.view.inputmethod.EditorInfo
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
import androidx.compose.ui.platform.PlatformTextInputInterceptor
import androidx.compose.ui.platform.PlatformTextInputMethodRequest
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

@OptIn(ExperimentalMaterial3ExpressiveApi::class, ExperimentalComposeUiApi::class)
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
    val typography = rememberAppTypography().applyOverrides(overrides.typography, fontResolver)
    val shapes = Shapes().applyOverrides(overrides.shapes)

    val extraColors = if (effectiveDark) MapoExtraColors.Dark else MapoExtraColors.Light
    MaterialExpressiveTheme(
        colorScheme = colorScheme,
        // Standard (critically-damped) springs instead of expressive (bouncy):
        // expressive's intentional overshoot caused the drawer to slide past its
        // open anchor and snap back, looking like a glitch. Switch back any time.
        motionScheme = MotionScheme.standard(),
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
            CompositionLocalProvider(LocalMapoExtraColors provides extraColors, content = content)
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
