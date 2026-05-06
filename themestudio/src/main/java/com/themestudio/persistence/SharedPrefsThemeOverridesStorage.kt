package com.themestudio.persistence

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.themestudio.core.ColorOverrides
import com.themestudio.core.ColorRoles
import com.themestudio.core.ColorThemeOverrides
import com.themestudio.core.ShapeOverrides
import com.themestudio.core.ShapeRoles
import com.themestudio.core.TextStyleOverride
import com.themestudio.core.ThemeOverrides
import com.themestudio.core.ThemeOverridesStorage
import com.themestudio.core.TypographyOverrides
import com.themestudio.core.TypographyRoles

/**
 * SharedPreferences-backed [ThemeOverridesStorage] for the full umbrella
 * (colors + typography + shapes). Each leaf override gets one prefs key;
 * absence of a key naturally means "not overridden", so no sentinel value
 * is needed.
 *
 * Key layout:
 *   color.light.<role>           Int (ARGB)
 *   color.dark.<role>            Int (ARGB)
 *   type.<role>.fontSize         Float (sp)
 *   type.<role>.fontWeight       Int (100..900)
 *   type.<role>.letterSpacing    Float (sp)
 *   type.displayFontFamilyName   String (Google Font name)
 *   type.bodyFontFamilyName      String (Google Font name)
 *   shape.<role>                 Float (dp)
 */
class SharedPrefsThemeOverridesStorage(
    context: Context,
    prefsName: String = DEFAULT_PREFS_NAME,
) : ThemeOverridesStorage {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(prefsName, Context.MODE_PRIVATE)

    override fun load(): ThemeOverrides = ThemeOverrides(
        colors = ColorThemeOverrides(
            light = readColors(COLOR_LIGHT_PREFIX),
            dark = readColors(COLOR_DARK_PREFIX),
        ),
        typography = readTypography(),
        shapes = readShapes(),
    )

    override fun save(overrides: ThemeOverrides) {
        val edit = prefs.edit()
        writeColors(edit, COLOR_LIGHT_PREFIX, overrides.colors.light)
        writeColors(edit, COLOR_DARK_PREFIX, overrides.colors.dark)
        writeTypography(edit, overrides.typography)
        writeShapes(edit, overrides.shapes)
        edit.apply()
    }

    // ── Colors ────────────────────────────────────────────────────────────

    private fun readColors(prefix: String): ColorOverrides {
        var result = ColorOverrides()
        for (role in ColorRoles.all) {
            val key = prefix + role.name
            if (prefs.contains(key)) {
                result = role.withOverride(result, Color(prefs.getInt(key, 0)))
            }
        }
        return result
    }

    private fun writeColors(edit: SharedPreferences.Editor, prefix: String, variant: ColorOverrides) {
        for (role in ColorRoles.all) {
            val key = prefix + role.name
            val value: Color? = role.readOverride(variant)
            if (value != null) edit.putInt(key, value.toArgb()) else edit.remove(key)
        }
    }

    // ── Typography ────────────────────────────────────────────────────────

    private fun readTypography(): TypographyOverrides {
        var result = TypographyOverrides(
            displayFontFamilyName = prefs.getString(TYPE_DISPLAY_FAMILY_KEY, null),
            bodyFontFamilyName = prefs.getString(TYPE_BODY_FAMILY_KEY, null),
        )
        for (role in TypographyRoles.all) {
            val sizeKey = "$TYPE_PREFIX${role.name}.fontSize"
            val weightKey = "$TYPE_PREFIX${role.name}.fontWeight"
            val letterKey = "$TYPE_PREFIX${role.name}.letterSpacing"
            val override = TextStyleOverride(
                fontSize = if (prefs.contains(sizeKey)) prefs.getFloat(sizeKey, 14f).sp else null,
                fontWeight = if (prefs.contains(weightKey)) FontWeight(prefs.getInt(weightKey, 400)) else null,
                letterSpacing = if (prefs.contains(letterKey)) prefs.getFloat(letterKey, 0f).sp else null,
            )
            result = role.withOverride(result, override)
        }
        return result
    }

    private fun writeTypography(edit: SharedPreferences.Editor, overrides: TypographyOverrides) {
        if (overrides.displayFontFamilyName != null) edit.putString(TYPE_DISPLAY_FAMILY_KEY, overrides.displayFontFamilyName)
        else edit.remove(TYPE_DISPLAY_FAMILY_KEY)
        if (overrides.bodyFontFamilyName != null) edit.putString(TYPE_BODY_FAMILY_KEY, overrides.bodyFontFamilyName)
        else edit.remove(TYPE_BODY_FAMILY_KEY)
        for (role in TypographyRoles.all) {
            val o = role.readOverride(overrides)
            val sizeKey = "$TYPE_PREFIX${role.name}.fontSize"
            val weightKey = "$TYPE_PREFIX${role.name}.fontWeight"
            val letterKey = "$TYPE_PREFIX${role.name}.letterSpacing"
            if (o.fontSize != null) edit.putFloat(sizeKey, o.fontSize.value) else edit.remove(sizeKey)
            if (o.fontWeight != null) edit.putInt(weightKey, o.fontWeight.weight) else edit.remove(weightKey)
            if (o.letterSpacing != null) edit.putFloat(letterKey, o.letterSpacing.value) else edit.remove(letterKey)
        }
    }

    // ── Shapes ────────────────────────────────────────────────────────────

    private fun readShapes(): ShapeOverrides {
        var result = ShapeOverrides()
        for (role in ShapeRoles.all) {
            val key = SHAPE_PREFIX + role.name
            if (prefs.contains(key)) {
                result = role.withOverride(result, prefs.getFloat(key, 0f).dp)
            }
        }
        return result
    }

    private fun writeShapes(edit: SharedPreferences.Editor, overrides: ShapeOverrides) {
        for (role in ShapeRoles.all) {
            val key = SHAPE_PREFIX + role.name
            val value = role.readOverride(overrides)
            if (value != null) edit.putFloat(key, value.value) else edit.remove(key)
        }
    }

    companion object {
        const val DEFAULT_PREFS_NAME = "themestudio_overrides"
        private const val COLOR_LIGHT_PREFIX = "color.light."
        private const val COLOR_DARK_PREFIX = "color.dark."
        private const val TYPE_PREFIX = "type."
        private const val TYPE_DISPLAY_FAMILY_KEY = "type.displayFontFamilyName"
        private const val TYPE_BODY_FAMILY_KEY = "type.bodyFontFamilyName"
        private const val SHAPE_PREFIX = "shape."
    }
}
