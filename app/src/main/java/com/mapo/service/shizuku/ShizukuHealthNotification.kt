package com.mapo.service.shizuku

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.annotation.VisibleForTesting
import androidx.core.app.NotificationCompat
import com.mapo.MainActivity
import com.mapo.R
import com.mapo.di.ApplicationScope
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * **Brick G.** Persistent reminder notification surfaced when the user has an
 * analog-mode binding configured for the foregrounded app but Shizuku isn't
 * connected — i.e. analog input is silently inert.
 *
 * **Show / hide condition.** `coordinator.analogModeWanted && !shizukuReady`.
 * The toast from [ShizukuMotionCoordinator] fires the moment of transition;
 * this notification is the durable surface that survives after the user
 * dismisses the toast or never saw it (status bar hidden in-game).
 *
 * **Channel.** Importance DEFAULT — visible in the status bar, no heads-up
 * interruption. Memory `project_shizuku_brick_e_landed.md` and Brick F's plan
 * note: most games hide the status bar mid-play, so the toast is the in-game
 * signal; this notification is the post-game / next-launch reminder.
 *
 * **Tap action.** Opens [MainActivity]; the drawer entry for Shizuku setup is
 * one tap away. Deep-linking directly to [com.mapo.ui.screen.ShizukuSetupScreen]
 * via the Compose nav graph is a polish follow-up — requires intent-extra
 * round-tripping through `MainScreen`'s NavController, more plumbing than the
 * incremental UX gain warrants for this brick.
 *
 * **Lifecycle.** [start] launches a single collector in the application scope;
 * survives activity backgrounding (the common case during gameplay). Idempotent
 * — re-calling [start] is a no-op.
 */
@Singleton
class ShizukuHealthNotification @Inject constructor(
    @ApplicationContext private val context: Context,
    private val shizukuConnection: ShizukuConnection,
    private val coordinator: ShizukuMotionCoordinator,
    @ApplicationScope private val applicationScope: CoroutineScope,
) {

    @Volatile
    private var collectionJob: Job? = null

    fun start() {
        if (collectionJob?.isActive == true) {
            Log.d(TAG, "start: already running")
            return
        }
        ensureChannel()
        Log.i(TAG, "start: subscribing to predicate inputs")
        collectionJob = applicationScope.launch {
            try {
                combine(
                    coordinator.analogModeWanted,
                    shizukuConnection.isReadyFlow,
                ) { wanted, ready -> shouldShow(wanted = wanted, shizukuReady = ready) }
                    .distinctUntilChanged()
                    .collect { showNotification ->
                        if (showNotification) post() else cancel()
                    }
            } catch (t: Throwable) {
                // Top-level guard so a NotificationManager throw or downstream
                // crash doesn't propagate to the global uncaught-exception
                // handler (Brick G revocation-race follow-up 2026-05-24).
                Log.e(TAG, "health-notification combine loop crashed", t)
            }
        }
    }

    fun stop() {
        collectionJob?.cancel()
        collectionJob = null
        cancel()
    }

    /**
     * Decision helper — pure given inputs so it's unit-testable without
     * staging coroutine flows.
     *
     * `wanted` is `coordinator.analogModeWanted` (foreground app bound +
     * analog mode in scope, **excluding** the Shizuku-ready clause). The
     * notification shows iff there's an analog binding the user is counting
     * on AND Shizuku isn't there to service it.
     */
    @VisibleForTesting
    internal fun shouldShow(wanted: Boolean, shizukuReady: Boolean): Boolean =
        wanted && !shizukuReady

    private fun ensureChannel() {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            context.getString(R.string.shizuku_health_channel_name),
            NotificationManager.IMPORTANCE_DEFAULT,
        ).apply {
            description = context.getString(R.string.shizuku_health_channel_description)
            setShowBadge(false)
        }
        manager.createNotificationChannel(channel)
    }

    private fun post() {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_keyboard_overlay)
            .setContentTitle(context.getString(R.string.shizuku_health_notification_title))
            .setContentText(context.getString(R.string.shizuku_health_notification_text))
            .setContentIntent(buildContentIntent())
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .build()
        try {
            manager.notify(NOTIFICATION_ID, notification)
            Log.i(TAG, "notification posted")
        } catch (t: Throwable) {
            // POST_NOTIFICATIONS may not be granted on API 33+. Silent no-op
            // is the right degraded behavior — the user will see Mapo's other
            // surfaces (Setup screen, RemapControls dialog) without this.
            Log.w(TAG, "notification post failed (POST_NOTIFICATIONS may be missing)", t)
        }
    }

    private fun cancel() {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        manager.cancel(NOTIFICATION_ID)
        Log.i(TAG, "notification cancelled")
    }

    private fun buildContentIntent(): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        // FLAG_IMMUTABLE: required on API 31+; we don't mutate the intent
        // post-creation either, so this is the strictly-correct flag.
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        return PendingIntent.getActivity(context, 0, intent, flags)
    }

    companion object {
        private const val TAG = "ShizukuHealthNotif"
        private const val CHANNEL_ID = "shizuku_health"
        private const val NOTIFICATION_ID = 0x5A48 // "ZH" — Shizuku Health
    }
}
