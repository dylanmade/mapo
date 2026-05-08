package com.mapo.service.input

import com.mapo.data.model.DeviceButton
import com.mapo.data.model.RemapTarget
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Performs an input-dispatch action (or accessibility query) requested by the
 * [InputDispatcher]. Implemented by `InputAccessibilityService`; null while the service
 * isn't connected, in which case dispatcher methods are no-ops and [InputDispatcher.isReady]
 * returns false.
 */
interface InputSink {
    fun injectKey(code: String): Boolean
    fun dispatchTargetAsClick(target: RemapTarget)
    fun startMouseDrag()
    fun injectMouseMove(dx: Float, dy: Float)
    fun endMouseDrag()

    /**
     * Returns the package name of the foreground app on the device's primary (default)
     * display, or null if it can't be determined or matches Mapo itself. Implemented via
     * `getWindowsOnAllDisplays()` so it works across both screens of dual-display devices.
     */
    fun queryPrimaryDisplayForegroundPackage(): String?
}

/**
 * Hilt-managed bridge between the [com.mapo.service.InputAccessibilityService] and the
 * rest of the app. Replaces the previous static-singleton + static-mutable-field
 * coupling so the VM/overlay can be tested without touching the service class.
 *
 * - **State** (`currentMappings`, `remapEnabled`, `overlayFocused`) is published by the
 *   ViewModel/overlay and read by the service inline (e.g. in `onKeyEvent`). Read via
 *   `.value` for synchronous access on the main thread.
 * - **Actions** (key/gesture injection, drag) are forwarded to the registered
 *   [InputSink]; when the service isn't connected the calls are silent no-ops.
 */
@Singleton
class InputDispatcher @Inject constructor() {

    private val _currentMappings = MutableStateFlow<Map<DeviceButton, RemapTarget>>(emptyMap())
    val currentMappings: StateFlow<Map<DeviceButton, RemapTarget>> = _currentMappings.asStateFlow()

    private val _remapEnabled = MutableStateFlow(false)
    val remapEnabled: StateFlow<Boolean> = _remapEnabled.asStateFlow()

    private val _overlayFocused = MutableStateFlow(false)
    val overlayFocused: StateFlow<Boolean> = _overlayFocused.asStateFlow()

    /**
     * When true, the accessibility service consumes `KEYCODE_BACK` (returns true from
     * `onKeyEvent`) without dispatching it. Set by MainScreen when the user is on the
     * keyboard view with the drawer closed: `FLAG_NOT_FOCUSABLE` is on there (so unmapped
     * gamepad inputs flow to the game on the primary screen), which means the back key
     * has no focusable target and would otherwise hit the system's 5 s input-dispatch
     * timeout and ANR. Consuming it here ack's the press without acting on it — matching
     * the user-facing intent that back does nothing on the keyboard view.
     */
    private val _consumeSystemBack = MutableStateFlow(false)
    val consumeSystemBack: StateFlow<Boolean> = _consumeSystemBack.asStateFlow()

    fun setCurrentMappings(mappings: Map<DeviceButton, RemapTarget>) {
        _currentMappings.value = mappings
    }

    fun setRemapEnabled(enabled: Boolean) {
        _remapEnabled.value = enabled
    }

    fun setOverlayFocused(focused: Boolean) {
        _overlayFocused.value = focused
    }

    fun setConsumeSystemBack(consume: Boolean) {
        _consumeSystemBack.value = consume
    }

    @Volatile private var sink: InputSink? = null

    /** Service calls this in `onServiceConnected`; later VM/overlay actions reach the sink. */
    fun register(sink: InputSink) {
        this.sink = sink
    }

    /** Service calls this in `onUnbind`; subsequent action calls become no-ops. */
    fun unregister() {
        sink = null
    }

    val isReady: Boolean get() = sink != null

    fun injectKey(code: String): Boolean = sink?.injectKey(code) ?: false

    fun dispatchTargetAsClick(target: RemapTarget) {
        sink?.dispatchTargetAsClick(target)
    }

    fun startMouseDrag() {
        sink?.startMouseDrag()
    }

    fun injectMouseMove(dx: Float, dy: Float) {
        sink?.injectMouseMove(dx, dy)
    }

    fun endMouseDrag() {
        sink?.endMouseDrag()
    }

    fun queryPrimaryDisplayForegroundPackage(): String? =
        sink?.queryPrimaryDisplayForegroundPackage()
}
