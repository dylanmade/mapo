package com.themestudio.core

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * JVM unit-test smoke check. Confirms the test harness compiles and runs by
 * exercising the [ColorRoles] registry and override round-trip — pure Kotlin,
 * no Android framework dependencies.
 */
class ColorOverridesTest {

    @Test
    fun colorRoles_registryIsNonEmpty() {
        assertNotNull(ColorRoles.all)
        assert(ColorRoles.all.isNotEmpty()) { "ColorRoles.all must not be empty" }
    }

    @Test
    fun colorRoles_namesAreUnique() {
        val names = ColorRoles.all.map { it.name }
        assertEquals(
            "ColorRoles.all has duplicate role names",
            names.size,
            names.toSet().size,
        )
    }

    @Test
    fun withOverride_setsAndClearsRole() {
        val role = ColorRoles.all.first { it.name == "primary" }
        val empty = ColorOverrides()
        assertNull(role.readOverride(empty))

        val withRed = role.withOverride(empty, Color.Red)
        assertEquals(Color.Red, role.readOverride(withRed))

        val cleared = role.withOverride(withRed, null)
        assertNull(role.readOverride(cleared))
    }

    @Test
    fun withOverride_isolatedToTargetRole() {
        val primary = ColorRoles.all.first { it.name == "primary" }
        val secondary = ColorRoles.all.first { it.name == "secondary" }

        val o = primary.withOverride(ColorOverrides(), Color.Red)
        assertEquals(Color.Red, primary.readOverride(o))
        assertNull(secondary.readOverride(o))
    }
}
