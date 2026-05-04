package com.themestudio.core

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color

/**
 * Observable state holder for the active [ThemeOverrides]. Reads from
 * [storage] on construction; writes through to [storage] on every mutation.
 *
 * The controller is exposed to the rest of the app via [LocalThemeStudioController]
 * (set up by [ThemeStudioProvider]). Consumers that just want to *read* the
 * current overrides — like the consumer's `MapoTheme` — should observe
 * [overrides] as Compose state. Consumers that want to *mutate* — like the
 * editor screen — call the setters.
 */
@Stable
class ThemeStudioController(
    private val storage: ThemeOverridesStorage,
) {

    private var _overrides by mutableStateOf(storage.load())

    /** Current overrides. Snapshots cleanly through Compose. */
    val overrides: ThemeOverrides get() = _overrides

    fun setLightRole(role: ColorRole, color: Color?) {
        _overrides = _overrides.copy(light = role.withOverride(_overrides.light, color))
        storage.save(_overrides)
    }

    fun setDarkRole(role: ColorRole, color: Color?) {
        _overrides = _overrides.copy(dark = role.withOverride(_overrides.dark, color))
        storage.save(_overrides)
    }

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
 * storage. This keeps the consumer's theme function safe to call from
 * `@Preview` blocks and unit tests where no [ThemeStudioProvider] wraps
 * the content.
 */
internal val NoOpThemeStudioController: ThemeStudioController =
    ThemeStudioController(InMemoryNoOpStorage)
