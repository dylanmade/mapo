package com.themestudio.core

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember

/**
 * CompositionLocal holding the active [ThemeStudioController]. Defaults to a
 * no-op so the consumer's theme function can call `LocalThemeStudioController.current`
 * unconditionally — outside a [ThemeStudioProvider] (e.g. previews, tests),
 * the no-op returns [ThemeOverrides.EMPTY] and ignores writes.
 */
val LocalThemeStudioController = compositionLocalOf<ThemeStudioController> {
    NoOpThemeStudioController
}

/**
 * Optional override for which variant (light/dark) the consumer's theme
 * function should resolve to. The editor sets this to `true`/`false` to force
 * a preview variant independent of the system setting; outside the editor
 * (the default, `null`) the consumer's theme falls back to its own logic
 * (typically `isSystemInDarkTheme()`).
 *
 * Consumer integration:
 * ```
 * val forced = LocalThemeStudioVariantOverride.current
 * val effectiveDark = forced ?: isSystemInDarkTheme()
 * ```
 */
val LocalThemeStudioVariantOverride = compositionLocalOf<Boolean?> { null }

/**
 * Wrap your app content (above your theme function) with this composable to
 * enable runtime theme editing. The provided [storage] is read once on first
 * composition and written on every override change.
 *
 * Typical wiring:
 * ```
 * setContent {
 *     ThemeStudioProvider(storage = SharedPrefsThemeOverridesStorage(this)) {
 *         MapoTheme { /* app content */ }
 *     }
 * }
 * ```
 */
@Composable
fun ThemeStudioProvider(
    storage: ThemeOverridesStorage,
    content: @Composable () -> Unit,
) {
    val controller = remember(storage) { ThemeStudioController(storage) }
    CompositionLocalProvider(LocalThemeStudioController provides controller) {
        content()
    }
}
