package com.paa.assistant.core.ai

import android.content.Context
import android.util.Log
import com.google.ai.client.generativeai.GenerativeModel
import com.google.ai.client.generativeai.type.BlockThreshold
import com.google.ai.client.generativeai.type.Content
import com.google.ai.client.generativeai.type.FunctionDeclaration
import com.google.ai.client.generativeai.type.FunctionResponsePart
import com.google.ai.client.generativeai.type.GenerationConfig
import com.google.ai.client.generativeai.type.HarmCategory
import com.google.ai.client.generativeai.type.SafetySetting
import com.google.ai.client.generativeai.type.Schema
import com.google.ai.client.generativeai.type.Tool
import com.google.ai.client.generativeai.type.content
import com.google.ai.client.generativeai.type.generationConfig
import com.paa.assistant.BuildConfig
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Structured response from Gemini after processing a user request.
 */
data class GeminiResponse(
    val spokenText: String,           // Text to be spoken via TTS
    val toolCall: GeminiToolCall? = null,  // Optional structured action to execute locally
    val failed: Boolean = false           // no model answered (offline, quota…): caller may fall back to on-device
)

/**
 * A function call returned by Gemini for local execution.
 */
data class GeminiToolCall(
    val functionName: String,
    val args: JSONObject
)

/**
 * GeminiClient — PAA's cloud brain.
 *
 * Uses Google Generative AI SDK (Gemini 2.0 Flash) with:
 *  - Structured Function Calling for task management actions
 *  - Google Search Grounding for real-time information
 *  - Multimodal support (PDF, image, audio bytes)
 *  - Conversation memory via injected context
 *
 * API key is stored in .env (or local.properties) as GEMINI_API_KEY.
 */
@Singleton
class GeminiClient @Inject constructor(
    @ApplicationContext private val context: Context,
    private val promptTemplates: PromptTemplates
) {
    private val tag = "GeminiClient"

    companion object {
        private const val MODEL_NAME = "gemini-3.6-flash"
    }

    // Generation configuration for PAA — optimized for fast, concise TTS-friendly responses
    private val generationConfig: GenerationConfig = generationConfig {
        temperature = 0.4f          // Lower temp = more deterministic, factual responses
        topP = 0.9f
        maxOutputTokens = 800       // Keep responses concise for voice playback
        responseMimeType = "text/plain"
    }

    // Safety settings — relaxed for personal assistant context
    private val safetySettings = listOf(
        SafetySetting(HarmCategory.HARASSMENT, BlockThreshold.MEDIUM_AND_ABOVE),
        SafetySetting(HarmCategory.HATE_SPEECH, BlockThreshold.MEDIUM_AND_ABOVE),
        SafetySetting(HarmCategory.SEXUALLY_EXPLICIT, BlockThreshold.MEDIUM_AND_ABOVE),
        SafetySetting(HarmCategory.DANGEROUS_CONTENT, BlockThreshold.MEDIUM_AND_ABOVE),
    )

    private fun createModel(name: String): GenerativeModel {
        return GenerativeModel(
            modelName = name,
            apiKey = BuildConfig.GEMINI_API_KEY,
            generationConfig = generationConfig,
            safetySettings = safetySettings,
            tools = listOf(buildToolDeclarations()),
            systemInstruction = content { text(promptTemplates.buildSystemPrompt()) }
        )
    }

    // Candidate models in preference order for fast responses and tool calling
    private val candidateModels = listOf(
        MODEL_NAME,
        "gemini-3.1-flash-lite",
        "gemini-3.5-flash",
        "gemini-3.8-flash"
    )

    private val modelMap = mutableMapOf<String, GenerativeModel>()

    @Synchronized
    private fun getModel(name: String): GenerativeModel {
        return modelMap.getOrPut(name) { createModel(name) }
    }

    /**
     * Send a text query to Gemini and get a structured response.
     * Automatically injects current date/time and user memory context.
     * Tries candidate models in order if transient 503/429 server spikes occur.
     */
    suspend fun sendTextQuery(
        userMessage: String,
        memoryFacts: List<String> = emptyList(),
        taskContext: String = ""
    ): GeminiResponse {
        val contextualPrompt = promptTemplates.buildUserPrompt(
            userMessage = userMessage,
            memoryFacts = memoryFacts,
            taskContext = taskContext
        )

        var lastException: Exception? = null

        for ((index, modelName) in candidateModels.withIndex()) {
            try {
                Log.d(tag, "Attempting text query with model: $modelName")
                val model = getModel(modelName)
                return executeChat(model, contextualPrompt)
            } catch (e: Exception) {
                lastException = e
                Log.w(tag, "Model '$modelName' failed (attempt ${index + 1}/${candidateModels.size}): ${e.message}")
                if (index < candidateModels.size - 1) {
                    kotlinx.coroutines.delay(350)
                }
            }
        }

        Log.e(tag, "All Gemini models exhausted", lastException)
        return GeminiResponse(spokenText = sanitizeErrorMessage(lastException), failed = true)
    }

    private suspend fun executeChat(model: GenerativeModel, prompt: String): GeminiResponse {
        val chat = model.startChat()
        val response = chat.sendMessage(prompt)
        val candidate = response.candidates.firstOrNull()

        // Check if Gemini returned a function call
        val functionCall = candidate?.content?.parts
            ?.filterIsInstance<com.google.ai.client.generativeai.type.FunctionCallPart>()
            ?.firstOrNull()

        return if (functionCall != null) {
            Log.d(tag, "Function call: ${functionCall.name} args: ${functionCall.args}")
            GeminiResponse(
                spokenText = generateFunctionCallConfirmation(functionCall.name, functionCall.args),
                toolCall = GeminiToolCall(
                    functionName = functionCall.name,
                    args = JSONObject(functionCall.args ?: emptyMap<String, Any>())
                )
            )
        } else {
            val text = response.text ?: "I wasn't able to process that. Please try again."
            GeminiResponse(spokenText = text)
        }
    }

    /**
     * Send a multimodal query to Gemini with a document/file (PDF, image, etc.)
     */
    suspend fun sendFileQuery(
        userMessage: String,
        fileBytes: ByteArray,
        mimeType: String,
        memoryFacts: List<String> = emptyList()
    ): GeminiResponse {
        val inputContent = content {
            blob(mimeType, fileBytes)
            text(userMessage)
            if (memoryFacts.isNotEmpty()) {
                text("\n\nPersonal context:\n" + memoryFacts.joinToString("\n") { "- $it" })
            }
        }

        var lastException: Exception? = null

        for ((index, modelName) in candidateModels.withIndex()) {
            try {
                Log.d(tag, "Attempting file query with model: $modelName")
                val model = getModel(modelName)
                val chat = model.startChat()
                val response = chat.sendMessage(inputContent)
                val text = response.text ?: "I couldn't extract information from that file."
                return GeminiResponse(spokenText = text)
            } catch (e: Exception) {
                lastException = e
                Log.w(tag, "Model '$modelName' file query failed (attempt ${index + 1}/${candidateModels.size}): ${e.message}")
                if (index < candidateModels.size - 1) {
                    kotlinx.coroutines.delay(350)
                }
            }
        }

        Log.e(tag, "All Gemini models exhausted for file query", lastException)
        return GeminiResponse(spokenText = sanitizeErrorMessage(lastException))
    }

    private fun sanitizeErrorMessage(e: Exception?): String {
        val msg = e?.message ?: ""
        return when {
            msg.contains("503") || msg.contains("high demand") || msg.contains("UNAVAILABLE") || msg.contains("MissingFieldException") ->
                "The AI servers are currently experiencing high traffic. Please try again in a moment."
            msg.contains("404") || msg.contains("not found") ->
                "The AI model is temporarily unavailable. Please try again in a moment."
            msg.contains("UnknownHost") || msg.contains("ConnectException") || msg.contains("timeout") || msg.contains("SocketTimeout") ->
                "Unable to connect to the network. Please check your internet connection and try again."
            else ->
                "I had trouble connecting to the AI service. Please try again in a moment."
        }
    }

    /**
     * Answer a real-time question using Gemini's native Google Search grounding.
     * The legacy generativeai SDK can't declare the google_search tool, so this calls REST directly.
     * If grounding is unavailable (e.g. free-tier quota → HTTP 429), answers from Tavily search results,
     * and only as a last resort gives an ungrounded answer.
     */
    suspend fun searchWeb(query: String): String = withContext(Dispatchers.IO) {
        try {
            generateRest(query, grounded = true)
                ?: tavilySearch(query)?.let { results ->
                    generateRest("Question: $query\n\nLive web search results:\n$results\n\n" +
                        "Answer the question using these results.", grounded = false)
                }
                ?: generateRest(query, grounded = false)?.let { "I can't search live right now, but from what I know: $it" }
                ?: "I couldn't search the web right now. Please try again."
        } catch (e: Exception) {
            Log.e(tag, "Search grounding error", e)
            "I couldn't search the web right now. Please check your internet connection."
        }
    }

    /** Queries Tavily and returns the results as plain text for Gemini, or null if unavailable. */
    private fun tavilySearch(query: String): String? {
        if (BuildConfig.TAVILY_API_KEY.isBlank()) return null
        val body = JSONObject()
            .put("query", query)
            .put("max_results", 5)
            .put("include_answer", true)

        val conn = (URL("https://api.tavily.com/search").openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 15_000
            readTimeout = 30_000
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Authorization", "Bearer ${BuildConfig.TAVILY_API_KEY}")
        }
        conn.outputStream.use { it.write(body.toString().toByteArray()) }

        if (conn.responseCode !in 200..299) {
            val err = conn.errorStream?.bufferedReader()?.use { it.readText() }
            Log.e(tag, "Tavily HTTP ${conn.responseCode}: $err")
            return null
        }

        val json = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
        val results = json.optJSONArray("results")
        return buildString {
            json.optString("answer").takeIf { it.isNotBlank() && it != "null" }?.let { append("Summary: $it\n") }
            if (results != null) for (i in 0 until results.length()) {
                val r = results.optJSONObject(i) ?: continue
                append("- ${r.optString("title")}: ${r.optString("content").take(500)}\n")
            }
        }.trim().ifEmpty { null }
    }

    /** Single REST generateContent call. Returns the reply text, or null on an HTTP error. */
    private fun generateRest(query: String, grounded: Boolean): String? {
        val body = JSONObject()
            .put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text",
                "You are PAA, a voice assistant. Current time: ${currentIsoTimestamp()}. " +
                    "Answer in 1-3 short sentences suitable for text-to-speech. No markdown."))))
            .put("contents", JSONArray().put(JSONObject()
                .put("role", "user")
                .put("parts", JSONArray().put(JSONObject().put("text", query)))))
        if (grounded) body.put("tools", JSONArray().put(JSONObject().put("google_search", JSONObject())))

        val conn = (URL("https://generativelanguage.googleapis.com/v1beta/models/$MODEL_NAME:generateContent")
            .openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 15_000
            readTimeout = 30_000
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("x-goog-api-key", BuildConfig.GEMINI_API_KEY)
        }
        conn.outputStream.use { it.write(body.toString().toByteArray()) }

        if (conn.responseCode !in 200..299) {
            val err = conn.errorStream?.bufferedReader()?.use { it.readText() }
            Log.e(tag, "generateContent (grounded=$grounded) HTTP ${conn.responseCode}: $err")
            return null
        }

        val json = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
        val parts = json.optJSONArray("candidates")?.optJSONObject(0)
            ?.optJSONObject("content")?.optJSONArray("parts")
        return buildString {
            if (parts != null) for (i in 0 until parts.length()) append(parts.optJSONObject(i)?.optString("text", "") ?: "")
        }.trim().ifEmpty { "I couldn't find anything on that." }
    }

    // ── TOOL / FUNCTION DECLARATIONS ─────────────────────────────────────────

    private fun buildToolDeclarations(): Tool {
        return Tool(
            functionDeclarations = listOf(
                // Create a task
                FunctionDeclaration(
                    name = "create_task",
                    description = "Create a new task or reminder for the user.",
                    parameters = listOf(
                        Schema.str("title", "The task title"),
                        Schema.str("due_timestamp_iso", "Due date/time in ISO-8601 format (nullable)"),
                        Schema.str("priority", "Priority: LOW, MEDIUM, HIGH, or URGENT"),
                        Schema.arr("tags", "A list of tags like #work or #health", Schema.str("tag", "tag name")),
                        Schema.int("reminder_minutes_before", "Minutes before due time to send reminder notification")
                    ),
                    requiredParameters = listOf("title")
                ),
                // Move an existing task to a new time
                FunctionDeclaration(
                    name = "reschedule_task",
                    description = "Change the due time of an EXISTING task (e.g. 'move it to 7', 'shift the github task to tomorrow evening'). Never create a new task for this.",
                    parameters = listOf(
                        Schema.str("task_title_hint", "Part of the existing task's title; empty for the task just mentioned"),
                        Schema.str("new_due_iso", "New due date/time in ISO-8601")
                    ),
                    requiredParameters = listOf("new_due_iso")
                ),
                // Update task status
                FunctionDeclaration(
                    name = "update_task_status",
                    description = "Mark a task as COMPLETED or CANCELLED by its ID or title.",
                    parameters = listOf(
                        Schema.long("task_id", "The task ID (use -1 if searching by title)"),
                        Schema.str("task_title_hint", "Partial task title for fuzzy matching if ID is unknown"),
                        Schema.str("status", "New status: COMPLETED or CANCELLED")
                    ),
                    requiredParameters = listOf("status")
                ),
                // Schedule a calendar event
                FunctionDeclaration(
                    name = "schedule_calendar_event",
                    description = "Create a calendar event in the user's device calendar.",
                    parameters = listOf(
                        Schema.str("title", "Event title"),
                        Schema.str("start_time_iso", "Start time ISO-8601"),
                        Schema.str("end_time_iso", "End time ISO-8601"),
                        Schema.str("location", "Optional location"),
                        Schema.str("notes", "Optional notes or agenda")
                    ),
                    requiredParameters = listOf("title", "start_time_iso")
                ),
                // Create a location-based reminder / geofence
                FunctionDeclaration(
                    name = "create_location_reminder",
                    description = "Create a location-based reminder triggered when arriving at or leaving a physical place (e.g., 'Remind me to buy groceries when I arrive at Walmart' or 'Remind me to lock the garage when I leave home').",
                    parameters = listOf(
                        Schema.str("title", "The reminder or task title"),
                        Schema.str("location_name", "Name or address of the destination or place (e.g., 'Walmart', 'Gym', 'Supermarket', 'Home', 'Office')"),
                        Schema.str("transition", "Trigger transition: 'ENTER' (when arriving at location) or 'EXIT' (when leaving location). Default is ENTER"),
                        Schema.int("radius_meters", "Geofence perimeter radius in meters. Default is 150")
                    ),
                    requiredParameters = listOf("title", "location_name")
                ),
                // Read a local file
                FunctionDeclaration(
                    name = "read_device_file",
                    description = "Request to read a file from the user's device storage.",
                    parameters = listOf(
                        Schema.str("filename_hint", "Partial filename to search for"),
                        Schema.str("directory_hint", "Where to look: DOWNLOADS, DOCUMENTS, or WHATSAPP_DOCS")
                    ),
                    requiredParameters = listOf("filename_hint")
                ),
                // Save personal memory fact
                FunctionDeclaration(
                    name = "save_personal_fact",
                    description = "Save a personal fact, user preference, or personal detail into PAA's long-term memory graph (e.g., 'Wife's birthday is June 14', 'Prefers meetings in the afternoon', 'Blood type is O+', 'Car license plate is ABC-1234').",
                    parameters = listOf(
                        Schema.str("topic", "Category or subject (e.g., 'family', 'preference', 'vehicle', 'health', 'work')"),
                        Schema.str("fact_content", "The specific fact or detail to remember forever")
                    ),
                    requiredParameters = listOf("topic", "fact_content")
                ),
                // Web search tool
                FunctionDeclaration(
                    name = "search_web_grounded",
                    description = "Search the web for real-time live data, store hours, flight info, weather, or current events.",
                    parameters = listOf(
                        Schema.str("query", "The search query to look up")
                    ),
                    requiredParameters = listOf("query")
                )
            )
        )
    }

    // Generate a natural confirmation message for function calls
    private fun generateFunctionCallConfirmation(functionName: String, args: Map<String, Any?>?): String {
        return when (functionName) {
            "create_task" -> "Done! I've added '${args?.get("title")}' to your tasks."
            "create_location_reminder" -> {
                val title = args?.get("title") ?: "your task"
                val loc = args?.get("location_name") ?: "the location"
                val transition = args?.get("transition")?.toString()?.uppercase() ?: "ENTER"
                val actionWord = if (transition == "EXIT") "leave" else "arrive at"
                "Done! I'll remind you to '$title' when you $actionWord $loc."
            }
            "save_personal_fact" -> "Got it! I've saved that to my memory: '${args?.get("fact_content")}'."
            "search_web_grounded" -> "Searching live information for '${args?.get("query")}'..."
            "update_task_status" -> {
                val status = args?.get("status") ?: "updated"
                val title = args?.get("task_title_hint") ?: "that task"
                "Got it! I've marked '$title' as ${status.toString().lowercase()}."
            }
            "reschedule_task" -> "Moved it."
            "schedule_calendar_event" -> "I've added '${args?.get("title")}' to your calendar."
            "read_device_file" -> "Looking for '${args?.get("filename_hint")}' in your ${args?.get("directory_hint") ?: "files"}..."
            else -> "Action completed."
        }
    }

    private fun currentIsoTimestamp(): String {
        val sdf = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.getDefault())
        sdf.timeZone = TimeZone.getDefault()
        return sdf.format(Date())
    }
}
