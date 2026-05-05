package com.themestudio.core

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class TypographyOverridesTest {

    @Test
    fun typographyRoles_registryHas15Entries() {
        // M3 specifies exactly 15 typography roles (3 display + 3 headline + 3 title + 3 body + 3 label).
        assertEquals(15, TypographyRoles.all.size)
    }

    @Test
    fun typographyRoles_namesAreUnique() {
        val names = TypographyRoles.all.map { it.name }
        assertEquals(names.size, names.toSet().size)
    }

    @Test
    fun applyOverrides_emptyOverrides_returnsBaseTypography() {
        val base = Typography()
        val merged = base.applyOverrides(TypographyOverrides())
        assertEquals(base.bodyLarge.fontSize, merged.bodyLarge.fontSize)
        assertEquals(base.titleLarge.fontWeight, merged.titleLarge.fontWeight)
    }

    @Test
    fun applyOverrides_partialOverride_appliesOnlyChangedFields() {
        val base = Typography(
            bodyLarge = TextStyle(
                fontSize = 16.sp,
                fontWeight = FontWeight.Normal,
                letterSpacing = 0.5.sp,
            ),
        )
        val overrides = TypographyOverrides(
            bodyLarge = TextStyleOverride(fontWeight = FontWeight.Bold),
        )
        val merged = base.applyOverrides(overrides)

        assertEquals(16.sp, merged.bodyLarge.fontSize)            // unchanged
        assertEquals(FontWeight.Bold, merged.bodyLarge.fontWeight) // overridden
        assertEquals(0.5.sp, merged.bodyLarge.letterSpacing)      // unchanged
    }

    @Test
    fun applyOverrides_isolatedToTargetRole() {
        val base = Typography(
            bodyLarge = TextStyle(fontSize = 16.sp),
            titleLarge = TextStyle(fontSize = 22.sp),
        )
        val overrides = TypographyOverrides(
            bodyLarge = TextStyleOverride(fontSize = 99.sp),
        )
        val merged = base.applyOverrides(overrides)

        assertEquals(99.sp, merged.bodyLarge.fontSize)
        assertEquals(22.sp, merged.titleLarge.fontSize) // unchanged
    }

    @Test
    fun typographyRole_withOverride_setsAndClearsRole() {
        val role = TypographyRoles.all.first { it.name == "bodyLarge" }
        val empty = TypographyOverrides()
        assertEquals(TextStyleOverride(), role.readOverride(empty))

        val applied = role.withOverride(empty, TextStyleOverride(fontSize = 18.sp))
        assertEquals(TextStyleOverride(fontSize = 18.sp), role.readOverride(applied))

        val cleared = role.withOverride(applied, TextStyleOverride())
        assertEquals(TextStyleOverride(), role.readOverride(cleared))
    }

    @Test
    fun applyOverrides_distinctTypographyResult_whenAnyFieldOverridden() {
        val base = Typography()
        val overrides = TypographyOverrides(
            displayLarge = TextStyleOverride(fontSize = 100.sp),
        )
        assertNotEquals(base, base.applyOverrides(overrides))
    }
}
