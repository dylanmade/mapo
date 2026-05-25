package com.mapo.service.input.capture

import android.util.Log
import com.mapo.data.model.AppProfileBinding
import com.mapo.data.model.Profile
import com.mapo.data.repository.AppProfileBindingRepository
import com.mapo.data.repository.ProfileRepository
import com.mapo.di.ApplicationScope
import com.mapo.service.foreground.ForegroundAppMonitor
import com.mapo.service.input.CompiledConfig
import com.mapo.service.input.InputDispatcher
import com.mapo.service.input.InputEvaluator
import com.mapo.service.input.modes.requiresMotionCapture
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Brick 4: gating predicate for the production motion-capture overlay.
 *
 * Attaches [MotionCaptureOverlayManager] if and only if:
 *  - The foreground app is bound to the **currently active** profile, AND
 *  - The active action set (with overlaying layers applied) has at least one
 *    [InputSource][com.mapo.data.model.steam.InputSource] whose mode requires
 *    motion capture (per [requiresMotionCapture]).
 *
 * Effect: side effects of the focused overlay (IME / back / app-switcher
 * gestures suspended) are scoped to "the user is in a game they opted into
 * analog modes for." Outside that window — Mapo itself, the launcher,
 * non-gaming apps, or a profile that hasn't enabled any analog modes — the
 * overlay stays detached and Android navigation works normally.
 *
 * **Lifecycle.** Started from `InputAccessibilityService.onServiceConnected`
 * (so the coordinator runs as long as the accessibility service is connected),
 * stopped from `onUnbind`. Uses the application-scoped supervisor so coroutine
 * collection survives Mapo's activity backgrounding (the common case during
 * gameplay).
 *
 * **Set-switch handling.** When the foreground app changes, [ProfileAutoSwitcher]
 * may swap the active profile; the coordinator re-evaluates on every input
 * change. On profile switch, [InputEvaluator.flushAnalog] is called before the
 * recompute so any in-flight analog state from the prior set is released
 * cleanly (today a no-op — Brick 5+ fill in the body per analog mode).
 */
@Singleton
class MotionCaptureCoordinator @Inject constructor(
    private val foregroundAppMonitor: ForegroundAppMonitor,
    private val profileRepository: ProfileRepository,
    private val appProfileBindingRepository: AppProfileBindingRepository,
    private val inputDispatcher: InputDispatcher,
    private val inputEvaluator: InputEvaluator,
    private val overlayManager: MotionCaptureOverlayManager,
    @ApplicationScope private val applicationScope: CoroutineScope,
) {

    @Volatile
    private var collectionJob: Job? = null

    @Volatile
    private var lastActiveProfileId: Long? = null

    private val _shizukuModeActive = MutableStateFlow(false)
    /**
     * **Brick E.** True whenever the gating predicate currently says "an analog
     * mode is in play for the foreground app" — same signal that drives the
     * focused overlay's attach/detach today. The Shizuku inject gate
     * ([com.mapo.service.shizuku.ShizukuKeyInjector]) reads this so the Shizuku
     * path is taken only while motion-capture is in use (i.e., the moments
     * where the legacy focused overlay would have stolen focus and necessitated
     * the inject-time detach dance).
     *
     * Brick F renames this coordinator and pivots the body to drive the
     * Shizuku UserService bind/unbind off the same predicate. Until then, this
     * flag is a thin re-export of the existing `shouldAttach` decision.
     */
    val shizukuModeActive: StateFlow<Boolean> = _shizukuModeActive.asStateFlow()

    /**
     * Begin observing the gating predicate. Idempotent — re-starting an
     * already-running coordinator is a no-op. Safe to call from any thread.
     */
    fun start() {
        if (collectionJob?.isActive == true) {
            Log.d(TAG, "start: already running")
            return
        }
        Log.i(TAG, "start: subscribing to predicate inputs")
        // `combine` only has named-lambda overloads up to 5 flows; for 6+ we use
        // the vararg form and unpack by index. The casts are local and the
        // surrounding fields keep their concrete types.
        collectionJob = applicationScope.launch {
            combine(
                foregroundAppMonitor.currentPackage,
                profileRepository.activeProfile,
                appProfileBindingRepository.getAll(),
                inputDispatcher.compiledConfig,
                inputEvaluator.activeSetIdFlow,
                inputEvaluator.activeLayerIdsFlow,
            ) { values ->
                @Suppress("UNCHECKED_CAST")
                val foregroundPkg = values[0] as String?
                @Suppress("UNCHECKED_CAST")
                val activeProfile = values[1] as Profile?
                @Suppress("UNCHECKED_CAST")
                val bindings = values[2] as List<AppProfileBinding>
                val compiled = values[3] as CompiledConfig
                val activeSetId = values[4] as Long
                @Suppress("UNCHECKED_CAST")
                val activeLayers = values[5] as List<Long>

                val activeProfileId = activeProfile?.id
                // Flush any analog state when the active profile switches —
                // synthetic dpad edges from the prior set must not leak into
                // the new one. Today this is a no-op (Brick 5+ fill the body).
                if (activeProfileId != lastActiveProfileId) {
                    Log.d(TAG, "active profile switched $lastActiveProfileId → $activeProfileId; flushing analog state")
                    inputEvaluator.flushAnalog()
                    lastActiveProfileId = activeProfileId
                }
                evaluatePredicate(
                    foregroundPkg = foregroundPkg,
                    activeProfileId = activeProfileId,
                    bindings = bindings.asSequence()
                        .filter { it.profileId == activeProfileId }
                        .mapTo(mutableSetOf()) { it.packageName },
                    compiled = compiled,
                    activeSetId = activeSetId,
                    activeLayers = activeLayers,
                )
            }
                .distinctUntilChanged()
                .onEach { decision -> applyDecision(decision) }
                .collect { /* drain — apply is in onEach */ }
        }
    }

    fun stop() {
        collectionJob?.cancel()
        collectionJob = null
        overlayManager.detach()
        _shizukuModeActive.value = false
        Log.i(TAG, "stop: subscriptions cancelled, overlay detached")
    }

    private fun applyDecision(shouldAttach: Boolean) {
        Log.d(TAG, "decision: shouldAttach=$shouldAttach (currentlyAttached=${overlayManager.isAttached.value})")
        if (shouldAttach) overlayManager.attach() else overlayManager.detach()
        _shizukuModeActive.value = shouldAttach
    }

    /**
     * Pure predicate: given a snapshot of every input, return whether the
     * overlay should be attached. Extracted from the [combine] lambda so the
     * coordinator's tests can poke the truth table directly without staging
     * coroutine flows.
     */
    internal fun evaluatePredicate(
        foregroundPkg: String?,
        activeProfileId: Long?,
        bindings: Set<String>,
        compiled: CompiledConfig,
        activeSetId: Long,
        activeLayers: List<Long>,
    ): Boolean {
        if (foregroundPkg == null || activeProfileId == null) return false
        if (foregroundPkg !in bindings) return false
        // Resolve the set the same way `InputEvaluator.resolveActiveSet` does:
        // activeSetId of 0L means "lazy-uninit — use compiled.startingActionSetId."
        val resolvedSetId = if (activeSetId == 0L) compiled.startingActionSetId else activeSetId
        val set = compiled.sets[resolvedSetId] ?: return false
        // Base set analog mode?
        if (set.inputs.values.any { it.mode.requiresMotionCapture() }) return true
        // Any active layer overlay declaring an analog mode?
        for (layerId in activeLayers) {
            val layer = set.layers[layerId] ?: continue
            if (layer.inputs.values.any { it.mode.requiresMotionCapture() }) return true
        }
        return false
    }

    companion object {
        private const val TAG = "MotionCaptureCoord"
    }
}
