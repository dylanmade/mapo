package com.mapo.service.foreground

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Process-wide observable for the current foreground app's package name.
 * Written by InputAccessibilityService on TYPE_WINDOW_STATE_CHANGED, read by ProfileAutoSwitcher.
 */
@Singleton
class ForegroundAppMonitor @Inject constructor() {

    private val _currentPackage = MutableStateFlow<String?>(null)
    val currentPackage: StateFlow<String?> = _currentPackage.asStateFlow()

    fun reportForegroundPackage(pkg: String?) {
        if (pkg.isNullOrBlank()) return
        if (_currentPackage.value == pkg) return
        _currentPackage.value = pkg
    }
}
