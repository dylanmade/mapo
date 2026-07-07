package com.mappo.service.shizuku

import android.os.RemoteException
import android.util.Log
import com.mappo.shizuku.InjectKeyRequest
import javax.inject.Inject
import javax.inject.Singleton

/**
 * **Brick E.** Single decision point for "should this `KeyEvent` inject go
 * through the shell-uid Shizuku UserService instead of the legacy in-process
 * reflection path?"
 *
 * Pulled into its own class — rather than living as a private method on
 * `InputAccessibilityService` — because `AccessibilityService` instances are
 * a pain to construct in tests and the gate is the only piece of Brick E that
 * actually needs unit coverage. The Robolectric service infra adds noise
 * without insight; a plain `@Singleton` does not.
 *
 * **Why the `shizukuModeActive` clause and not just `isReadyFlow.value`**: the
 * Shizuku route exists to serve analog modes (which need focus-bypassed inject
 * because the source modes are firing from a non-focused context). For the
 * no-analog-mode case the in-process reflection path is equivalent in behavior
 * and one binder hop cheaper. Gating on `shizukuModeActive` keeps the Shizuku
 * route scoped to exactly the moments analog modes are live, leaving the
 * reflection path as the everyday digital-remap floor.
 */
@Singleton
class ShizukuKeyInjector @Inject constructor(
    private val shizukuConnection: ShizukuConnection,
    private val shizukuMotionCoordinator: ShizukuMotionCoordinator,
) {

    /**
     * Try to route a key inject through the Shizuku UserService.
     *
     * Returns `true` when the Shizuku path was taken (regardless of whether
     * the underlying inject's success boolean came back true or false — the
     * caller should NOT fall back, because the reflection path inside `:app`
     * would still hit the same focus problem this brick exists to bypass).
     *
     * Returns `false` in two cases, both of which mean "caller, run your
     * existing reflection fallback":
     *  - The gate said no (Shizuku not ready, mode not active, or the service
     *    binder is currently null).
     *  - The gate said yes but the binder threw [RemoteException] — Shizuku
     *    crashed mid-call. Falling back keeps digital remap alive during
     *    Shizuku flaps.
     */
    fun tryInject(keyCode: Int, action: Int, displayId: Int, eventTime: Long): Boolean {
        if (!shizukuConnection.isReadyFlow.value) return false
        if (!shizukuMotionCoordinator.shizukuModeActive.value) return false
        val service = shizukuConnection.service.value ?: return false
        return try {
            val ok = service.injectKeyEvent(
                InjectKeyRequest(
                    keyCode = keyCode,
                    action = action,
                    displayId = displayId,
                    eventTime = eventTime,
                ),
            )
            Log.d(TAG, "shizuku inject result=$ok keyCode=$keyCode displayId=$displayId")
            true
        } catch (t: Throwable) {
            // Broad catch: revocation tears down the binder before our state
            // machine sees it. Beyond RemoteException, expect SecurityException
            // / IllegalStateException on revoke-race. All are non-fatal — caller
            // falls back to the reflection inject so digital remap survives.
            Log.w(TAG, "Shizuku injectKeyEvent threw — falling back to reflection", t)
            false
        }
    }

    companion object {
        private const val TAG = "ShizukuKeyInjector"
    }
}
