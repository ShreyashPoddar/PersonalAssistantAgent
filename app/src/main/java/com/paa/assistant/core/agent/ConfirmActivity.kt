package com.paa.assistant.core.agent

import android.os.Build
import android.os.Bundle
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG
import androidx.biometric.BiometricPrompt
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import com.paa.assistant.ui.theme.PAATheme
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/** Shows one pending action's typed summary; Confirm (and a fingerprint when asked) approves exactly that action. */
@AndroidEntryPoint
class ConfirmActivity : FragmentActivity() {

    @Inject lateinit var broker: ConfirmBroker
    @Inject lateinit var gate: BiometricGate

    private var requestId: String? = null
    private var decided = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) setShowWhenLocked(true)
        val id = intent.getStringExtra(EXTRA_REQUEST_ID)
        val p = id?.let { broker.get(it) }
        if (id == null || p == null) return finish()
        requestId = id
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = decide(false, null)
        })
        setContent {
            PAATheme {
                Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(20.dp)) {
                            Text(title(p.action.tier), style = MaterialTheme.typography.headlineSmall)
                            Spacer(Modifier.height(12.dp))
                            Text(p.action.summary(), style = MaterialTheme.typography.bodyLarge)
                            Spacer(Modifier.height(8.dp))
                            Text(sourceText(p.source), style = MaterialTheme.typography.bodySmall)
                            Spacer(Modifier.height(20.dp))
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.End)) {
                                OutlinedButton(onClick = { decide(false, null) }) { Text("Cancel") }
                                Button(onClick = { if (p.biometric) askFingerprint(id, p) else decide(true, null) }) {
                                    Text("Confirm")
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    private fun askFingerprint(id: String, p: ConfirmBroker.Pending) {
        val signature = runCatching { gate.signatureForPrompt() }.getOrElse { return decide(false, null) }
        val callback = object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                val sig = runCatching {
                    result.cryptoObject!!.signature!!.apply { update(gate.digest(p.action, id)) }.sign()
                }.getOrNull()
                decide(sig != null, sig)
            }

            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) = decide(false, null)
        }
        val info = BiometricPrompt.PromptInfo.Builder()
            .setTitle(p.action.summary())
            .setAllowedAuthenticators(BIOMETRIC_STRONG)
            .setNegativeButtonText("Cancel")
            .build()
        BiometricPrompt(this, ContextCompat.getMainExecutor(this), callback)
            .authenticate(info, BiometricPrompt.CryptoObject(signature))
    }

    private fun decide(approved: Boolean, sig: ByteArray?) {
        if (decided) return
        decided = true
        requestId?.let { broker.complete(it, approved, sig) }
        finish()
    }

    override fun onDestroy() {
        // Leaving without a decision = cancel (not on rotation, which recreates this screen).
        if (!isChangingConfigurations) decide(false, null)
        super.onDestroy()
    }

    private fun title(t: RiskTier) = when (t) {
        RiskTier.MONEY -> "Pay?"
        RiskTier.OUTWARD -> "Send this?"
        RiskTier.SOS -> "Send SOS?"
        else -> "Do this?"
    }

    private fun sourceText(s: CommandSource) = when (s) {
        CommandSource.OWNER_VOICE -> "You asked by voice"
        CommandSource.OWNER_UI -> "You asked in the app"
        CommandSource.SCHEDULE -> "Scheduled earlier"
        CommandSource.SYSTEM_EVENT -> "PAA noticed this"
    }

    companion object {
        const val EXTRA_REQUEST_ID = "request_id"
    }
}
