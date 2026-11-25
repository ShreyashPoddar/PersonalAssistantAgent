package com.paa.assistant.core.sos

import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG
import androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity

/** "I'm safe": only the owner (fingerprint or phone PIN) can stop an SOS and send "safe now". */
class SosSafeActivity : FragmentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) setShowWhenLocked(true)
        val prompt = BiometricPrompt(this, ContextCompat.getMainExecutor(this), object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                startService(Intent(this@SosSafeActivity, SosService::class.java).setAction(SosService.ACTION_SAFE))
                finish()
            }
            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) = finish()
        })
        prompt.authenticate(
            BiometricPrompt.PromptInfo.Builder()
                .setTitle("Stop SOS — I'm safe")
                .setAllowedAuthenticators(BIOMETRIC_STRONG or DEVICE_CREDENTIAL)
                .build()
        )
    }
}
