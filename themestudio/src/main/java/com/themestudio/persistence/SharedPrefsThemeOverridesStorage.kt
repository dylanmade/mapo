package com.themestudio.persistence

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import com.themestudio.core.ColorOverrides
import com.themestudio.core.ColorRole
import com.themestudio.core.ColorRoles
import com.themestudio.core.ThemeOverrides
import com.themestudio.core.ThemeOverridesStorage

/**
 * SharedPreferences-backed [ThemeOverridesStorage]. One key per (variant, role)
 * — e.g. `light.primary`, `dark.surfaceContainer`. Absent keys naturally mean
 * "not overridden", which avoids needing a sentinel value.
 */
class SharedPrefsThemeOverridesStorage(
    context: Context,
    prefsName: String = DEFAULT_PREFS_NAME,
) : ThemeOverridesStorage {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(prefsName, Context.MODE_PRIVATE)

    override fun load(): ThemeOverrides = ThemeOverrides(
        light = readVariant(LIGHT_PREFIX),
        dark = readVariant(DARK_PREFIX),
    )

    override fun save(overrides: ThemeOverrides) {
        val edit = prefs.edit()
        writeVariant(edit, LIGHT_PREFIX, overrides.light)
        writeVariant(edit, DARK_PREFIX, overrides.dark)
        edit.apply()
    }

    private fun readVariant(prefix: String): ColorOverrides {
        var result = ColorOverrides()
        for (role in ColorRoles.all) {
            val key = prefix + role.name
            if (prefs.contains(key)) {
                val argb = prefs.getInt(key, 0)
                result = role.withOverride(result, Color(argb))
            }
        }
        return result
    }

    private fun writeVariant(
        edit: SharedPreferences.Editor,
        prefix: String,
        variant: ColorOverrides,
    ) {
        for (role in ColorRoles.all) {
            val key = prefix + role.name
            val value: Color? = role.readOverride(variant)
            if (value != null) {
                edit.putInt(key, value.toArgb())
            } else {
                edit.remove(key)
            }
        }
    }

    companion object {
        const val DEFAULT_PREFS_NAME = "themestudio_overrides"
        private const val LIGHT_PREFIX = "light."
        private const val DARK_PREFIX = "dark."
    }
}
