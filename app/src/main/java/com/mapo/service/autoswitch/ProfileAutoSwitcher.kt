package com.mapo.service.autoswitch

import android.util.Log
import com.mapo.data.repository.AppProfileBindingRepository
import com.mapo.data.repository.ProfileRepository
import com.mapo.data.settings.AutoSwitchSettings
import com.mapo.service.foreground.ForegroundAppFilter
import com.mapo.service.foreground.ForegroundAppMonitor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Listens to foreground-app changes and auto-switches the active profile when a binding
 * exists. Emits UI events for both the switched-profile case and the create-profile prompt.
 */
@Singleton
class ProfileAutoSwitcher @Inject constructor(
    private val foregroundAppMonitor: ForegroundAppMonitor,
    private val bindingRepo: AppProfileBindingRepository,
    private val profileRepo: ProfileRepository,
    private val settings: AutoSwitchSettings,
    private val filter: ForegroundAppFilter
) {

    sealed class UiEvent {
        data class Switched(val pkg: String, val appLabel: String, val profileName: String) : UiEvent()
        data class PromptCreate(val pkg: String, val appLabel: String) : UiEvent()
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _events = MutableSharedFlow<UiEvent>(extraBufferCapacity = 8)
    val events: SharedFlow<UiEvent> = _events.asSharedFlow()

    private val lastPromptedAt: MutableMap<String, Long> = mutableMapOf()

    @Volatile private var startJob: Job? = null

    fun start() {
        if (startJob != null) return
        startJob = scope.launch {
            foregroundAppMonitor.currentPackage
                .filterNotNull()
                .distinctUntilChanged()
                .collect { pkg -> handleForegroundChange(pkg) }
        }
        Log.i(TAG, "ProfileAutoSwitcher started")
    }

    private suspend fun handleForegroundChange(pkg: String) {
        if (!settings.autoSwitchEnabled.value) return
        if (!filter.isInteresting(pkg)) return

        val binding = bindingRepo.getForPackageOnce(pkg)
        if (binding != null) {
            val current = profileRepo.activeProfile.value
            if (current?.id == binding.profileId) {
                Log.d(TAG, "binding for $pkg already matches active profile; no switch")
                return
            }
            val switched = profileRepo.setActiveProfileById(binding.profileId)
            if (switched != null) {
                Log.i(TAG, "auto-switched profile to '${switched.name}' for $pkg")
                _events.tryEmit(UiEvent.Switched(pkg, filter.appLabel(pkg), switched.name))
            } else {
                Log.w(TAG, "binding for $pkg references missing profile id=${binding.profileId}")
            }
            return
        }

        val now = System.currentTimeMillis()
        val last = lastPromptedAt[pkg] ?: 0L
        if (now - last < PROMPT_THROTTLE_MS) {
            Log.d(TAG, "prompt for $pkg throttled (${now - last}ms since last)")
            return
        }
        lastPromptedAt[pkg] = now
        Log.d(TAG, "no binding for $pkg → emitting create-profile prompt")
        _events.tryEmit(UiEvent.PromptCreate(pkg, filter.appLabel(pkg)))
    }

    suspend fun createProfileAndBind(pkg: String, appLabel: String) {
        val newId = profileRepo.addProfile(appLabel)
        bindingRepo.bind(packageName = pkg, profileId = newId)
        profileRepo.setActiveProfileById(newId)
        Log.i(TAG, "created profile '$appLabel' (id=$newId) bound to $pkg")
    }

    companion object {
        private const val TAG = "ProfileAutoSwitcher"
        private const val PROMPT_THROTTLE_MS = 60_000L
    }
}
