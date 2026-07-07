package com.mappo.service.shizuku

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.util.Log
import com.mappo.shizuku.service.MappoInputUserService
import dagger.hilt.android.qualifiers.ApplicationContext
import rikka.shizuku.Shizuku
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Defensive wrapper around the static `rikka.shizuku.Shizuku` surface. ALL Mappo code
 * routes Shizuku calls through here for three reasons:
 *
 *  1. **Crash safety.** When the Shizuku Manager app isn't installed, the `Shizuku`
 *     class itself is still on Mappo's classpath (it's just an API jar), but the
 *     `ShizukuProvider` content-provider registration in our manifest can fail to
 *     load at first touch on some Android versions, throwing `RuntimeException`s
 *     into composables. The facade catches once and reports a safe state.
 *
 *  2. **Testability.** Tests inject a fake [ShizukuFacade] without needing the
 *     Shizuku API jar resolved on the unit-test classpath.
 *
 *  3. **Single chokepoint.** If we ever swap Shizuku for an equivalent (e.g.
 *     Sui — rooted users' alternative implementation), only this file changes.
 *
 * Stateless beyond the cached "is the Shizuku class loadable?" check. Listener
 * registration delegates to Shizuku directly — callers own listener lifecycles.
 */
@Singleton
class ShizukuFacade @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    /**
     * `true` if the user has Shizuku Manager (or a compatible implementation like Sui)
     * installed. Detected via the package manager — independent of binder state.
     */
    fun isShizukuPackageInstalled(): Boolean = SHIZUKU_PACKAGES.any { pkg ->
        try {
            context.packageManager.getPackageInfo(pkg, 0)
            true
        } catch (_: PackageManager.NameNotFoundException) {
            false
        }
    }

    /** `true` if the Shizuku service binder is alive (the user started Shizuku). */
    fun isBinderAlive(): Boolean = safe { Shizuku.pingBinder() } ?: false

    /**
     * `true` if Mappo holds the Shizuku permission. Caller should consult
     * [isBinderAlive] first — without a live binder the result is meaningless.
     */
    fun isPermissionGranted(): Boolean =
        safe { Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED } ?: false

    /** Pre-request gate: Shizuku's API can `shouldShowRequestPermissionRationale`. */
    fun shouldShowRequestRationale(): Boolean =
        safe { Shizuku.shouldShowRequestPermissionRationale() } ?: false

    /** Kick the permission UI. Result arrives on the [PermissionResultListener]. */
    fun requestPermission(requestCode: Int) {
        safe { Shizuku.requestPermission(requestCode) }
    }

    /**
     * Open Shizuku Manager in the launcher (so the user can start the service,
     * pair via wireless debugging, etc.). Returns `true` if we successfully
     * launched an intent.
     */
    fun openShizukuApp(): Boolean {
        val pm = context.packageManager
        for (pkg in SHIZUKU_PACKAGES) {
            val intent = pm.getLaunchIntentForPackage(pkg) ?: continue
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            return runCatching {
                context.startActivity(intent)
                true
            }.getOrDefault(false)
        }
        return false
    }

    /**
     * Open Shizuku's Play Store / GitHub install page. Tries Play first, falls back
     * to a generic web URL on devices without Play (common on AYN Thor / Anbernic).
     */
    fun openShizukuInstall(): Boolean {
        val playIntent = Intent(
            Intent.ACTION_VIEW,
            Uri.parse("market://details?id=$PRIMARY_PACKAGE"),
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (tryLaunch(playIntent)) return true

        val webIntent = Intent(
            Intent.ACTION_VIEW,
            Uri.parse("https://shizuku.rikka.app/download/"),
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return tryLaunch(webIntent)
    }

    fun addBinderReceivedListener(listener: Shizuku.OnBinderReceivedListener) {
        safe { Shizuku.addBinderReceivedListener(listener) }
    }

    fun removeBinderReceivedListener(listener: Shizuku.OnBinderReceivedListener) {
        safe { Shizuku.removeBinderReceivedListener(listener) }
    }

    fun addBinderDeadListener(listener: Shizuku.OnBinderDeadListener) {
        safe { Shizuku.addBinderDeadListener(listener) }
    }

    fun removeBinderDeadListener(listener: Shizuku.OnBinderDeadListener) {
        safe { Shizuku.removeBinderDeadListener(listener) }
    }

    fun addPermissionResultListener(listener: Shizuku.OnRequestPermissionResultListener) {
        safe { Shizuku.addRequestPermissionResultListener(listener) }
    }

    fun removePermissionResultListener(listener: Shizuku.OnRequestPermissionResultListener) {
        safe { Shizuku.removeRequestPermissionResultListener(listener) }
    }

    // ── UserService binding ──────────────────────────────────────────────────

    /**
     * Build the Shizuku [Shizuku.UserServiceArgs] for [MappoInputUserService].
     * Pinned `processNameSuffix` so the service shows as `com.mappo:mappo_input`
     * in `adb shell ps` — easy to spot during diagnostics. `daemon(false)`
     * because we want Shizuku to tear the process down when no client is bound;
     * leaving it daemoned would burn battery between game sessions.
     */
    fun userServiceArgsForMappoInput(): Shizuku.UserServiceArgs {
        val debuggable = (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
        return Shizuku.UserServiceArgs(
            ComponentName(context, MappoInputUserService::class.java)
        )
            .daemon(false)
            .processNameSuffix("mappo_input")
            .debuggable(debuggable)
            .version(MappoInputUserService.PROTOCOL_VERSION)
    }

    fun bindUserService(args: Shizuku.UserServiceArgs, connection: ServiceConnection) {
        safe { Shizuku.bindUserService(args, connection) }
    }

    fun unbindUserService(
        args: Shizuku.UserServiceArgs,
        connection: ServiceConnection,
        remove: Boolean,
    ) {
        safe { Shizuku.unbindUserService(args, connection, remove) }
    }

    private fun tryLaunch(intent: Intent): Boolean = runCatching {
        context.startActivity(intent)
        true
    }.getOrDefault(false)

    private inline fun <T> safe(block: () -> T): T? = try {
        block()
    } catch (t: Throwable) {
        Log.w(TAG, "Shizuku call failed: ${t.javaClass.simpleName}: ${t.message}")
        null
    }

    companion object {
        private const val TAG = "ShizukuFacade"

        /** Canonical Shizuku Manager package. */
        const val PRIMARY_PACKAGE = "moe.shizuku.privileged.api"

        /** Sui (Magisk-module variant for rooted users) ships the same API surface. */
        private const val SUI_PACKAGE = "moe.shizuku.privileged.api.sui"

        private val SHIZUKU_PACKAGES = listOf(PRIMARY_PACKAGE, SUI_PACKAGE)
    }
}
