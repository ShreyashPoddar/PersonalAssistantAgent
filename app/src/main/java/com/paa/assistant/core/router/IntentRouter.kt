package com.paa.assistant.core.router

import javax.inject.Inject
import javax.inject.Singleton

/**
 * The 50ms Edge-vs-Cloud gatekeeper.
 *
 * Evaluates every user utterance and returns an [ExecutionRoute] indicating
 * whether the command should be handled locally (fast path, offline, free)
 * or escalated to Gemini 2.0 Flash (complex, multimodal, grounded search).
 *
 * Local fast-path triggers (examples):
 *   - "Remind me to call John tomorrow at 4 PM"
 *   - "Show my tasks for today"
 *   - "Mark dentist appointment as done"
 *   - "Set an alarm for 7 AM"
 *
 * Cloud escalation triggers (examples):
 *   - "Read the invoice PDF from my downloads"
 *   - "Search Google for the best restaurants near me"
 *   - "What time does the Apple Store close today?"
 *   - "Summarize the document John sent on WhatsApp"
 *   - "Plan a 3-day trip to Goa next weekend"
 */
sealed class ExecutionRoute {
    data class LocalExecution(val result: LocalParseResult) : ExecutionRoute()
    /** @param needsCloud true when the request clearly needs the internet or general knowledge (keyword hit). */
    data class CloudEscalation(val reason: String, val originalInput: String = "", val needsCloud: Boolean = false) : ExecutionRoute()
}

@Singleton
class IntentRouter @Inject constructor(
    private val parser: LocalTaskParser
) {

    // Keywords that always trigger cloud escalation regardless of local confidence
    private val cloudKeywords = listOf(
        // File operations
        "read file", "read the file", "read pdf", "read document",
        "from downloads", "from documents", "from whatsapp",
        "open file", "open document", "summarize file",

        // Real-time search
        "search", "google", "look up", "find out",
        "what time does", "when does", "is it open",
        "weather", "temperature", "forecast",
        "news", "latest", "current", "live",

        // Deep reasoning / planning
        "plan a", "plan my", "help me plan",
        "write a", "draft a", "compose",
        "explain", "what is", "who is",
        "how do i", "how to",
        "suggest", "recommend", "best way",

        // WhatsApp / share
        "whatsapp", "from my chat", "message from",

        // Complex multi-step
        "trip", "itinerary", "travel",
        "before that", "so that", "based on"
    )

    /**
     * Routes a user utterance to either local execution or cloud escalation.
     * Runs synchronously in <1ms for cloud keyword detection, <30ms for full local parsing.
     */
    fun route(input: String): ExecutionRoute {
        val lower = input.trim().lowercase()

        // Always-local intents: they read the user's (possibly private) task list or places,
        // so they must never go to the cloud even if they contain words like "plan my" or "current".
        val early = parser.parse(input)
        if (early.intent in setOf(LocalIntent.PLAN_TASKS, LocalIntent.SAVE_PLACE, LocalIntent.LIST_TASKS, LocalIntent.QUERY_AGENDA, LocalIntent.RESCHEDULE_TASK, LocalIntent.SEND_MESSAGE) ||
            (early.intent == LocalIntent.CREATE_TASK && early.locationName != null)
        ) {
            return ExecutionRoute.LocalExecution(early)
        }

        // Fast cloud bypass check: explicit cloud keywords override everything
        for (keyword in cloudKeywords) {
            if (lower.contains(keyword)) {
                return ExecutionRoute.CloudEscalation(
                    reason = "Cloud keyword detected: '$keyword'",
                    originalInput = input,
                    needsCloud = true
                )
            }
        }

        // Attempt local deterministic parse
        val localResult = parser.parse(input)

        return if (localResult.confidence >= 0.85f && localResult.intent != LocalIntent.UNKNOWN) {
            ExecutionRoute.LocalExecution(localResult)
        } else {
            ExecutionRoute.CloudEscalation(
                reason = "Low local confidence (${localResult.confidence}) or unknown intent",
                originalInput = input
            )
        }
    }
}
