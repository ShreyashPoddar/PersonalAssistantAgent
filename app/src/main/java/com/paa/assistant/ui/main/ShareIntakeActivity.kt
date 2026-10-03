package com.paa.assistant.ui.main

import android.content.ClipData
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import com.paa.assistant.ui.overlay.VoiceOverlayActivity
import dagger.hilt.android.AndroidEntryPoint

/**
 * ShareIntakeActivity — receives files (PDF, image, audio) and text shared from WhatsApp and other apps.
 * Safely forwards content URI and permissions to VoiceOverlayActivity for Gemini multimodal analysis.
 */
@AndroidEntryPoint
class ShareIntakeActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        try {
            val sharedUri: Uri? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra(Intent.EXTRA_STREAM)
            } ?: intent.clipData?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.uri ?: intent.data

            val sharedText = intent.getStringExtra(Intent.EXTRA_TEXT)
                ?: intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString()

            val overlayIntent = Intent(this, VoiceOverlayActivity::class.java).apply {
                action = Intent.ACTION_SEND
                sharedUri?.let { uri ->
                    data = uri
                    clipData = ClipData.newRawUri(null, uri)
                    putExtra("shared_uri", uri.toString())
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                putExtra("auto_listen", false)
                putExtra("shared_text", sharedText)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(overlayIntent)
        } catch (e: Exception) {
            Log.e("ShareIntakeActivity", "Error processing shared content", e)
        } finally {
            finish()
        }
    }
}
