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
 * Persistent settings for the auto-switch feature. The primary toggle gates both
 * profile auto-switching and the create-profile-for-unbound-app prompt. The
 * auto-create toggle, when enabled, replaces that prompt with silent
 * create-and-bind for any package not on the blocklist.
 */
@Singleton
class AutoSwitchSettings @Inject constructor(
    @ApplicationContext context: Context
) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _autoSwitchEnabled = MutableStateFlow(
        prefs.getBoolean(KEY_AUTO_SWITCH_ENABLED, false)
    )
    val autoSwitchEnabled: StateFlow<Boolean> = _autoSwitchEnabled.asStateFlow()

    private val _autoCreateProfilesEnabled = MutableStateFlow(
        prefs.getBoolean(KEY_AUTO_CREATE_PROFILES_ENABLED, false)
    )
    val autoCreateProfilesEnabled: StateFlow<Boolean> = _autoCreateProfilesEnabled.asStateFlow()

    private val _ignoredPackages = MutableStateFlow(
        prefs.getStringSet(KEY_IGNORED_PACKAGES, emptySet())?.toSet() ?: emptySet()
    )
    val ignoredPackages: StateFlow<Set<String>> = _ignoredPackages.asStateFlow()

    private val listener = SharedPreferences.OnSharedPreferenceChangeListener { sp, key ->
        when (key) {
            KEY_AUTO_SWITCH_ENABLED ->
                _autoSwitchEnabled.value = sp.getBoolean(KEY_AUTO_SWITCH_ENABLED, false)
            KEY_AUTO_CREATE_PROFILES_ENABLED ->
                _autoCreateProfilesEnabled.value =
                    sp.getBoolean(KEY_AUTO_CREATE_PROFILES_ENABLED, false)
            KEY_IGNORED_PACKAGES ->
                _ignoredPackages.value =
                    sp.getStringSet(KEY_IGNORED_PACKAGES, emptySet())?.toSet() ?: emptySet()
        }
    }

    init {
        seedDefaultBlocklistIfNeeded()
        prefs.registerOnSharedPreferenceChangeListener(listener)
    }

    /**
     * On first launch, union the default launcher set into the blocklist. The seeded flag is
     * sticky: if the user later removes a seeded entry it stays removed across re-installs of
     * the same data dir / future settings re-instantiations. (A new seeded-flag key with a
     * different version suffix would let us add more defaults later without resurrecting
     * user-removed entries.)
     */
    private fun seedDefaultBlocklistIfNeeded() {
        if (prefs.getBoolean(KEY_BLOCKLIST_SEEDED, false)) return
        val seeded = _ignoredPackages.value + DEFAULT_BLOCKED_LAUNCHERS
        prefs.edit()
            .putStringSet(KEY_IGNORED_PACKAGES, seeded)
            .putBoolean(KEY_BLOCKLIST_SEEDED, true)
            .apply()
        _ignoredPackages.value = seeded
    }

    fun setAutoSwitchEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_AUTO_SWITCH_ENABLED, enabled).apply()
    }

    fun setAutoCreateProfilesEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_AUTO_CREATE_PROFILES_ENABLED, enabled).apply()
    }

    fun addIgnoredPackage(pkg: String) {
        val updated = _ignoredPackages.value + pkg
        prefs.edit().putStringSet(KEY_IGNORED_PACKAGES, updated).apply()
    }

    fun removeIgnoredPackage(pkg: String) {
        val updated = _ignoredPackages.value - pkg
        prefs.edit().putStringSet(KEY_IGNORED_PACKAGES, updated).apply()
    }

    companion object {
        private const val PREFS_NAME = "mapo_settings"
        private const val KEY_AUTO_SWITCH_ENABLED = "auto_switch_enabled"
        private const val KEY_AUTO_CREATE_PROFILES_ENABLED = "auto_create_profiles_enabled"
        private const val KEY_IGNORED_PACKAGES = "ignored_packages"
        private const val KEY_BLOCKLIST_SEEDED = "blocklist_seeded_v1"

        /**
         * Pre-populated blocklist of stock OEM and popular custom launcher packages.
         * `ForegroundAppFilter` already excludes the *resolved default* launcher, but on
         * dual-display devices the launcher can briefly grab focus during app-switch
         * animations on the primary screen, slipping past that filter; the blocklist
         * acts as a final defense so we never prompt to bind a profile to one.
         */
        private val DEFAULT_BLOCKED_LAUNCHERS: Set<String> = setOf(
            // Google / AOSP
            "com.android.launcher3",
            "com.google.android.apps.nexuslauncher",
            // OEMs
            "com.sec.android.app.launcher",      // Samsung One UI Home
            "com.miui.home",                     // Xiaomi MIUI
            "com.huawei.android.launcher",       // Huawei EMUI
            "com.oneplus.launcher",              // OnePlus
            "com.oppo.launcher",                 // Oppo / ColorOS
            "com.realme.launcher",               // Realme
            "com.vivo.launcher",                 // Vivo
            "com.asus.launcher",                 // Asus
            "com.lge.launcher2",                 // LG (older)
            "com.lge.launcher3",                 // LG (newer)
            "com.motorola.launcher3",            // Motorola
            "com.sonyericsson.home",             // Sony
            "com.transsion.hilauncher",          // Tecno / Infinix
            // Popular replacements
            "org.lineageos.trebuchet",           // LineageOS Trebuchet
            "com.teslacoilsw.launcher",          // Nova
            "com.microsoft.launcher",            // Microsoft
            "ginlemon.flowerfree",               // Smart Launcher (free)
            "ginlemon.flowerpro",                // Smart Launcher (pro)
            "com.actionlauncher.playstore",      // Action Launcher
            "is.shortcut",                       // Niagara
            "app.lawnchair",                     // Lawnchair
        )
    }
}
