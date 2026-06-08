package com.mapo.service.overlay.element

import android.util.Log
import com.mapo.data.model.OverlayElement
import com.mapo.data.model.OverlayGesture
import com.mapo.data.model.targetFor
import com.mapo.data.repository.OverlayRepository
import com.mapo.data.repository.ProfileRepository
import com.mapo.service.overlay.keyboard.KeyboardDisplayRouter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Single coordination point for "show / hide / toggle the rebuilt button overlay"
 * (see `OVERLAY_REBUILD_PLAN.md`, Brick B). Sibling of `KeyboardOverlayPresenter` — the
 * new overlay is independent of the legacy keyboard runtime.
 *
 * Holds the live/not-live flag and, while live, collects the active profile's
 * [OverlayElement]s and drives [OverlayElementWindowManager.render]. Taps dispatch
 * through [OverlayTargetDispatcher]; an un-connected accessibility sink is surfaced via
 * [errorMessages] (relayed by `MainViewModel` into its toast stream, like the keyboard
 * controller).
 */
@OptIn(ExperimentalCoroutinesApi::class)
@Singleton
class OverlayPresenter @Inject constructor(
    private val manager: OverlayElementWindowManager,
    private val overlayRepository: OverlayRepository,
    private val profileRepository: ProfileRepository,
    private val dispatcher: OverlayTargetDispatcher,
    private val displayRouter: KeyboardDisplayRouter,
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var collectJob: Job? = null

    private val _showing = MutableStateFlow(false)
    val showing: StateFlow<Boolean> = _showing.asStateFlow()

    private val _errorMessages = MutableSharedFlow<String>(
        replay = 0,
        extraBufferCapacity = 4,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val errorMessages: SharedFlow<String> = _errorMessages.asSharedFlow()

    fun isShowing(): Boolean = _showing.value

    fun show() {
        if (_showing.value) return
        if (!manager.canShow()) {
            Log.w(TAG, "show() skipped: overlay permission not granted")
            _errorMessages.tryEmit(ERR_NO_OVERLAY_PERMISSION)
            return
        }
        _showing.value = true
        // Re-renders whenever the active profile's elements change, so edits made in the
        // editor reflect on the live overlay immediately.
        collectJob = scope.launch {
            profileRepository.activeProfile
                .filterNotNull()
                .flatMapLatest { overlayRepository.elementsByProfile(it.id) }
                .collect { elements ->
                    val displayId = displayRouter.routeOverlay(OVERLAY_ID).first()
                    manager.render(elements, displayId, ::onGesture)
                }
        }
    }

    fun hide() {
        collectJob?.cancel()
        collectJob = null
        manager.detachAll()
        _showing.value = false
    }

    fun toggle() {
        if (isShowing()) hide() else show()
    }

    private fun onGesture(element: OverlayElement, gesture: OverlayGesture) {
        if (!dispatcher.dispatch(element.targetFor(gesture))) {
            _errorMessages.tryEmit(ERR_SERVICE_NOT_RUNNING)
        }
    }

    companion object {
        private const val TAG = "OverlayPresenter"
        const val OVERLAY_ID = "overlay_elements_main"
        private const val ERR_SERVICE_NOT_RUNNING = "Accessibility service not running"
        private const val ERR_NO_OVERLAY_PERMISSION = "Grant the display-over-other-apps permission first"
    }
}
