package com.sabid.applock.service

import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.sabid.applock.AppLockApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Quick Settings tile: arm / disarm protection without opening the app. */
class QuickLockTileService : TileService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onStartListening() {
        super.onStartListening()
        // Cached snapshot, not runBlocking: onStartListening runs on the main thread and
        // a first-access DataStore read there stalls the whole shade.
        render(AppLockApp.get().snapshot.protectionEnabled)
    }

    override fun onClick() {
        super.onClick()
        val app = AppLockApp.get()
        scope.launch {
            val next = !app.settings.current().protectionEnabled
            app.settings.setProtection(next)
            if (next) LockGuardService.start(this@QuickLockTileService) else LockSession.revokeAll()
            withContext(Dispatchers.Main) { render(next) }
            LockWidgetProvider.refreshAll(this@QuickLockTileService)
        }
    }

    /** Must run on the main thread — Tile is not thread-safe. */
    private fun render(enabled: Boolean) {
        qsTile?.apply {
            state = if (enabled) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
            subtitle = if (enabled) "Protected" else "Off"
            runCatching { updateTile() }
        }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }
}
