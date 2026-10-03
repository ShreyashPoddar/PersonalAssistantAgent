package com.paa.assistant.services

import android.service.voice.VoiceInteractionService

/**
 * AssistantVoiceService — registers PAA as a system-level voice interaction service.
 *
 * When declared in AndroidManifest.xml with the ROLE_ASSIST permission,
 * this allows PAA to be set as the Default Digital Assistant under:
 *   Android Settings → Apps → Default apps → Digital assistant app
 *
 * Once set:
 *  - Long-pressing the hardware power button triggers PAA
 *  - Swiping from the bottom corner of the screen triggers PAA
 *  - The Assist gesture anywhere in the OS opens VoiceOverlayActivity
 */
class AssistantVoiceService : VoiceInteractionService() {

    override fun onReady() {
        super.onReady()
        // Service is ready — PAA is now registered as the system assistant
    }
}
