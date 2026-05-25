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
 * Persistent ack for the [com.mapo.ui.screen.dialog.ShizukuRequiredDialog]
 * (Brick G). The dialog fires the first time the user picks an analog mode in
 * the Remap Controls mode dropdown while Shizuku is NOT ready. Once
 * acknowledged we never show it again — subsequent analog-mode picks proceed
 * silently even if Shizuku is still unconfigured (the user has been told; the
 * persistent health notification + Setup screen are the durable surfaces from
 * there on).
 *
 * SharedPreferences backed to match the rest of the codebase
 * (see [AutoSwitchSettings]) — DataStore would be a one-class new dependency
 * to justify nothing else uses.
 */
@Singleton
class ShizukuRequiredPreferences @Inject constructor(
    @ApplicationContext context: Context,
) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _acknowledged = MutableStateFlow(
        prefs.getBoolean(KEY_ACKED, false),
    )
    val acknowledged: StateFlow<Boolean> = _acknowledged.asStateFlow()

    private val listener = SharedPreferences.OnSharedPreferenceChangeListener { sp, key ->
        if (key == KEY_ACKED) {
            _acknowledged.value = sp.getBoolean(KEY_ACKED, false)
        }
    }

    init {
        prefs.registerOnSharedPreferenceChangeListener(listener)
    }

    fun setAcknowledged() {
        prefs.edit().putBoolean(KEY_ACKED, true).apply()
    }

    companion object {
        // New file name so a stale install doesn't carry over the pre-pivot
        // (focused-overlay-era) ack — the new dialog explains Shizuku, not the
        // overlay tradeoffs. Users who acked the old dialog should see the new
        // one once.
        private const val PREFS_NAME = "mapo_shizuku_required"
        private const val KEY_ACKED = "shizuku_required_acked_v1"
    }
}
