package com.paa.assistant.core.agent

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.KeyProperties
import dagger.hilt.android.qualifiers.ApplicationContext
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import javax.inject.Inject
import javax.inject.Singleton

/**
 * A Keystore key that signs only after a strong biometric. A confirm signs the digest of the exact action shown,
 * so a different action cannot be slipped in behind the fingerprint.
 */
@Singleton
class BiometricGate @Inject constructor(@ApplicationContext private val ctx: Context) {

    private val keyStore: KeyStore get() = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }

    fun ensureKey() {
        if (keyStore.containsAlias(ALIAS)) return
        val spec = KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_SIGN)
            .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
            .setDigests(KeyProperties.DIGEST_SHA256)
            .setUserAuthenticationRequired(true)
            .setInvalidatedByBiometricEnrollment(true)
            .apply {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    setUserAuthenticationParameters(0, KeyProperties.AUTH_BIOMETRIC_STRONG)
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P &&
                    ctx.packageManager.hasSystemFeature(PackageManager.FEATURE_STRONGBOX_KEYSTORE)
                ) setIsStrongBoxBacked(true)
            }
            .build()
        KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, ANDROID_KEYSTORE)
            .apply { initialize(spec) }
            .generateKeyPair()
    }

    /** A Signature ready for BiometricPrompt's CryptoObject. Recreates the key if a new fingerprint invalidated it. */
    fun signatureForPrompt(): Signature {
        ensureKey()
        return try {
            Signature.getInstance(SIG_ALG).apply { initSign(privateKey()) }
        } catch (e: KeyPermanentlyInvalidatedException) {
            keyStore.deleteEntry(ALIAS)
            ensureKey()
            Signature.getInstance(SIG_ALG).apply { initSign(privateKey()) }
        }
    }

    fun digest(action: Action, requestId: String): ByteArray = sha256(digestInput(action, requestId))

    fun verify(action: Action, requestId: String, sig: ByteArray): Boolean = runCatching {
        val pub = keyStore.getCertificate(ALIAS)?.publicKey ?: return false
        Signature.getInstance(SIG_ALG).apply { initVerify(pub); update(digest(action, requestId)) }.verify(sig)
    }.getOrDefault(false)

    private fun privateKey(): PrivateKey = keyStore.getKey(ALIAS, null) as PrivateKey

    companion object {
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val ALIAS = "paa_confirm_v1"
        private const val SIG_ALG = "SHA256withECDSA"

        /** What gets signed — pure, so it is testable without the Keystore. */
        fun digestInput(action: Action, requestId: String): String =
            "$requestId|${action::class.simpleName}|${action.summary()}"

        fun sha256(s: String): ByteArray = MessageDigest.getInstance("SHA-256").digest(s.toByteArray(Charsets.UTF_8))
    }
}
