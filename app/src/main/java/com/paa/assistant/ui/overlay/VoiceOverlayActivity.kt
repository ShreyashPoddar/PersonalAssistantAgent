package com.paa.assistant.ui.overlay

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.core.content.ContextCompat
import com.paa.assistant.ui.theme.PAATheme
import dagger.hilt.android.AndroidEntryPoint

/**
 * VoiceOverlayActivity — the translucent voice assistant drawer.
 *
 * This activity:
 *  - Has a semi-transparent, blurred background (Theme.PAA.Overlay)
 *  - Shows the animated pulsing mic interface
 *  - Is launched via Assist gesture, Quick Settings tile, Floating Pill, or WhatsApp share
 *  - Handles voice listening, document/file analysis, and smart task suggestions
 */
@AndroidEntryPoint
class VoiceOverlayActivity : ComponentActivity() {

    private val viewModel: VoiceOverlayViewModel by viewModels()

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        if (permissions[Manifest.permission.RECORD_AUDIO] == true) {
            viewModel.startListening()
        }
    }

    private fun checkAndRequestPermissions() {
        val permissions = mutableListOf<String>()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            permissions.add(Manifest.permission.RECORD_AUDIO)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                permissions.add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
        if (permissions.isNotEmpty()) {
            permissionLauncher.launch(permissions.toTypedArray())
        } else {
            viewModel.startListening()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Keep screen on while PAA is active
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        // Power-button assist from a locked, screen-off phone: show over the lock screen and
        // turn the display on. The user chose full access while locked; replies are text-only.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
            )
        }

        handleIntent(intent)

        setContent {
            PAATheme {
                VoiceOverlayScreen(
                    viewModel = viewModel,
                    onRequestMicPermission = {
                        checkAndRequestPermissions()
                    },
                    onDismiss = { finish() }
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent) {
        val sharedUri = intent.data?.toString() ?: intent.getStringExtra("shared_uri")
        val sharedText = intent.getStringExtra("shared_text")
        val suggestionText = intent.getStringExtra("suggestion_text")
        val speakText = intent.getStringExtra("speak_text")

        when {
            speakText != null -> {
                viewModel.speakText(speakText)
            }
            sharedUri != null || sharedText != null -> {
                viewModel.handleSharedContent(sharedUri, sharedText)
            }
            suggestionText != null -> {
                // Suggestions come from private chats: process on-device only
                viewModel.processCommand(suggestionText, localOnly = true)
            }
            intent.getBooleanExtra("auto_listen", true) -> {
                viewModel.followUpEnabled = true
                checkAndRequestPermissions()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        com.paa.assistant.services.WakeListenerService.pauseForPopup(this)
    }

    override fun onPause() {
        super.onPause()
        viewModel.stopListening()
        com.paa.assistant.services.WakeListenerService.resumeAfterPopup(this)
    }

    override fun onDestroy() {
        super.onDestroy()
        viewModel.release()
    }
}
