package com.mappo.data.repository

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import com.mappo.di.IoDispatcher
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Enumerates launchable apps (anything with an ACTION_MAIN/CATEGORY_LAUNCHER
 * activity) so the Auto-Switch app-picker can populate a multi-select sheet.
 *
 * One-shot per sheet open — the device list rarely changes within a single
 * picker session, and the user closes the sheet between sessions. We resolve
 * labels in the same pass off the IO dispatcher because
 * `PackageManager.getApplicationLabel` is non-trivial per package and we'd
 * otherwise hit it again from composition.
 */
@Singleton
class InstalledAppsRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) {

    data class InstalledApp(
        val packageName: String,
        val label: String,
    )

    suspend fun launchableApps(): List<InstalledApp> = withContext(ioDispatcher) {
        val pm = context.packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val resolved = pm.queryIntentActivities(intent, 0)
        // Dedup by package — multi-activity launchers (e.g., dual apps) surface
        // twice; the user picks an app, not an activity.
        resolved.asSequence()
            .map { it.activityInfo.packageName }
            .filter { it != context.packageName }
            .distinct()
            .map { pkg ->
                val label = try {
                    pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
                } catch (_: PackageManager.NameNotFoundException) {
                    pkg
                }
                InstalledApp(packageName = pkg, label = label)
            }
            .sortedBy { it.label.lowercase() }
            .toList()
    }
}
