package com.mapo.service.shizuku

import android.content.ComponentName
import android.content.ServiceConnection
import android.os.IBinder
import android.util.Log
import com.mapo.di.ApplicationScope
import com.mapo.shizuku.IMapoInputService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import rikka.shizuku.Shizuku
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Single source of truth for Shizuku's coarse state ([ShizukuState]). Mapo code
 * that needs to decide whether analog modes can light up reads [isReadyFlow]
 * (cached `StateFlow<Boolean>`, no per-call binder ping).
 *
 * State is driven by three sources, in priority order:
 *
 *  1. **Listeners** — `Shizuku.OnBinderReceivedListener` and `OnBinderDeadListener`
 *     fire instantly when the Shizuku service starts/stops. `OnRequestPermissionResultListener`
 *     fires when the user accepts/denies our permission request.
 *  2. **Polling** — a 2s tick re-evaluates state whenever we're not [ShizukuState.Granted].
 *     This catches the case where the user installs Shizuku after Mapo launched (Shizuku's
 *     listeners only fire after Mapo's process has seen Shizuku at least once).
 *  3. **One-shot refresh** on construction.
 *
 * Brick A scope: state detection only. Brick B extends this class to also bind the
 * UserService and surface `service: StateFlow<IMapoInputService?>`.
 *
 * Listener lifecycle: registered once on construction; never unregistered because
 * this is a `@Singleton` that lives for the app's lifetime. If the class is ever
 * scoped narrower, add explicit cleanup.
 */
@Singleton
class ShizukuConnection @Inject constructor(
    private val facade: ShizukuFacade,
    @ApplicationScope private val scope: CoroutineScope,
) {

    private val _state = MutableStateFlow<ShizukuState>(ShizukuState.NotInstalled)
    val state: StateFlow<ShizukuState> = _state.asStateFlow()

    /**
     * Cached "is Shizuku ready to use?" flag. The Brick E inject chokepoint reads
     * `.value` directly to decide between the Shizuku path and the reflection
     * fallback — no per-call binder ping, no per-call coroutine.
     */
    val isReadyFlow: StateFlow<Boolean> = state
        .map { it is ShizukuState.Granted }
        .stateIn(scope, SharingStarted.Eagerly, false)

    /**
     * Live binder to the Shizuku UserService when bound, null otherwise. Brick B
     * scope: the binder is just an `IMapoInputService` whose methods are no-op
     * stubs. Brick C+ implement the real behavior. Brick F (`ShizukuMotionCoordinator`)
     * observes this flow + the gating predicate to decide when to call
     * `setEnumerationEnabled(true)`.
     */
    private val _service = MutableStateFlow<IMapoInputService?>(null)
    val service: StateFlow<IMapoInputService?> = _service.asStateFlow()

    private val binderReceivedListener = Shizuku.OnBinderReceivedListener {
        Log.i(TAG, "binder received")
        refresh()
    }

    private val binderDeadListener = Shizuku.OnBinderDeadListener {
        Log.i(TAG, "binder dead")
        refresh()
    }

    private val permissionResultListener = Shizuku.OnRequestPermissionResultListener { requestCode, grantResult ->
        Log.i(TAG, "permission result: code=$requestCode result=$grantResult")
        refresh()
    }

    /**
     * `true` between [bindService] and [unbindService] (intent-tracked, not
     * lifecycle-tracked). Guards against double-bind on rapid state churn and
     * lets `onServiceConnected` ignore late binder deliveries after we asked
     * Shizuku to unbind. `@Volatile` because [serviceConnection] callbacks
     * fire on a binder thread.
     */
    @Volatile
    private var bindRequested: Boolean = false

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val svc = IMapoInputService.Stub.asInterface(binder)
            if (!bindRequested) {
                // We already asked to unbind — drop this late delivery.
                Log.w(TAG, "UserService onServiceConnected after unbind; ignoring")
                return
            }
            val version = runCatching { svc?.protocolVersion }.getOrNull()
            Log.i(TAG, "UserService connected; protocolVersion=$version")
            _service.value = svc
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            Log.i(TAG, "UserService disconnected")
            _service.value = null
        }
    }

    // Cached so bind and unbind use the same args identity (Shizuku requires it).
    private val userServiceArgs: Shizuku.UserServiceArgs? by lazy {
        runCatching { facade.userServiceArgsForMapoInput() }
            .onFailure { Log.w(TAG, "userServiceArgsForMapoInput threw", it) }
            .getOrNull()
    }

    init {
        facade.addBinderReceivedListener(binderReceivedListener)
        facade.addBinderDeadListener(binderDeadListener)
        facade.addPermissionResultListener(permissionResultListener)
        refresh()
        scope.launch {
            // Background poll. Stops being load-bearing once Granted because the
            // listeners cover the granted-state lifecycle; but a fresh install of
            // Shizuku Manager after Mapo boots needs this tick to be discovered.
            while (true) {
                if (_state.value !is ShizukuState.Granted) {
                    refresh()
                }
                delay(POLL_INTERVAL_MS)
            }
        }
        // Drive UserService binding off the granted-state flow. When we move
        // into Granted we bind; when we leave we unbind. Idempotent — `bindRequested`
        // suppresses redundant calls if `state` flaps within a tick.
        scope.launch {
            state.collect { current ->
                if (current is ShizukuState.Granted) {
                    bindService()
                } else {
                    unbindService()
                }
            }
        }
    }

    /** Kick the Shizuku permission UI. Result arrives via the listener registered in [init]. */
    fun requestPermission(requestCode: Int = DEFAULT_REQUEST_CODE) {
        facade.requestPermission(requestCode)
    }

    /** Re-evaluate from current facade state. Idempotent. */
    fun refresh() {
        _state.value = computeState()
    }

    private fun bindService() {
        if (bindRequested) return
        val args = userServiceArgs ?: run {
            Log.w(TAG, "bindService skipped: no UserServiceArgs (Shizuku class unloadable?)")
            return
        }
        bindRequested = true
        Log.i(TAG, "binding UserService")
        facade.bindUserService(args, serviceConnection)
    }

    private fun unbindService() {
        if (!bindRequested) return
        val args = userServiceArgs ?: return
        bindRequested = false
        Log.i(TAG, "unbinding UserService")
        facade.unbindUserService(args, serviceConnection, remove = true)
        _service.value = null
    }

    private fun computeState(): ShizukuState {
        if (!facade.isShizukuPackageInstalled()) return ShizukuState.NotInstalled
        if (!facade.isBinderAlive()) return ShizukuState.InstalledNotRunning
        return if (facade.isPermissionGranted()) ShizukuState.Granted else ShizukuState.RunningNotGranted
    }

    companion object {
        private const val TAG = "ShizukuConnection"

        /** Arbitrary, stable code used to correlate the permission result callback. */
        const val DEFAULT_REQUEST_CODE = 17_031

        /** Poll interval while not Granted. Tuned for "user installing Shizuku in
         *  parallel" responsiveness vs. wakelock cost — 2s is the same cadence
         *  Shizuku Manager itself uses for its self-status polling. */
        private const val POLL_INTERVAL_MS: Long = 2_000L
    }
}
