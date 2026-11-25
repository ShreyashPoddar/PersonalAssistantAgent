package com.paa.assistant.core.agent

import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService

/** Quick Settings tile "PAA Lockdown": one tap → only alarms, reminders and SOS work; tap again → back to normal. */
class LockdownTileService : TileService() {
    override fun onStartListening() = refresh()

    override fun onClick() {
        Lockdown.set(this, !Lockdown.isOn(this))
        refresh()
    }

    private fun refresh() {
        qsTile?.apply {
            val on = Lockdown.isOn(this@LockdownTileService)
            state = if (on) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
            label = "PAA Lockdown"
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) subtitle = if (on) "On" else "Off"
            updateTile()
        }
    }
}
