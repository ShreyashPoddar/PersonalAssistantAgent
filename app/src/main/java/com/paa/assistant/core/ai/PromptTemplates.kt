package com.paa.assistant.core.ai

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Builds dynamic system prompts and user prompts for Gemini 2.0 Flash.
 *
 * The system prompt instructs Gemini on its role, capabilities, response style,
 * and how to handle function calls. It is injected once when the model initializes.
 *
 * User prompts are built per-request and include:
 *  - Current timestamp (so Gemini can reason about time correctly)
 *  - Personal memory context (facts about the user)
 *  - Upcoming task context (brief summary of pending tasks)
 */
@Singleton
class PromptTemplates @Inject constructor() {

    /**
     * Builds the static system-level instruction for Gemini.
     * This defines PAA's identity, capabilities, and response style constraints.
     */
    fun buildSystemPrompt(): String = """
        You are PAA — Personal Assistant Agent. You are a highly intelligent, proactive, and concise personal executive assistant living on the user's Android phone.
        
        ## Your Core Capabilities
        1. Task & Reminder Management: Create, update, complete, and delete tasks using function calls. Use create_location_reminder whenever a reminder is tied to arriving at or leaving a place. To change the time of an EXISTING task always call reschedule_task — never create_task again (that makes a duplicate).
        2. Calendar Scheduling: Create calendar events and appointments using function calls.
        3. File Intelligence: When given file content (PDF, document, image), extract key action items, deadlines, and summaries.
        4. Real-time Knowledge: You can search Google to answer questions about current events, store hours, weather, flight status, restaurant information, and live data.
        5. Personal Memory: Use the personal context provided to personalize your responses and anticipate needs.
        
        ## Response Style Rules — VERY IMPORTANT
        - Your responses will be converted to speech via Text-to-Speech (TTS). Keep responses short, natural, and conversational.
        - Avoid using markdown, bullet points, numbered lists, or headers in spoken responses.
        - When confirming an action, be brief: "Done! I've added your dentist appointment for Friday at 3 PM."
        - When answering a question, give the key fact first, then any important context.
        - Maximum 2 to 3 sentences for routine responses. Longer only when summarizing a document.
        - Never say "Certainly!" or "Of course!" — just do the task and confirm it directly.
        - If you cannot do something, tell the user clearly in one sentence.
        
        ## Function Calling Rules
        - If the user wants to create, update, or delete a task or event — ALWAYS use the appropriate function call. Never just say "I will add that" without calling the function.
        - When the user asks to be reminded at/upon arriving/leaving a location (e.g. "when I get to the grocery store", "remind me when I leave the office"), call create_location_reminder with the title and location_name.
        - When the user asks you to remember a personal detail, fact, or preference (e.g., "Remember that my car plate is ABC", "My sister's birthday is May 10", "I prefer morning meetings"), call save_personal_fact with an appropriate topic and the fact content.
        - If the user asks to read a file — call read_device_file so the app can load it.
        - After a function call, your spoken confirmation should be 1 sentence maximum.
        
        ## Timezone
        Always reason about dates and times in the user's local timezone provided in the context.
    """.trimIndent()

    /**
     * Builds a per-request user message with dynamic context injected.
     *
     * @param userMessage  The raw user utterance.
     * @param memoryFacts  List of personal context strings to personalize responses.
     * @param taskContext  A brief string describing today's pending tasks.
     */
    fun buildUserPrompt(
        userMessage: String,
        memoryFacts: List<String> = emptyList(),
        taskContext: String = ""
    ): String {
        val timestamp = currentLocalTimestamp()
        val timezone = TimeZone.getDefault().id

        val sb = StringBuilder()
        sb.appendLine("[Context]")
        sb.appendLine("Current date and time: $timestamp ($timezone)")

        if (taskContext.isNotBlank()) {
            sb.appendLine("Today's pending tasks: $taskContext")
        }

        if (memoryFacts.isNotEmpty()) {
            sb.appendLine("Personal context about this user:")
            memoryFacts.take(15).forEach { fact -> sb.appendLine("- $fact") }
        }

        sb.appendLine()
        sb.appendLine("[User Request]")
        sb.append(userMessage)

        return sb.toString()
    }

    /**
     * Builds the morning briefing prompt for the proactive morning update.
     *
     * @param taskSummary    e.g. "3 pending tasks: Call John at 10 AM, Submit report by noon, Dentist at 4 PM"
     * @param calendarSummary e.g. "Team standup at 9:30 AM, Project review at 2 PM"
     * @param weatherSummary e.g. "Partly cloudy, 28°C"
     */
    fun buildMorningBriefingPrompt(
        taskSummary: String,
        calendarSummary: String,
        weatherSummary: String = ""
    ): String {
        val timestamp = currentLocalTimestamp()
        return """
            Generate a concise, energetic 40-second morning executive briefing for the user.
            Today is: $timestamp
            ${if (weatherSummary.isNotBlank()) "Weather: $weatherSummary" else ""}
            Pending tasks today: $taskSummary
            Calendar events: $calendarSummary
            
            Rules:
            - Be warm, upbeat, and motivating but not over-the-top.
            - Mention the 2-3 most important items first.
            - End with one brief motivational note.
            - Natural spoken language only. No markdown. No lists. Just flowing speech.
            - Maximum 80 words.
        """.trimIndent()
    }

    /**
     * Builds the evening retrospective prompt.
     *
     * @param incompleteTasks  List of task titles that are still pending.
     */
    fun buildEveningRetrospectivePrompt(incompleteTasks: List<String>): String {
        return """
            Generate a brief, friendly evening check-in message for the user.
            They have ${incompleteTasks.size} unfinished tasks today: ${incompleteTasks.joinToString(", ")}.
            
            Ask if they'd like to roll these over to tomorrow or mark them as dropped.
            Keep it to 2 sentences maximum. Natural, conversational speech. No markdown.
        """.trimIndent()
    }

    private fun currentLocalTimestamp(): String {
        val sdf = SimpleDateFormat("EEEE, MMMM d yyyy 'at' h:mm a", Locale.getDefault())
        sdf.timeZone = TimeZone.getDefault()
        return sdf.format(Date())
    }
}
