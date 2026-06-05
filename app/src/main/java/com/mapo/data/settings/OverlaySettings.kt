package com.mapo.data.settings

import android.content.Context
import android.content.SharedPreferences
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Settings for the rebuilt button overlay (`OVERLAY_REBUILD_PLAN.md`, Brick D). The
 * overlay's "own settings" surface, exposed in-context in the live editor's toolbar.
 *
 * [snapEnabled] governs whether dragging a button in the editor snaps its edges/centers
 * to a grid and to nearby siblings, so groups line up cleanly. Mirrors the
 * `SharedPreferences` + `StateFlow` pattern of [AutoSwitchSettings].
 */
@Singleton
class OverlaySettings @Inject constructor(
    @ApplicationContext context: Context,
) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _snapEnabled = MutableStateFlow(prefs.getBoolean(KEY_SNAP_ENABLED, true))
    val snapEnabled: StateFlow<Boolean> = _snapEnabled.asStateFlow()

    private val listener = SharedPreferences.OnSharedPreferenceChangeListener { sp, key ->
        if (key == KEY_SNAP_ENABLED) {
            _snapEnabled.value = sp.getBoolean(KEY_SNAP_ENABLED, true)
        }
    }

    init {
        prefs.registerOnSharedPreferenceChangeListener(listener)
    }

    fun setSnapEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_SNAP_ENABLED, enabled).apply()
    }

    companion object {
        private const val PREFS_NAME = "overlay_settings"
        private const val KEY_SNAP_ENABLED = "snap_enabled"

        /** Fine grid the editor snaps to (per axis), in addition to sibling edges. */
        const val GRID_DIVISIONS = 24
        /** How close (dp) an edge must be to a snap target before it grabs. */
        const val SNAP_THRESHOLD_DP = 16
    }
}
