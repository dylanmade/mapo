package com.mappo.service.overlay.keyboard

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.mappo.R

/**
 * Foreground service whose only job is to keep the process priority high enough for
 * [KeyboardOverlayManager]'s overlay windows to survive activity backgrounding. Android
 * 12+ aggressively kills cached processes within seconds — without this FGS the overlay
 * vanishes the moment the launching activity is dismissed from recents.
 *
 * Brick 1 of the single-screen refactor: skeleton only — persistent low-priority
 * notification + `specialUse` foreground-service type. Brick 4 adds the
 * "Show / Hide keyboard" notification action that mirrors the QS tile.
 */
class KeyboardOverlayService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        ensureNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        Log.i(TAG, "started")
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        Log.i(TAG, "destroyed")
        super.onDestroy()
    }

    private fun ensureNotificationChannel() {
        val manager = getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            CHANNEL_NAME,
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = CHANNEL_DESCRIPTION
            setShowBadge(false)
        }
        manager.createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_keyboard_overlay)
            .setContentTitle(NOTIFICATION_TITLE)
            .setContentText(NOTIFICATION_TEXT)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }

    companion object {
        private const val TAG = "KeyboardOverlaySvc"
        private const val CHANNEL_ID = "keyboard_overlay"
        private const val CHANNEL_NAME = "Keyboard overlay"
        private const val CHANNEL_DESCRIPTION =
            "Persistent while the Mappo keyboard overlay is active."
        private const val NOTIFICATION_ID = 0x4D70 // "Mp"
        private const val NOTIFICATION_TITLE = "Mappo keyboard overlay"
        private const val NOTIFICATION_TEXT = "Tap the Mappo Quick Settings tile to hide."
    }
}
