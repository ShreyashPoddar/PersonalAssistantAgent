package com.paa.assistant.services

import android.app.PendingIntent
import android.content.Intent
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.paa.assistant.ui.overlay.VoiceOverlayActivity

/**
 * PAATileService — Quick Settings Tile that appears in the Android notification shade.
 *
 * Shows a microphone icon labeled "PAA Assistant".
 * Tapping it immediately launches VoiceOverlayActivity in auto-listen mode
 * so the user can speak their command without unlocking or switching apps.
 */
class PAATileService : TileService() {

    override fun onStartListening() {
        qsTile?.apply {
            state = Tile.STATE_ACTIVE
            label = "PAA"
            contentDescription = "Open PAA Personal Assistant"
            updateTile()
        }
    }

    override fun onClick() {
        val intent = Intent(this, VoiceOverlayActivity::class.java).apply {
            putExtra("auto_listen", true)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        // startActivityAndCollapse launches the activity and folds the QS panel back
        startActivityAndCollapse(
            PendingIntent.getActivity(
                this, 0, intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        )
    }
}
