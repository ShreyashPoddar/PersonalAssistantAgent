package com.paa.assistant.core.sos

import android.service.quicksettings.Tile
import android.service.quicksettings.TileService

/** Quick Settings tile "PAA SOS": starts the SOS countdown (Cancel in the notification). */
class SosTileService : TileService() {
    override fun onStartListening() {
        qsTile?.apply { state = Tile.STATE_INACTIVE; label = "PAA SOS"; updateTile() }
    }

    override fun onClick() {
        val s = SosSettings(this)
        if (s.contacts.isEmpty() && s.callNumber() == null) return
        SosService.start(this, drill = false)
    }
}
