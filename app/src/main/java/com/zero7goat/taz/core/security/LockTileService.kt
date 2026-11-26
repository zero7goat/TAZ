package com.zero7goat.taz.core.security

import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.widget.Toast
import com.zero7goat.taz.core.AppContainer

/**
 * A "Lock TAZ" tile for the quick-settings panel: one tap locks TAZ from anywhere. It only locks;
 * it never opens or shows anything.
 */
class LockTileService : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        val tile = qsTile ?: return
        val lock = AppContainer.lockManager(applicationContext)
        tile.state = if (lock.hasPasscode.value && lock.state.value == LockState.Unlocked) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.updateTile()
    }

    override fun onClick() {
        super.onClick()
        val lock = AppContainer.lockManager(applicationContext)
        if (!lock.hasPasscode.value) {
            Toast.makeText(applicationContext, "Turn on the app lock in TAZ first", Toast.LENGTH_SHORT).show()
            return
        }
        lock.lockNow()
        qsTile?.let {
            it.state = Tile.STATE_INACTIVE
            it.updateTile()
        }
        Toast.makeText(applicationContext, "TAZ is locked", Toast.LENGTH_SHORT).show()
    }
}
