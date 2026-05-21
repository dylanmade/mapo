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
 * Persistent ack for the analog-mode tradeoffs dialog (Brick 4). The dialog
 * fires the first time the user picks an analog mode in the Remap Controls
 * mode dropdown; once acknowledged we never show it again.
 *
 * SharedPreferences backed to match the rest of the codebase
 * (see [AutoSwitchSettings]) — DataStore would be a one-class new dependency
 * to justify nothing else uses.
 */
@Singleton
class AnalogModePreferences @Inject constructor(
    @ApplicationContext context: Context,
) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _tradeoffsAcknowledged = MutableStateFlow(
        prefs.getBoolean(KEY_TRADEOFFS_ACKED, false),
    )
    val tradeoffsAcknowledged: StateFlow<Boolean> = _tradeoffsAcknowledged.asStateFlow()

    private val listener = SharedPreferences.OnSharedPreferenceChangeListener { sp, key ->
        if (key == KEY_TRADEOFFS_ACKED) {
            _tradeoffsAcknowledged.value = sp.getBoolean(KEY_TRADEOFFS_ACKED, false)
        }
    }

    init {
        prefs.registerOnSharedPreferenceChangeListener(listener)
    }

    fun setTradeoffsAcknowledged() {
        prefs.edit().putBoolean(KEY_TRADEOFFS_ACKED, true).apply()
    }

    companion object {
        private const val PREFS_NAME = "mapo_analog_mode"
        private const val KEY_TRADEOFFS_ACKED = "tradeoffs_acked_v1"
    }
}
