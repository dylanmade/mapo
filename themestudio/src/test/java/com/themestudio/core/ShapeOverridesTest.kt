package com.themestudio.core

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ShapeOverridesTest {

    @Test
    fun shapeRoles_registryHas5Entries() {
        // M3 Shapes has 5 corner-radius tokens: extraSmall, small, medium, large, extraLarge.
        assertEquals(5, ShapeRoles.all.size)
    }

    @Test
    fun shapeRoles_namesAreUnique() {
        val names = ShapeRoles.all.map { it.name }
        assertEquals(names.size, names.toSet().size)
    }

    @Test
    fun applyOverrides_emptyOverrides_returnsBaseShapes() {
        val base = Shapes()
        val merged = base.applyOverrides(ShapeOverrides())
        assertEquals(base.medium, merged.medium)
        assertEquals(base.large, merged.large)
    }

    @Test
    fun applyOverrides_appliesRoundedCornerShape_forOverriddenRole() {
        val base = Shapes()
        val overrides = ShapeOverrides(medium = 24.dp)
        val merged = base.applyOverrides(overrides)

        assertEquals(RoundedCornerShape(24.dp), merged.medium)
        // Other shapes untouched.
        assertEquals(base.large, merged.large)
        assertEquals(base.small, merged.small)
    }

    @Test
    fun applyOverrides_isolatedToTargetRole() {
        val base = Shapes()
        val overrides = ShapeOverrides(small = 8.dp, large = 32.dp)
        val merged = base.applyOverrides(overrides)

        assertEquals(RoundedCornerShape(8.dp), merged.small)
        assertEquals(RoundedCornerShape(32.dp), merged.large)
        assertEquals(base.medium, merged.medium) // unchanged
        assertEquals(base.extraSmall, merged.extraSmall)
        assertEquals(base.extraLarge, merged.extraLarge)
    }

    @Test
    fun shapeRole_withOverride_setsAndClearsRole() {
        val role = ShapeRoles.all.first { it.name == "medium" }
        val empty = ShapeOverrides()
        assertNull(role.readOverride(empty))

        val applied = role.withOverride(empty, 16.dp)
        assertEquals(16.dp, role.readOverride(applied))

        val cleared = role.withOverride(applied, null)
        assertNull(role.readOverride(cleared))
    }

    @Test
    fun applyOverrides_distinctShapesResult_whenAnyFieldOverridden() {
        val base = Shapes()
        val overrides = ShapeOverrides(medium = 99.dp)
        assertNotEquals(base, base.applyOverrides(overrides))
    }
}
