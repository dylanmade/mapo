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
 * Persistent settings for the auto-switch feature. Single toggle gates both
 * profile auto-switching and the create-profile-for-unbound-app prompt.
 */
@Singleton
class AutoSwitchSettings @Inject constructor(
    @ApplicationContext context: Context
) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _autoSwitchEnabled = MutableStateFlow(
        prefs.getBoolean(KEY_AUTO_SWITCH_ENABLED, true)
    )
    val autoSwitchEnabled: StateFlow<Boolean> = _autoSwitchEnabled.asStateFlow()

    private val listener = SharedPreferences.OnSharedPreferenceChangeListener { sp, key ->
        if (key == KEY_AUTO_SWITCH_ENABLED) {
            _autoSwitchEnabled.value = sp.getBoolean(KEY_AUTO_SWITCH_ENABLED, true)
        }
    }

    init {
        prefs.registerOnSharedPreferenceChangeListener(listener)
    }

    fun setAutoSwitchEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_AUTO_SWITCH_ENABLED, enabled).apply()
    }

    companion object {
        private const val PREFS_NAME = "mapo_settings"
        private const val KEY_AUTO_SWITCH_ENABLED = "auto_switch_enabled"
    }
}
