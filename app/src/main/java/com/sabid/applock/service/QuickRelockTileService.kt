package com.sabid.applock.service

import android.service.quicksettings.Tile
import android.service.quicksettings.TileService

/** Quick Settings tile: relock every app right now, including timed unlocks. */
class QuickRelockTileService : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        qsTile?.apply {
            state = Tile.STATE_INACTIVE
            label = "Relock all"
            runCatching { updateTile() }
        }
    }

    override fun onClick() {
        super.onClick()
        LockSession.revokeAll()
    }
}
