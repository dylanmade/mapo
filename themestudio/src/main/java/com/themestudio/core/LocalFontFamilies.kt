package com.themestudio.core

import android.content.res.AssetManager
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight

/**
 * Locally-bundled font families that ship as assets inside this module —
 * an alternative to the GMS Fonts catalog in [GoogleFontsCatalog].
 *
 * !! REDISTRIBUTION WARNING !!
 * Some entries here have `redistributable = false`: their license forbids
 * shipping them outside this in-house build. When themestudio is extracted
 * or published as a library, the corresponding files under
 * `assets/fonts/restricted/` MUST be deleted and the matching entries here
 * MUST be removed. See `assets/fonts/restricted/LICENSE.txt` for inventory.
 */

/** One concrete weight/style of a [LocalFontFamilySpec], pointing at a single asset file. */
data class LocalFontVariant(
    val weight: FontWeight,
    val style: FontStyle,
    /** Path relative to `assets/`, e.g. `fonts/restricted/ppmori/PPMori-Regular.otf`. */
    val assetPath: String,
)

/**
 * Metadata for a font family bundled as assets. The picker shows
 * [displayName]; the resolver matches on [displayName] (case-insensitive)
 * before falling back to GMS, so the name should be unique enough not to
 * collide with a Google Fonts entry.
 *
 * [redistributable] gates whether this family can ship in standalone /
 * published builds of themestudio. The picker badges non-redistributable
 * families and the export dialog flags them in generated theme code.
 */
data class LocalFontFamilySpec(
    val displayName: String,
    val licenseNote: String,
    val redistributable: Boolean,
    val variants: List<LocalFontVariant>,
)

object LocalFontRegistry {
    val all: List<LocalFontFamilySpec> = listOf(
        LocalFontFamilySpec(
            displayName = "PP Mori",
            licenseNote = "Personal use only — not redistributable (Pangram Pangram Foundry)",
            redistributable = false,
            variants = listOf(
                LocalFontVariant(FontWeight.ExtraLight, FontStyle.Normal, "fonts/restricted/ppmori/PPMori-Extralight.otf"),
                LocalFontVariant(FontWeight.ExtraLight, FontStyle.Italic, "fonts/restricted/ppmori/PPMori-ExtralightItalic.otf"),
                LocalFontVariant(FontWeight.Normal, FontStyle.Normal, "fonts/restricted/ppmori/PPMori-Regular.otf"),
                LocalFontVariant(FontWeight.Normal, FontStyle.Italic, "fonts/restricted/ppmori/PPMori-Italic.otf"),
                LocalFontVariant(FontWeight.SemiBold, FontStyle.Normal, "fonts/restricted/ppmori/PPMori-Semibold.otf"),
                LocalFontVariant(FontWeight.SemiBold, FontStyle.Italic, "fonts/restricted/ppmori/PPMori-SemiboldItalic.otf"),
                LocalFontVariant(FontWeight.Black, FontStyle.Normal, "fonts/restricted/ppmori/PPMori-Black.otf"),
                LocalFontVariant(FontWeight.Black, FontStyle.Italic, "fonts/restricted/ppmori/PPMori-BlackItalic.otf"),
            ),
        ),
        LocalFontFamilySpec(
            displayName = "Gill Sans",
            licenseNote = "Commercial typeface (Monotype) — in-house use only, not redistributable",
            redistributable = false,
            // The Condensed cuts are a separate width family and are deliberately excluded.
            variants = listOf(
                LocalFontVariant(FontWeight.Light, FontStyle.Normal, "fonts/restricted/gillsans/GillSans-Light.otf"),
                LocalFontVariant(FontWeight.Light, FontStyle.Italic, "fonts/restricted/gillsans/GillSans-LightItalic.otf"),
                LocalFontVariant(FontWeight.Normal, FontStyle.Normal, "fonts/restricted/gillsans/GillSans-Regular.otf"),
                LocalFontVariant(FontWeight.Normal, FontStyle.Italic, "fonts/restricted/gillsans/GillSans-Italic.otf"),
                LocalFontVariant(FontWeight.Medium, FontStyle.Normal, "fonts/restricted/gillsans/GillSans-Medium.otf"),
                LocalFontVariant(FontWeight.Medium, FontStyle.Italic, "fonts/restricted/gillsans/GillSans-MediumItalic.otf"),
                LocalFontVariant(FontWeight.Bold, FontStyle.Normal, "fonts/restricted/gillsans/GillSans-Bold.otf"),
                LocalFontVariant(FontWeight.Bold, FontStyle.Italic, "fonts/restricted/gillsans/GillSans-BoldItalic.otf"),
                LocalFontVariant(FontWeight.ExtraBold, FontStyle.Normal, "fonts/restricted/gillsans/GillSans-Heavy.otf"),
                LocalFontVariant(FontWeight.ExtraBold, FontStyle.Italic, "fonts/restricted/gillsans/GillSans-HeavyItalic.otf"),
            ),
        ),
    )

    fun findByDisplayName(name: String): LocalFontFamilySpec? {
        val key = name.trim()
        if (key.isEmpty()) return null
        return all.firstOrNull { it.displayName.equals(key, ignoreCase = true) }
    }
}

/**
 * Resolved [FontFamily] cache keyed by display name. AssetManager lifetime
 * is process-scoped so caching by name is safe — if the manager were ever
 * swapped (it isn't, in practice) the cache would just hold stale handles
 * until cleared.
 */
private const val LOCAL_FAMILY_CACHE_CAP = 8

private val localFamilyCache: MutableMap<String, FontFamily> =
    object : LinkedHashMap<String, FontFamily>(8, 0.75f, /* accessOrder = */ true) {
        override fun removeEldestEntry(eldest: Map.Entry<String, FontFamily>): Boolean =
            size > LOCAL_FAMILY_CACHE_CAP
    }

/**
 * Build (and cache) a [FontFamily] for [spec] using [assets] for asset
 * resolution. Variants in [spec] map 1:1 to [Font] entries; Compose picks
 * the closest weight/style at render time.
 */
fun localFontFamily(spec: LocalFontFamilySpec, assets: AssetManager): FontFamily {
    synchronized(localFamilyCache) {
        localFamilyCache[spec.displayName]?.let { return it }
        val fonts = spec.variants.map { v ->
            Font(path = v.assetPath, assetManager = assets, weight = v.weight, style = v.style)
        }
        val family = FontFamily(fonts)
        localFamilyCache[spec.displayName] = family
        return family
    }
}
