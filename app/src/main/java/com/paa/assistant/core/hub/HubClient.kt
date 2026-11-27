package com.paa.assistant.core.hub

import android.content.Context
import com.paa.assistant.data.db.DatabaseEncryption
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.URL
import java.security.MessageDigest
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager

/** What the hub prints on first run (hub/server.py). */
data class HubPairing(val host: String, val port: Int, val token: String, val certSha256: String) {
    companion object {
        /** Parses the payload JSON; null if anything is missing or malformed. */
        fun parse(json: String): HubPairing? = runCatching {
            val o = JSONObject(json.trim())
            val fp = o.getString("cert_sha256").lowercase()
            require(fp.length == 64 && fp.all { it in '0'..'9' || it in 'a'..'f' })
            val token = o.getString("token")
            require(token.length >= 16)
            HubPairing(o.getString("host"), o.getInt("port").also { require(it in 1..65535) }, token, fp)
        }.getOrNull()
    }
}

/** Stores the pairing (token encrypted with the Keystore key); "hub enabled" switch. */
class HubSettings(ctx: Context) {
    private val p = ctx.getSharedPreferences("paa_hub", Context.MODE_PRIVATE)

    var pairing: HubPairing?
        get() = p.getString("pairing", null)?.let { runCatching { HubPairing.parse(DatabaseEncryption.decrypt(it)) }.getOrNull() }
        set(v) = p.edit().apply { if (v == null) remove("pairing") else putString("pairing", DatabaseEncryption.encrypt(
            JSONObject().put("host", v.host).put("port", v.port).put("token", v.token).put("cert_sha256", v.certSha256).toString()
        )) }.apply()
    var enabled: Boolean
        get() = p.getBoolean("enabled", false)
        set(v) = p.edit().putBoolean("enabled", v).apply()
}

/**
 * Talks to the owner's hub over HTTPS (P4-T1). The hub's self-signed certificate is accepted only if its SHA-256
 * matches the one from pairing (pinning) — no other certificate, CA or host is trusted.
 */
class HubClient(private val pairing: HubPairing) {

    /** Pure: does this certificate (DER) match the pinned fingerprint? */
    companion object {
        fun pinMatches(der: ByteArray, pinnedSha256: String): Boolean =
            MessageDigest.getInstance("SHA-256").digest(der).joinToString("") { "%02x".format(it) } == pinnedSha256.lowercase()
    }

    private val pinning = object : X509TrustManager {
        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = throw CertificateException("client")
        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
            val leaf = chain?.firstOrNull() ?: throw CertificateException("no certificate")
            if (!pinMatches(leaf.encoded, pairing.certSha256)) throw CertificateException("certificate doesn't match the paired hub")
        }
        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
    }

    private val ssl = SSLContext.getInstance("TLS").apply { init(null, arrayOf(pinning), null) }

    private suspend fun call(path: String, body: JSONObject? = null, readTimeoutMs: Int = 8_000): JSONObject = withContext(Dispatchers.IO) {
        val c = URL("https://${pairing.host}:${pairing.port}$path").openConnection() as HttpsURLConnection
        c.sslSocketFactory = ssl.socketFactory
        c.hostnameVerifier = javax.net.ssl.HostnameVerifier { _, _ -> true }  // identity = the pinned cert, not the name
        c.connectTimeout = 3_000; c.readTimeout = readTimeoutMs
        c.setRequestProperty("Authorization", "Bearer ${pairing.token}")
        if (body != null) {
            c.requestMethod = "POST"; c.doOutput = true
            c.setRequestProperty("Content-Type", "application/json")
            c.outputStream.use { it.write(body.toString().toByteArray()) }
        }
        try {
            val code = c.responseCode
            val text = (if (code in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (code !in 200..299) throw IllegalStateException("hub answered $code")
            JSONObject(text)
        } finally { c.disconnect() }
    }

    suspend fun health(): Result<String> = runCatching {
        val o = call("/health")
        "Hub OK (v${o.optString("version")}, up ${o.optLong("uptime_s") / 60} min) · laptop AI " +
            (if (o.optBoolean("extract")) "on" else "off") + " · WhatsApp Desktop " + (if (o.optBoolean("whatsapp")) "on" else "off")
    }

    suspend fun pair(device: String): Result<Boolean> = runCatching { call("/pair", JSONObject().put("device", device)).optBoolean("paired") }

    /** One WhatsApp Desktop chat-list change the laptop saw (a preview: never the full chat; the laptop never opens chats). */
    data class LaptopMessage(val seq: Long, val chat: String, val sender: String?, val text: String, val group: Boolean)

    /** Laptop WhatsApp messages after [after] → (messages, last seq). */
    suspend fun messages(after: Long): Result<Pair<List<LaptopMessage>, Long>> = runCatching {
        val o = call("/messages?after=$after")
        val arr = o.getJSONArray("items")
        val items = (0 until arr.length()).map { arr.getJSONObject(it) }.map {
            LaptopMessage(it.getLong("seq"), it.getString("chat"), it.optString("sender").takeIf { s -> s.isNotBlank() && s != "null" },
                it.getString("text"), it.optBoolean("group"))
        }
        items to o.optLong("last", after)
    }

    /** The laptop's model reads one task prompt (already sanitised: OTPs masked, banking apps never get here). */
    suspend fun extract(prompt: String): Result<String> = runCatching {
        call("/extract", JSONObject().put("prompt", prompt), readTimeoutMs = 30_000).getString("output")
    }
}
