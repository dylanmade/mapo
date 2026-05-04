package com.themestudio.core

/**
 * Pluggable persistence for [ThemeOverrides]. The default implementation lives
 * in [com.themestudio.persistence.SharedPrefsThemeOverridesStorage]; consumers
 * can supply their own (DataStore, in-memory, file, etc.) without touching the
 * core or UI layers.
 */
interface ThemeOverridesStorage {
    fun load(): ThemeOverrides
    fun save(overrides: ThemeOverrides)
}
