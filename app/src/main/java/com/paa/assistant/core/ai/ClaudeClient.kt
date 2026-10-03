package com.paa.assistant.core.ai

import android.util.Log
import com.paa.assistant.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Second cloud brain for heavy, NON-private work: long plans, comparisons, write-ups, analysing a
 * document the owner shared. Gemini Flash stays the default for quick voice answers.
 *
 * Privacy: only ever called with the owner's own spoken/typed request and cloud-safe context
 * (see [com.paa.assistant.core.privacy.PrivacyGuard]) — never chat messages or call transcripts.
 * Off until ANTHROPIC_API_KEY is set in .env.
 */
@Singleton
class ClaudeClient @Inject constructor() {
    private val tag = "ClaudeClient"

    val isConfigured: Boolean get() = BuildConfig.ANTHROPIC_API_KEY.isNotBlank()

    /** Returns the answer text, or null if not configured / failed (caller falls back to Gemini). */
    suspend fun ask(request: String, context: String = "", deep: Boolean = false): String? = withContext(Dispatchers.IO) {
        if (!isConfigured) return@withContext null
        try {
            val body = JSONObject()
                .put("model", if (deep) "claude-opus-5-5" else "claude-sonnet-5-5")
                .put("max_tokens", 1500)
                .put("system", "You are PAA, the owner's personal assistant on their phone. Answers are shown as text in a small popup: " +
                    "be concrete and well organised, use short paragraphs or simple lists, no markdown headings.")
                .put("messages", JSONArray().put(JSONObject().put("role", "user").put("content",
                    if (context.isBlank()) request else "$request\n\nContext:\n$context")))
            val conn = (URL("https://api.anthropic.com/v1/messages").openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 15_000
                readTimeout = 120_000
                doOutput = true
                setRequestProperty("content-type", "application/json")
                setRequestProperty("x-api-key", BuildConfig.ANTHROPIC_API_KEY)
                setRequestProperty("anthropic-version", "2023-06-01")
            }
            conn.outputStream.use { it.write(body.toString().toByteArray()) }
            if (conn.responseCode !in 200..299) {
                Log.e(tag, "HTTP ${conn.responseCode}")
                return@withContext null
            }
            val json = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
            val parts = json.optJSONArray("content") ?: return@withContext null
            (0 until parts.length()).mapNotNull { parts.optJSONObject(it)?.takeIf { p -> p.optString("type") == "text" }?.optString("text") }
                .joinToString("\n").trim().ifEmpty { null }
        } catch (e: Exception) {
            Log.e(tag, "Request failed: ${e.javaClass.simpleName}")
            null
        }
    }

    companion object {
        /** Requests worth the slower, stronger model. */
        private val heavy = Regex(
            "\\b(think (deeply|hard)|deep(ly)?|detailed|in detail|step[- ]by[- ]step|compare|pros and cons|analy[sz]e|" +
                "write (a|an|me)|draft|essay|report|plan (my|a|the) (week|month|semester|trip|study)|strategy|research|explain why)\\b",
            RegexOption.IGNORE_CASE
        )

        fun isHeavy(request: String) = heavy.containsMatchIn(request)
        fun wantsDeepest(request: String) = Regex("\\b(think (deeply|hard)|deep(ly)?)\\b", RegexOption.IGNORE_CASE).containsMatchIn(request)
    }
}
