package com.themestudio.core

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.Dp

/**
 * Override container for the 5 Material 3 shape tokens. Each value is a
 * corner radius in [Dp]; absent (null) values fall through to the base
 * [Shapes].
 *
 * v1 only supports [RoundedCornerShape] overrides because that's what M3's
 * default `Shapes()` uses. Cut/asymmetric corners aren't surfaced — they're
 * uncommon and not exposed to the editor UI.
 */
data class ShapeOverrides(
    val extraSmall: Dp? = null,
    val small: Dp? = null,
    val medium: Dp? = null,
    val large: Dp? = null,
    val extraLarge: Dp? = null,
)

/** Returns a copy of [this] with non-null override radii applied as RoundedCornerShape. */
fun Shapes.applyOverrides(o: ShapeOverrides): Shapes = copy(
    extraSmall = o.extraSmall?.let { RoundedCornerShape(it) } ?: extraSmall,
    small = o.small?.let { RoundedCornerShape(it) } ?: small,
    medium = o.medium?.let { RoundedCornerShape(it) } ?: medium,
    large = o.large?.let { RoundedCornerShape(it) } ?: large,
    extraLarge = o.extraLarge?.let { RoundedCornerShape(it) } ?: extraLarge,
)

/**
 * Reflective table for the editor / persistence to iterate over shape
 * tokens by name.
 */
data class ShapeRole(
    val name: String,
    val readOverride: (ShapeOverrides) -> Dp?,
    val withOverride: (ShapeOverrides, Dp?) -> ShapeOverrides,
)

object ShapeRoles {
    val all: List<ShapeRole> = listOf(
        ShapeRole("extraSmall", { it.extraSmall }, { o, v -> o.copy(extraSmall = v) }),
        ShapeRole("small", { it.small }, { o, v -> o.copy(small = v) }),
        ShapeRole("medium", { it.medium }, { o, v -> o.copy(medium = v) }),
        ShapeRole("large", { it.large }, { o, v -> o.copy(large = v) }),
        ShapeRole("extraLarge", { it.extraLarge }, { o, v -> o.copy(extraLarge = v) }),
    )
}
