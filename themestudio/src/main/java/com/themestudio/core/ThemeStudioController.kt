package com.themestudio.core

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp

/**
 * Observable state holder for the active [ThemeOverrides]. Reads from
 * [storage] on construction; writes through to [storage] on every mutation.
 *
 * Exposed via [LocalThemeStudioController]. Consumers that just want to
 * *read* the current overrides — like the consumer's `MapoTheme` — observe
 * [overrides] as Compose state. Consumers that want to *mutate* — like the
 * editor — call the setters.
 */
@Stable
class ThemeStudioController(
    private val storage: ThemeOverridesStorage,
) {

    private var _overrides by mutableStateOf(storage.load())

    val overrides: ThemeOverrides get() = _overrides

    // ── Colors ────────────────────────────────────────────────────────────

    fun setLightRole(role: ColorRole, color: Color?) {
        val newColors = _overrides.colors.copy(
            light = role.withOverride(_overrides.colors.light, color)
        )
        _overrides = _overrides.copy(colors = newColors)
        storage.save(_overrides)
    }

    fun setDarkRole(role: ColorRole, color: Color?) {
        val newColors = _overrides.colors.copy(
            dark = role.withOverride(_overrides.colors.dark, color)
        )
        _overrides = _overrides.copy(colors = newColors)
        storage.save(_overrides)
    }

    // ── Typography ────────────────────────────────────────────────────────

    fun setTypographyRole(role: TypographyRole, value: TextStyleOverride) {
        _overrides = _overrides.copy(
            typography = role.withOverride(_overrides.typography, value)
        )
        storage.save(_overrides)
    }

    // ── Shapes ────────────────────────────────────────────────────────────

    fun setShapeRole(role: ShapeRole, radius: Dp?) {
        _overrides = _overrides.copy(
            shapes = role.withOverride(_overrides.shapes, radius)
        )
        storage.save(_overrides)
    }

    // ── Bulk ──────────────────────────────────────────────────────────────

    fun setOverrides(next: ThemeOverrides) {
        _overrides = next
        storage.save(_overrides)
    }

    fun reset() {
        setOverrides(ThemeOverrides.EMPTY)
    }
}

private object InMemoryNoOpStorage : ThemeOverridesStorage {
    override fun load(): ThemeOverrides = ThemeOverrides.EMPTY
    override fun save(overrides: ThemeOverrides) { /* no-op */ }
}

/**
 * Sentinel "no provider in scope" controller. Reading [overrides] returns
 * [ThemeOverrides.EMPTY]; mutators harmlessly write to in-memory no-op
 * storage. Keeps the consumer's theme function safe under `@Preview` and
 * unit tests where no [ThemeStudioProvider] wraps the content.
 */
internal val NoOpThemeStudioController: ThemeStudioController =
    ThemeStudioController(InMemoryNoOpStorage)
