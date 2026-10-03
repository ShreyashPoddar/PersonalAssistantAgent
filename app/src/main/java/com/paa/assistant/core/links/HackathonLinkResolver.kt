package com.paa.assistant.core.links

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/** A hackathon found behind a shared link. [deadline] is the registration deadline, if known. */
data class HackathonInfo(val name: String, val deadline: Long?, val url: String)

/**
 * Recognises hackathon links shared in chats and looks up their registration deadline.
 *
 * Privacy: only the link itself is requested (like WhatsApp's own link preview). No chat text
 * is sent anywhere.
 *  - Unstop: public API → exact title + registration deadline
 *  - Devfolio, Devpost, MLH, Hack2skill, HackerEarth, …: page title; deadline usually unknown
 *  - Any other link: page title/description checked for hackathon words
 */
@Singleton
class HackathonLinkResolver @Inject constructor() {
    private val tag = "HackathonLinks"

    private val urlRegex = Regex("https?://[^\\s<>\"')]+", RegexOption.IGNORE_CASE)
    private val unstopId = Regex("unstop\\.com/.*?-(\\d{5,})(?:[/?#]|$)", RegexOption.IGNORE_CASE)
    private val hackathonHosts = listOf(
        "unstop.com", "devfolio.co", "devpost.com", "mlh.io", "hack2skill.com",
        "hackerearth.com", "dorahacks.io", "lablab.ai", "hackathon.com", "kaggle.com/competitions"
    )
    private val hackathonWords = Regex(
        "\\b(hackathon|hackfest|buildathon|ideathon|codeathon|hack\\s?week|devfest|coding contest|hacks?\\b)",
        RegexOption.IGNORE_CASE
    )
    // Skip links that are never hackathons, to avoid needless requests
    private val ignoredHosts = listOf(
        "youtube.com", "youtu.be", "instagram.com", "maps.google", "wa.me", "chat.whatsapp.com",
        "spotify.com", "twitter.com", "x.com", "zomato.com", "swiggy.com", "amazon.", "flipkart.com"
    )

    fun extractUrls(text: String): List<String> =
        urlRegex.findAll(text).map { it.value.trimEnd('.', ',', '!', '?') }.distinct().toList()

    suspend fun resolve(url: String): HackathonInfo? = withContext(Dispatchers.IO) {
        val lower = url.lowercase(Locale.ENGLISH)
        if (ignoredHosts.any { lower.contains(it) }) return@withContext null
        try {
            unstopId.find(url)?.let { return@withContext resolveUnstop(it.groupValues[1], url) }

            val knownHost = hackathonHosts.any { lower.contains(it) }
            val page = fetch(url, maxBytes = 300_000) ?: return@withContext if (knownHost) HackathonInfo("hackathon", null, url) else null
            val title = metaContent(page, "og:title") ?: Regex("<title[^>]*>([^<]+)</title>", RegexOption.IGNORE_CASE)
                .find(page)?.groupValues?.get(1)?.trim()
            val description = metaContent(page, "og:description") ?: metaContent(page, "description") ?: ""

            if (!knownHost && !hackathonWords.containsMatchIn("$title $description")) return@withContext null
            HackathonInfo(cleanTitle(title) ?: "hackathon", null, url)
        } catch (e: Exception) {
            Log.w(tag, "Could not resolve link: ${e.javaClass.simpleName}")
            null
        }
    }

    private fun resolveUnstop(id: String, url: String): HackathonInfo? {
        val body = fetch("https://unstop.com/api/public/competition/$id", maxBytes = 2_000_000) ?: return null
        val competition = JSONObject(body).optJSONObject("data")?.optJSONObject("competition") ?: return null
        val deadlineIso = competition.optJSONObject("regnRequirements")?.optString("end_regn_dt")
            ?.takeIf { it.isNotBlank() && it != "null" }
        val deadline = deadlineIso?.let {
            SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.ENGLISH).parse(it)?.time
        }
        return HackathonInfo(competition.optString("title").ifBlank { "Unstop hackathon" }, deadline, url)
    }

    private fun fetch(url: String, maxBytes: Int): String? {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 10_000
            readTimeout = 15_000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14) PAA/1.0")
            setRequestProperty("Accept-Language", "en")
        }
        if (conn.responseCode !in 200..299) return null
        return conn.inputStream.use { input ->
            val buf = ByteArray(maxBytes)
            var total = 0
            while (total < maxBytes) {
                val n = input.read(buf, total, maxBytes - total)
                if (n < 0) break
                total += n
            }
            String(buf, 0, total)
        }
    }

    private fun metaContent(html: String, name: String): String? =
        Regex("<meta[^>]+(?:property|name)=[\"']$name[\"'][^>]*content=[\"']([^\"']+)", RegexOption.IGNORE_CASE)
            .find(html)?.groupValues?.get(1)?.trim()
            ?: Regex("<meta[^>]+content=[\"']([^\"']+)[\"'][^>]*(?:property|name)=[\"']$name[\"']", RegexOption.IGNORE_CASE)
                .find(html)?.groupValues?.get(1)?.trim()

    /** "SIH 2026 | Devfolio" → "SIH 2026" */
    private fun cleanTitle(title: String?): String? =
        title?.split(" | ", " - ", " – ")?.firstOrNull()?.trim()?.take(80)?.ifBlank { null }
}
