package com.mappo.service

import android.app.PendingIntent
import android.content.Intent
import android.graphics.drawable.Icon
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.mappo.MainActivity
import com.mappo.R
import com.mappo.service.overlay.element.ToolbarOverlayManager
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * Quick Settings tile that toggles the home toolbar overlay (OVERLAY_TOOLBAR_PLAN.md, Brick 5b) —
 * the touch reveal path that complements the gamepad Select+A summon. A tile-shown toolbar is
 * persistent (not gamepad-summoned), so it stays up until toggled off again.
 */
@AndroidEntryPoint
class ToolbarTileService : TileService() {

    @Inject lateinit var toolbarOverlayManager: ToolbarOverlayManager

    override fun onStartListening() {
        super.onStartListening()
        updateTile()
    }

    override fun onClick() {
        super.onClick()
        if (!toolbarOverlayManager.canShow()) {
            // No overlay permission yet — open the app so its permission flow can prompt.
            val intent = Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startActivityAndCollapse(
                    PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE),
                )
            } else {
                @Suppress("DEPRECATION")
                startActivityAndCollapse(intent)
            }
            return
        }
        toolbarOverlayManager.toggle()
        updateTile()
    }

    private fun updateTile() {
        val tile = qsTile ?: return
        val showing = toolbarOverlayManager.isShowing()
        tile.state = if (showing) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.label = getString(R.string.toolbar_tile_label)
        tile.icon = Icon.createWithResource(this, R.drawable.ic_toolbar_tile)
        tile.updateTile()
    }
}
