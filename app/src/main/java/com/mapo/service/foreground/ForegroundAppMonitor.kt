package com.mapo.service.foreground

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Process-wide observable for the current foreground app's package name.
 * Written by InputAccessibilityService on TYPE_WINDOW_STATE_CHANGED, read by ProfileAutoSwitcher.
 *
 * Mapo's own package is dropped at write time so [currentPackage] reflects the most recent
 * *other* foreground app. This matters for the run-mode overlay flow on every device:
 * users tap the Quick Settings tile to mount the keyboard over a foregrounded game, then
 * may swap back to Mapo's activity to tweak something — and on dual-display devices (AYN
 * Thor) where Mapo's activity can sit on the bottom screen alongside a game on the primary
 * screen. Either way, resuming Mapo would otherwise overwrite [currentPackage] with
 * `com.mapo`, hiding the game's package from a resume-time auto-switch re-evaluation.
 */
@Singleton
class ForegroundAppMonitor @Inject constructor(
    @ApplicationContext context: Context,
) {

    private val ownPackage: String = context.packageName

    private val _currentPackage = MutableStateFlow<String?>(null)
    val currentPackage: StateFlow<String?> = _currentPackage.asStateFlow()

    fun reportForegroundPackage(pkg: String?) {
        if (pkg.isNullOrBlank()) return
        if (pkg == ownPackage) return
        if (_currentPackage.value == pkg) return
        _currentPackage.value = pkg
    }
}
