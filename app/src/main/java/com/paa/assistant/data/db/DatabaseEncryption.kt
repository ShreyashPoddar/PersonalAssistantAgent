package com.paa.assistant.data.db

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import net.zetetic.database.sqlcipher.SQLiteDatabase
import java.io.File
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Database encryption for PAA (SQLCipher, AES-256).
 *
 * A random passphrase is generated once, encrypted with a hardware-backed Android Keystore key,
 * and stored in private SharedPreferences. The Keystore key never leaves the secure hardware,
 * so the database file is unreadable if copied off the phone.
 */
object DatabaseEncryption {
    private const val TAG = "DatabaseEncryption"
    private const val KEYSTORE = "AndroidKeyStore"
    private const val KEY_ALIAS = "paa_db_key"
    private const val PREFS = "paa_secure"
    private const val PREF_PASSPHRASE = "db_passphrase"

    /** Returns the database passphrase, creating it on first run. */
    @Synchronized
    fun getPassphrase(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs.getString(PREF_PASSPHRASE, null)?.let { return decrypt(it) }

        val bytes = ByteArray(32).also { SecureRandom().nextBytes(it) }
        val passphrase = bytes.joinToString("") { "%02x".format(it) }
        prefs.edit().putString(PREF_PASSPHRASE, encrypt(passphrase)).commit()
        return passphrase
    }

    /** Converts an existing plaintext database (from before encryption was added) in place. */
    fun migratePlaintextIfNeeded(context: Context, dbName: String, passphrase: String) {
        val dbFile = context.getDatabasePath(dbName)
        if (!dbFile.exists() || !isPlaintext(dbFile)) return

        val tmp = File(dbFile.parentFile, "$dbName.encrypting")
        tmp.delete()
        try {
            val db = SQLiteDatabase.openDatabase(dbFile.absolutePath, "", null, SQLiteDatabase.OPEN_READWRITE, null)
            db.rawExecSQL("ATTACH DATABASE '${tmp.absolutePath}' AS encrypted KEY '$passphrase'")
            db.rawExecSQL("SELECT sqlcipher_export('encrypted')")
            db.rawExecSQL("PRAGMA encrypted.user_version = ${db.version}")
            db.rawExecSQL("DETACH DATABASE encrypted")
            db.close()

            listOf("", "-wal", "-shm", "-journal").forEach { File(dbFile.path + it).delete() }
            if (!tmp.renameTo(dbFile)) throw IllegalStateException("rename failed")
            Log.i(TAG, "Existing database encrypted")
        } catch (e: Exception) {
            Log.e(TAG, "Plaintext migration failed; starting a fresh encrypted database", e)
            tmp.delete()
            listOf("", "-wal", "-shm", "-journal").forEach { File(dbFile.path + it).delete() }
        }
    }

    private fun isPlaintext(file: File): Boolean {
        val header = ByteArray(16)
        val read = file.inputStream().use { it.read(header) }
        return read == 16 && String(header, Charsets.US_ASCII) == "SQLite format 3\u0000"
    }

    private fun keystoreKey(): SecretKey {
        val ks = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (ks.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }

        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE).apply {
            init(
                KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .build()
            )
        }.generateKey()
    }

    /** AES-GCM with the hardware-backed Keystore key (also used for the detection log file). */
    internal fun encrypt(plain: String): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, keystoreKey()) }
        val out = cipher.iv + cipher.doFinal(plain.toByteArray())
        return Base64.encodeToString(out, Base64.NO_WRAP)
    }

    internal fun decrypt(stored: String): String {
        val data = Base64.decode(stored, Base64.NO_WRAP)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.DECRYPT_MODE, keystoreKey(), GCMParameterSpec(128, data, 0, 12))
        }
        return String(cipher.doFinal(data, 12, data.size - 12))
    }
}
