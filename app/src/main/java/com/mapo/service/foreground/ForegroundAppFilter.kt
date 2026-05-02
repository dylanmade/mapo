package com.mapo.service.foreground

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.util.Log
import android.view.inputmethod.InputMethodManager
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Decides whether a foreground package is "interesting" enough to trigger auto-switch
 * or a create-profile prompt. Excludes Mapo itself, the system launcher, system UI,
 * and any enabled IME. Also resolves a human-readable label for a package.
 */
@Singleton
class ForegroundAppFilter @Inject constructor(
    @ApplicationContext private val context: Context
) {

    private val staticExclusions: Set<String> = setOf(
        "android",
        "com.android.systemui",
        "com.android.systemui.recents",
        context.packageName
    )

    @Volatile private var cachedLauncherPackage: String? = null
    @Volatile private var cachedImePackages: Set<String> = emptySet()
    @Volatile private var imeCacheStamp: Long = 0L

    fun isInteresting(pkg: String): Boolean {
        if (pkg.isBlank()) return false
        if (pkg in staticExclusions) return false
        if (pkg == launcherPackage()) return false
        if (pkg in imePackages()) return false
        return true
    }

    fun appLabel(pkg: String): String = try {
        val pm = context.packageManager
        pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
    } catch (_: PackageManager.NameNotFoundException) {
        pkg
    }

    private fun launcherPackage(): String? {
        cachedLauncherPackage?.let { return it }
        return try {
            val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
            val resolved = context.packageManager
                .resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY)
                ?.activityInfo?.packageName
            cachedLauncherPackage = resolved
            resolved
        } catch (e: Exception) {
            Log.w(TAG, "launcher resolution failed", e)
            null
        }
    }

    private fun imePackages(): Set<String> {
        val now = System.currentTimeMillis()
        if (now - imeCacheStamp < IME_CACHE_TTL_MS) return cachedImePackages
        return try {
            val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            val pkgs = imm.enabledInputMethodList.map { it.packageName }.toSet()
            cachedImePackages = pkgs
            imeCacheStamp = now
            pkgs
        } catch (e: Exception) {
            Log.w(TAG, "ime list resolution failed", e)
            cachedImePackages
        }
    }

    companion object {
        private const val TAG = "ForegroundAppFilter"
        private const val IME_CACHE_TTL_MS = 60_000L
    }
}
