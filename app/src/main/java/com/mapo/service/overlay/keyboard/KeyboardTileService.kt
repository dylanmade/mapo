package com.mapo.service.overlay.keyboard

import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.util.Log
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * Quick Settings tile that toggles the run-mode virtual-keyboard overlay. Tap the
 * tile → [KeyboardOverlayPresenter.toggle] flips the overlay on or off; tile state
 * mirrors `presenter.isShowing()`.
 *
 * State sync limits: tile state refreshes on [onStartListening] (whenever the user
 * pulls down the shade with our tile visible) and after every tap. We do **not**
 * push state changes when the overlay is toggled from inside Mapo (e.g. drawer
 * item) — the system would deliver them lazily anyway. If staleness becomes a
 * problem we can hook the presenter to call [TileService.requestListeningState].
 */
@AndroidEntryPoint
class KeyboardTileService : TileService() {

    @Inject lateinit var presenter: KeyboardOverlayPresenter

    override fun onStartListening() {
        super.onStartListening()
        syncTileState()
    }

    override fun onClick() {
        super.onClick()
        Log.i(TAG, "tile tapped — toggling overlay (was showing=${presenter.isShowing()})")
        presenter.toggle()
        syncTileState()
    }

    private fun syncTileState() {
        val tile = qsTile ?: return
        tile.state = if (presenter.isShowing()) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.updateTile()
    }

    companion object {
        private const val TAG = "KeyboardTile"
    }
}
