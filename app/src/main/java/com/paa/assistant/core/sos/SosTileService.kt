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
        val canLocate = androidx.core.content.ContextCompat.checkSelfPermission(this, android.Manifest.permission.ACCESS_FINE_LOCATION) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED
        if (s.contacts.isEmpty() && s.callNumber() == null || !canLocate) {
            // Not set up, or no location permission (the location service would fail): open PAA's SOS card instead
            val open = android.content.Intent(this, com.paa.assistant.ui.main.MainActivity::class.java).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            if (android.os.Build.VERSION.SDK_INT >= 34) {
                startActivityAndCollapse(android.app.PendingIntent.getActivity(this, 0, open, android.app.PendingIntent.FLAG_IMMUTABLE))
            } else @Suppress("DEPRECATION") startActivityAndCollapse(open)
            return
        }
        SosService.start(this, drill = false)
    }
}
