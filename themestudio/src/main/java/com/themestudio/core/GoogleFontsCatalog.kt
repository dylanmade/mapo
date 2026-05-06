package com.themestudio.core

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.googlefonts.Font
import androidx.compose.ui.text.googlefonts.GoogleFont
import com.themestudio.R

/**
 * Catalog of Google Fonts the typography editor surfaces in its family
 * pickers. Names are loaded from res/raw/google_fonts.txt — a snapshot of
 * Google's metadata feed (https://fonts.google.com/metadata/fonts), one
 * family per line. The picker also accepts free-text input so families
 * added after the snapshot remain reachable.
 *
 * Why on-demand: building a [FontFamily] is just a descriptor; the actual
 * font bytes are downloaded by Google Play Services on first render and
 * disk-cached at the system level. We don't ship any font binaries — only
 * the certificate array (in this module's res/values-v23/font_certs.xml)
 * for the GMS Fonts provider.
 */
object GoogleFontsCatalog {
    @Volatile
    private var cached: List<String>? = null

    /**
     * Load the catalog (idempotent). Subsequent calls return the cached
     * list regardless of [context]. Safe to call from any thread.
     */
    fun load(context: Context): List<String> {
        cached?.let { return it }
        synchronized(this) {
            cached?.let { return it }
            val list = context.resources
                .openRawResource(R.raw.google_fonts)
                .bufferedReader()
                .useLines { lines -> lines.map { it.trim() }.filter { it.isNotEmpty() }.toList() }
            cached = list
            return list
        }
    }
}

/**
 * Composable convenience: read the catalog using [LocalContext]. Wraps the
 * load in [remember] so the list is read once per composition tree.
 */
@Composable
fun rememberGoogleFontsCatalog(): List<String> {
    val ctx = LocalContext.current
    return remember(ctx) { GoogleFontsCatalog.load(ctx) }
}

/**
 * Provider for the GMS Fonts service. The certificate array lives in this
 * module's res/values-v23/font_certs.xml. Exposed publicly so the consumer
 * (e.g. app's Type.kt) can also reuse it when constructing its own
 * compiled-in [FontFamily] values — keeps the cert resource in one place.
 */
val GoogleFontsProvider: GoogleFont.Provider = GoogleFont.Provider(
    providerAuthority = "com.google.android.gms.fonts",
    providerPackage = "com.google.android.gms",
    certificates = R.array.com_google_android_gms_fonts_certs,
)

/**
 * In-memory LRU of resolved [FontFamily] descriptors. Cap is 10: smaller
 * descriptors get evicted when the cache fills, but eviction is cheap —
 * the next call rebuilds the descriptor in microseconds and GMS still has
 * the actual font bytes on disk, so re-renders don't re-download.
 */
private const val FAMILY_CACHE_CAP = 10

private val familyCache: MutableMap<String, FontFamily> =
    object : LinkedHashMap<String, FontFamily>(16, 0.75f, /* accessOrder = */ true) {
        override fun removeEldestEntry(eldest: Map.Entry<String, FontFamily>): Boolean =
            size > FAMILY_CACHE_CAP
    }

/**
 * Resolve a Google Font family by name. Returns a cached descriptor when
 * the name was recently used; otherwise builds (and caches) a new one.
 * Thread-safe: callable from any thread, including Compose recomposition.
 */
fun googleFontFamily(name: String): FontFamily {
    val key = name.trim()
    synchronized(familyCache) {
        familyCache[key]?.let { return it }
        val family = FontFamily(
            Font(googleFont = GoogleFont(key), fontProvider = GoogleFontsProvider),
        )
        familyCache[key] = family
        return family
    }
}
