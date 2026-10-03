package com.paa.assistant.services

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.service.voice.VoiceInteractionSession
import android.service.voice.VoiceInteractionSessionService
import android.util.Log
import com.paa.assistant.ui.overlay.VoiceOverlayActivity

/**
 * Session service referenced by voice_interaction_service.xml.
 * When the system assist gesture fires (power-button hold, corner swipe), the session
 * hands off to [VoiceOverlayActivity] — which shows over the lock screen — and then hides itself.
 */
class AssistantSessionService : VoiceInteractionSessionService() {

    override fun onNewSession(args: Bundle?): VoiceInteractionSession = AssistantSession(this)

    private class AssistantSession(context: Context) : VoiceInteractionSession(context) {
        override fun onShow(args: Bundle?, showFlags: Int) {
            super.onShow(args, showFlags)
            Log.i(TAG, "Assist session shown (flags=$showFlags)")
            val intent = Intent(context, VoiceOverlayActivity::class.java)
                .putExtra("auto_listen", true)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            try {
                startAssistantActivity(intent)
            } catch (e: Exception) {
                // Some OEM builds refuse assistant activities; start it as a normal activity instead
                Log.w(TAG, "startAssistantActivity failed (${e.javaClass.simpleName}); using startActivity")
                try { context.startActivity(intent) } catch (e2: Exception) {
                    Log.e(TAG, "Could not open voice overlay: ${e2.javaClass.simpleName}")
                }
            }
            // Hiding immediately can cancel the launch on some devices; give it a moment
            Handler(Looper.getMainLooper()).postDelayed({ hide() }, 700)
        }
    }

    private companion object {
        const val TAG = "PAA_Assist"
    }
}
