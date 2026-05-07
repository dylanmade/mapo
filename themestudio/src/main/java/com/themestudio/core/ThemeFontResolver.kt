package com.themestudio.core

import android.content.res.AssetManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily

/**
 * Unified family lookup used by the typography editor and theme application.
 * Checks [LocalFontRegistry] first (asset-bundled families) and falls back
 * to [googleFontFamily] (GMS Fonts) for everything else. This keeps the
 * persisted override name as a plain string while letting non-Google
 * families resolve through their own asset-loaded [FontFamily].
 */
typealias ThemeFontResolver = (String) -> FontFamily

/** Static resolver for callers that already have an [AssetManager]. */
fun resolveThemeFontFamily(name: String, assets: AssetManager): FontFamily {
    val key = name.trim()
    LocalFontRegistry.findByDisplayName(key)?.let { return localFontFamily(it, assets) }
    return googleFontFamily(key)
}

/**
 * Composable handle that yields a name → [FontFamily] resolver scoped to
 * the current context's [AssetManager]. Intended to be passed to
 * [Typography.applyOverrides] from the theme function and to picker
 * preview composables.
 */
@Composable
fun rememberThemeFontResolver(): ThemeFontResolver {
    val assets = LocalContext.current.assets
    return remember(assets) { { name -> resolveThemeFontFamily(name, assets) } }
}
