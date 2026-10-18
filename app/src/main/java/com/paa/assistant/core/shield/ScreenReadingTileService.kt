package com.paa.assistant.core.shield

import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.provider.Settings
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService

/** Quick Settings tile "PAA screen reading": one tap turns PAA's screen reading off before banking, and on after. */
class ScreenReadingTileService : TileService() {

    override fun onStartListening() = refresh()

    override fun onClick() {
        if (A11yToggle.setEnabled(this, !A11yToggle.isEnabled(this))) return refresh()
        // No permission to flip it: open Accessibility settings for a manual flip.
        val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startActivityAndCollapse(PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE))
        } else {
            @Suppress("DEPRECATION") startActivityAndCollapse(intent)
        }
    }

    private fun refresh() {
        qsTile?.apply {
            val on = A11yToggle.isEnabled(this@ScreenReadingTileService)
            state = if (on) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
            label = "PAA screen reading"
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) subtitle = if (on) "On" else "Off"
            updateTile()
        }
    }
}
