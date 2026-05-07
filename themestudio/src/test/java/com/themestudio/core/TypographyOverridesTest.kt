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

    @Test
    fun applyOverrides_displayFamily_appliesToDisplayHeadlineTitle_only() {
        val base = Typography()
        val merged = base.applyOverrides(TypographyOverrides(displayFontFamilyName = "Roboto"))
        // display/headline/title should pick up the swapped family
        assertNotEquals(base.displayLarge.fontFamily, merged.displayLarge.fontFamily)
        assertNotEquals(base.headlineMedium.fontFamily, merged.headlineMedium.fontFamily)
        assertNotEquals(base.titleSmall.fontFamily, merged.titleSmall.fontFamily)
        // body/label should remain untouched
        assertEquals(base.bodyLarge.fontFamily, merged.bodyLarge.fontFamily)
        assertEquals(base.labelMedium.fontFamily, merged.labelMedium.fontFamily)
    }

    @Test
    fun applyOverrides_bodyFamily_appliesToBodyLabel_only() {
        val base = Typography()
        val merged = base.applyOverrides(TypographyOverrides(bodyFontFamilyName = "Inter"))
        assertNotEquals(base.bodyLarge.fontFamily, merged.bodyLarge.fontFamily)
        assertNotEquals(base.labelSmall.fontFamily, merged.labelSmall.fontFamily)
        assertEquals(base.displayLarge.fontFamily, merged.displayLarge.fontFamily)
        assertEquals(base.headlineLarge.fontFamily, merged.headlineLarge.fontFamily)
    }

    @Test
    fun applyOverrides_displayUmbrella_cascadesAcrossDisplayHeadlineTitle() {
        val base = Typography()
        val merged = base.applyOverrides(
            TypographyOverrides(displayUmbrella = TextStyleOverride(fontSize = 24.sp)),
        )
        assertEquals(24.sp, merged.displayLarge.fontSize)
        assertEquals(24.sp, merged.headlineMedium.fontSize)
        assertEquals(24.sp, merged.titleSmall.fontSize)
        // body/label unchanged
        assertEquals(base.bodyLarge.fontSize, merged.bodyLarge.fontSize)
        assertEquals(base.labelLarge.fontSize, merged.labelLarge.fontSize)
    }

    @Test
    fun applyOverrides_bodyUmbrella_cascadesAcrossBodyLabel() {
        val base = Typography()
        val merged = base.applyOverrides(
            TypographyOverrides(bodyUmbrella = TextStyleOverride(fontWeight = FontWeight.Bold)),
        )
        assertEquals(FontWeight.Bold, merged.bodyLarge.fontWeight)
        assertEquals(FontWeight.Bold, merged.labelSmall.fontWeight)
        // display group unchanged
        assertEquals(base.displayLarge.fontWeight, merged.displayLarge.fontWeight)
    }

    @Test
    fun applyOverrides_umbrella_overridesPerRoleSameField() {
        // Umbrella beats per-role for the same field; per-role still wins for fields the umbrella leaves null.
        val base = Typography()
        val overrides = TypographyOverrides(
            displayUmbrella = TextStyleOverride(fontSize = 30.sp),
            displayLarge = TextStyleOverride(
                fontSize = 99.sp,                // shadowed by umbrella
                fontWeight = FontWeight.Black,   // umbrella has no weight → per-role wins
            ),
        )
        val merged = base.applyOverrides(overrides)
        assertEquals(30.sp, merged.displayLarge.fontSize)
        assertEquals(FontWeight.Black, merged.displayLarge.fontWeight)
    }

    @Test
    fun applyOverrides_umbrellaCleared_perRoleReasserts() {
        val base = Typography()
        val withBoth = TypographyOverrides(
            displayUmbrella = TextStyleOverride(fontSize = 30.sp),
            displayLarge = TextStyleOverride(fontSize = 99.sp),
        )
        val cleared = withBoth.copy(displayUmbrella = TextStyleOverride())
        assertEquals(99.sp, base.applyOverrides(cleared).displayLarge.fontSize)
    }

    @Test
    fun umbrellaRoles_readWriteRoundTrip() {
        val empty = TypographyOverrides()
        assertEquals(TextStyleOverride(), UmbrellaRoles.display.readOverride(empty))
        val applied = UmbrellaRoles.display.withOverride(empty, TextStyleOverride(fontSize = 22.sp))
        assertEquals(TextStyleOverride(fontSize = 22.sp), UmbrellaRoles.display.readOverride(applied))
        // Doesn't bleed into per-role
        assertEquals(TextStyleOverride(), applied.displayLarge)
    }
}
