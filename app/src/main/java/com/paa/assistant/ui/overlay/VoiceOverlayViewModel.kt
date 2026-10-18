package com.paa.assistant.ui.overlay

import com.paa.assistant.core.privacy.PrivacyGuard
import android.net.Uri
import android.speech.SpeechRecognizer
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.paa.assistant.core.ai.GeminiClient
import com.paa.assistant.core.audio.SpeechEvent
import com.paa.assistant.core.audio.SpeechManager
import com.paa.assistant.core.calendar.CalendarManager
import com.paa.assistant.core.location.GeofenceManager
import com.paa.assistant.core.router.ExecutionRoute
import com.paa.assistant.core.router.IntentRouter
import com.paa.assistant.core.router.LocalIntent
import com.paa.assistant.core.router.LocalParseResult
import com.paa.assistant.core.router.LocalTaskParser
import com.paa.assistant.core.storage.StorageManager
import com.paa.assistant.data.models.TaskEntity
import com.paa.assistant.data.repository.TaskRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

enum class AssistantState {
    IDLE,           // Mic is off, no activity
    LISTENING,      // Actively recording user speech
    PROCESSING,     // Routing / calling local engine or Gemini
    SPEAKING,       // TTS is playing the response
    ERROR           // Something went wrong
}

data class VoiceUiState(
    val state: AssistantState = AssistantState.IDLE,
    val partialText: String = "",           // Live partial speech transcription
    val finalUserText: String = "",         // Finalized user utterance
    val responseText: String = "",          // PAA's response to display
    val errorMessage: String = "",
    /** A message waiting for the owner's OK: shown with Send / Cancel. */
    val pendingSend: PendingSend? = null
)

data class PendingSend(
    val contact: com.paa.assistant.core.messaging.Contact, val text: String,
    val alternatives: List<com.paa.assistant.core.messaging.Contact> = emptyList(),
    /** Who asked: spoken (OWNER_VOICE) or typed (OWNER_UI), and whether the voice was the owner's. */
    val source: com.paa.assistant.core.agent.CommandSource = com.paa.assistant.core.agent.CommandSource.OWNER_VOICE,
    val voiceVerified: Boolean = false
)

/**
 * VoiceOverlayViewModel — the brain behind the voice assistant UI.
 *
 * Orchestrates the full voice command pipeline:
 *  1. SpeechManager.startListening() → receive SpeechEvents
 *  2. On Final speech → run IntentRouter.route()
 *  3. LocalExecution → execute task in TaskRepository → confirm via TTS
 *  4. CloudEscalation → send to GeminiClient → execute returned tool call → respond via TTS
 *  5. Multimodal SAF & WhatsApp file analysis
 */
@HiltViewModel
class VoiceOverlayViewModel @Inject constructor(
    private val speechManager: SpeechManager,
    private val router: IntentRouter,
    private val parser: LocalTaskParser,
    private val repository: TaskRepository,
    private val localExecutor: com.paa.assistant.core.router.LocalCommandExecutor,
    private val geminiClient: GeminiClient,
    private val storageManager: StorageManager,
    private val calendarManager: CalendarManager,
    private val geofenceManager: GeofenceManager,
    private val claude: com.paa.assistant.core.ai.ClaudeClient,
    private val executor: com.paa.assistant.core.agent.Executor
) : ViewModel() {

    private val tag = "VoiceOverlayVM"

    /** Last turns of this popup session (non-private only), sent to Gemini as conversation history. */
    private val turns = ArrayDeque<Pair<String, String>>()
    private var lastInputPrivate = false

    /** Opened by voice: after each reply, listen again briefly for a follow-up ("no, make it 7"). */
    var followUpEnabled = false
    private var inFollowUp = false

    private val _uiState = MutableStateFlow(VoiceUiState())
    val uiState: StateFlow<VoiceUiState> = _uiState.asStateFlow()

    init {
        // Each popup gets a fresh chance at Android's recognizer (one earlier error shouldn't stick)
        speechManager.resetEngineChoice()
        // Observe speech events from the mic
        viewModelScope.launch {
            speechManager.speechEvents.collect { event ->
                handleSpeechEvent(event)
            }
        }
    }

    fun startListening(keepResponse: Boolean = false) {
        speechManager.stopSpeaking()
        _uiState.value = _uiState.value.copy(
            state = AssistantState.LISTENING,
            partialText = "",
            finalUserText = "",
            responseText = if (keepResponse) _uiState.value.responseText else "",
            errorMessage = ""
        )
        speechManager.startListening()
    }

    fun stopListening() {
        speechManager.stopListening()
        _uiState.value = _uiState.value.copy(state = AssistantState.IDLE)
    }

    /**
     * Called when the user taps the mic button while listening.
     * If speech has already been transcribed, commits and executes it immediately.
     */
    fun commitCurrentSpeech() {
        val captured = _uiState.value.partialText.ifBlank { speechManager.commitOrStop() ?: "" }
        if (captured.isNotBlank()) {
            speechManager.stopListening()
            _uiState.value = _uiState.value.copy(
                state = AssistantState.PROCESSING,
                finalUserText = captured,
                partialText = ""
            )
            processCommand(captured, spoken = true)
        } else {
            stopListening()
        }
    }

    /**
     * Handle incoming shared files or text from WhatsApp or other apps.
     */
    fun handleSharedContent(uriString: String?, sharedText: String?) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(state = AssistantState.PROCESSING)

            if (!uriString.isNullOrBlank()) {
                val uri = Uri.parse(uriString)
                val (filename, mime) = storageManager.getFileMetadata(uri)
                _uiState.value = _uiState.value.copy(finalUserText = "Analyzing shared document: $filename")

                val bytes = storageManager.readFileBytes(uri)
                if (bytes != null) {
                    val prompt = "Analyze this shared file from WhatsApp ($filename). Summarize key details and extract any actionable tasks, deadlines, or dates."
                    val memoryFacts = repository.getRecentFacts(10)
                    val response = geminiClient.sendFileQuery(prompt, bytes, mime, memoryFacts)
                    response.toolCall?.let { executeGeminiToolCall(it) }
                    deliverResponse(response.spokenText)
                } else {
                    deliverResponse("I couldn't read the attached file from WhatsApp.")
                }
            } else if (!sharedText.isNullOrBlank()) {
                processCommand(sharedText)
            }
        }
    }

    private fun handleSpeechEvent(event: SpeechEvent) {
        when (event) {
            is SpeechEvent.ListeningStarted -> {
                _uiState.value = _uiState.value.copy(state = AssistantState.LISTENING)
            }
            is SpeechEvent.Partial -> {
                _uiState.value = _uiState.value.copy(partialText = event.text)
            }
            is SpeechEvent.Final -> {
                inFollowUp = false
                _uiState.value = _uiState.value.copy(
                    state = AssistantState.PROCESSING,
                    finalUserText = event.text,
                    partialText = ""
                )
                processCommand(event.text, spoken = true)
            }
            is SpeechEvent.ListeningStopped -> { /* handled by Final event or debounce */ }
            is SpeechEvent.Error -> {
                val captured = _uiState.value.partialText
                if (captured.isNotBlank()) {
                    Log.d(tag, "Recovered from speech error using partialText: $captured")
                    _uiState.value = _uiState.value.copy(
                        state = AssistantState.PROCESSING,
                        finalUserText = captured,
                        partialText = "",
                        errorMessage = ""
                    )
                    processCommand(captured, spoken = true)
                    return
                }

                // Silence after a reply just ends the follow-up window — not an error
                if (inFollowUp) {
                    inFollowUp = false
                    _uiState.value = _uiState.value.copy(state = AssistantState.IDLE, errorMessage = "")
                    return
                }
                if (event.isRecoverable) {
                    _uiState.value = _uiState.value.copy(
                        state = AssistantState.IDLE,
                        errorMessage = event.message
                    )
                } else {
                    _uiState.value = _uiState.value.copy(
                        state = AssistantState.ERROR,
                        errorMessage = event.message
                    )
                }
            }
        }
    }

    /** @param localOnly true for text derived from private chats: never escalate to the cloud. */
    /** How the current command came in (see [PendingSend.source]). */
    private var commandSource = com.paa.assistant.core.agent.CommandSource.OWNER_VOICE
    private var commandVoiceVerified = false

    fun processCommand(input: String, localOnly: Boolean = false, spoken: Boolean = false) {
        if (_uiState.value.pendingSend != null) {
            val said = input.trim().lowercase()
            if (Regex("^(send|send it|yes|haan|ha|bhej do|bhejo|bhej de|ok send)\\b").containsMatchIn(said)) { confirmSend(); return }
            if (Regex("^(cancel|no|nahi|mat bhejo|don'?t)\\b").containsMatchIn(said)) { cancelSend(); return }
        }
        val now = System.currentTimeMillis()
        val wake = com.paa.assistant.services.WakeListenerService
        val locked = repository.context.getSystemService(android.app.KeyguardManager::class.java).isKeyguardLocked
        // Spoken into a popup the owner opened by touch on an unlocked phone = as good as typed. Hands-free
        // ("Oyee PA") or from the lock screen = anyone near the phone could have said it.
        val handsFree = now - wake.wakeOpenedAt < 60_000L
        commandSource = if (spoken && (handsFree || locked)) com.paa.assistant.core.agent.CommandSource.OWNER_VOICE
            else com.paa.assistant.core.agent.CommandSource.OWNER_UI
        // Spoken within a minute of an "Oyee PA" that matched the owner's voiceprint
        commandVoiceVerified = spoken && now - wake.verifiedWakeAt < 60_000L
        // Owner's screen-reading switch ("banking mode") — a local setting, not an Action
        com.paa.assistant.core.shield.A11yToggle.voiceCommand(input)?.let { on ->
            _uiState.value = _uiState.value.copy(finalUserText = input, partialText = "", errorMessage = "")
            deliverResponse(com.paa.assistant.core.shield.A11yToggle.applyVoice(repository.context, on))
            return
        }
        lastInputPrivate = localOnly
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(
                state = AssistantState.PROCESSING,
                finalUserText = input,
                partialText = "",
                errorMessage = ""
            )

            val route = if (localOnly) {
                ExecutionRoute.LocalExecution(parser.parse(input))
            } else {
                router.route(input)
            }
            when (route) {
                is ExecutionRoute.LocalExecution -> {
                    Log.i(tag, "Local execution: ${route.result.intent}")
                    val response = executeLocally(route.result)
                    deliverResponse(response)
                }
                is ExecutionRoute.CloudEscalation -> {
                    Log.i(tag, "Cloud escalation: ${route.reason}")
                    val memoryFacts = repository.getRecentFacts(10)
                    // Chat-derived tasks never leave the device
                    val upcomingTasks = PrivacyGuard.cloudSafe(repository.getUpcomingTasks(5))
                    val taskSummary = if (upcomingTasks.isNotEmpty()) {
                        upcomingTasks.joinToString("; ") { task ->
                            task.title + (task.dueTimestamp?.let { " (due: ${java.text.SimpleDateFormat("h:mm a", java.util.Locale.getDefault()).format(java.util.Date(it))})" } ?: "")
                        }
                    } else {
                        "No pending tasks."
                    }

                    val todayEvents = calendarManager.getTodayEvents()
                    val calendarSummary = if (todayEvents.isNotEmpty()) {
                        "Today's Calendar Events: " + todayEvents.joinToString("; ") { "${it.title} at ${it.startTime}" + (if (!it.location.isNullOrBlank()) " (${it.location})" else "") }
                    } else {
                        "No calendar events today."
                    }
                    val combinedContext = "$taskSummary\n$calendarSummary"

                    // Heavy, non-private requests (plans, comparisons, write-ups) → Claude, if configured
                    if (claude.isConfigured && com.paa.assistant.core.ai.ClaudeClient.isHeavy(input)) {
                        _uiState.value = _uiState.value.copy(partialText = "Thinking it through…")
                        claude.ask(input, combinedContext, deep = com.paa.assistant.core.ai.ClaudeClient.wantsDeepest(input))?.let {
                            deliverResponse(it)
                            return@launch
                        }
                    }
                    // Gemini first: ~1–2 s. The on-device model takes far longer on this phone and may be
                    // busy with a call recording, so it's only the offline fallback.
                    val geminiResponse = kotlinx.coroutines.withTimeoutOrNull(10_000) {
                        geminiClient.sendTextQuery(userMessage = input, memoryFacts = memoryFacts, taskContext = combinedContext, history = turns.toList())
                    }
                    if (geminiResponse == null || geminiResponse.failed) {
                        val local = localExecutor.interpretWithGemma(input)
                        deliverResponse(local?.let { executeLocally(it) } ?: geminiResponse?.spokenText ?: "I'm offline and couldn't understand that.")
                        return@launch
                    }

                    // If Gemini returned a function call, execute it locally; some tools produce their own reply
                    val toolReply = geminiResponse.toolCall?.let { toolCall ->
                        executeGeminiToolCall(toolCall)
                    }

                    deliverResponse(toolReply ?: geminiResponse.spokenText)
                }
            }
        }
    }

    private suspend fun executeLocally(result: LocalParseResult): String {
        return when (result.intent) {
            LocalIntent.CREATE_TASK -> if (result.locationName != null) localExecutor.createLocationTask(result) else {
                val task = TaskEntity(
                    title = result.taskTitle ?: result.rawInput,
                    dueTimestamp = result.timestamp,
                    priority = result.priority,
                    tags = result.tags.joinToString(","),
                    recurrenceRule = result.recurrenceRule
                )
                repository.createTask(task)
                buildConfirmation(result)
            }
            LocalIntent.COMPLETE_TASK -> {
                val success = result.taskTitle?.let { repository.completeTaskByTitleFuzzy(it) } ?: false
                if (success) "Done! I've marked that as completed."
                else "I couldn't find a matching task. Could you be more specific?"
            }
            LocalIntent.LIST_TASKS -> localExecutor.listTasks(result.timestamp)
            LocalIntent.QUERY_AGENDA -> localExecutor.listTasks(result.timestamp)
            LocalIntent.SET_TIMER -> {
                result.timestamp?.let { ts ->
                    executor.submit(
                        com.paa.assistant.core.agent.Action.SetAlarm(ts, result.taskTitle ?: "Alarm"),
                        commandSource, voiceVerified = commandVoiceVerified
                    ).message
                } ?: "I couldn't understand the time. Please try again."
            }
            LocalIntent.DELETE_TASK -> {
                val titleHint = result.taskTitle ?: result.rawInput
                val deleted = repository.deleteTaskByTitleFuzzy(titleHint)
                if (deleted) "I've deleted '$titleHint' from your tasks."
                else "I couldn't find a task matching '$titleHint' to remove."
            }
            LocalIntent.PLAN_TASKS -> localExecutor.planTasks(result.timestamp)
            LocalIntent.RESCHEDULE_TASK -> {
                val due = result.timestamp
                val moved = due?.let { repository.rescheduleTask(result.taskTitle, it) }
                when {
                    due == null -> "What time should I move it to?"
                    moved == null -> "I couldn't find that task to move."
                    else -> "Moved \"${moved.title}\" to " +
                        java.text.SimpleDateFormat("h:mm a, MMM d", java.util.Locale.getDefault()).format(java.util.Date(due)) + "."
                }
            }
            LocalIntent.SAVE_PLACE -> localExecutor.savePlace(result.locationName)
            LocalIntent.SEND_MESSAGE -> if (result.taskTitle == null) prepareBareMessage(result.recipient ?: "")
                else prepareMessage(result.recipient ?: "", result.taskTitle)
            LocalIntent.UNKNOWN -> "I didn't quite catch that. Could you rephrase?"
        }
    }

    /** Executes a Gemini tool call. Returns a reply that replaces the default confirmation, or null. */
    private suspend fun executeGeminiToolCall(toolCall: com.paa.assistant.core.ai.GeminiToolCall): String? {
        when (toolCall.functionName) {
            "create_task" -> {
                val title = toolCall.args.optString("title", "Untitled task")
                val priority = when (toolCall.args.optString("priority", "MEDIUM").uppercase()) {
                    "URGENT" -> 3; "HIGH" -> 2; "LOW" -> 0; else -> 1
                }
                val due = toolCall.args.optString("due_timestamp_iso", "")
                    .takeIf { it.isNotBlank() }?.let { calendarManager.parseIsoToMillis(it) }
                repository.createTask(
                    TaskEntity(
                        title = title,
                        priority = priority,
                        dueTimestamp = due,
                        reminderMinutesBefore = toolCall.args.optInt("reminder_minutes_before", 0)
                    )
                )
            }
            "create_location_reminder" -> {
                val title = toolCall.args.optString("title", "Location Reminder")
                val locationName = toolCall.args.optString("location_name", "")
                val transitionStr = toolCall.args.optString("transition", "ENTER").uppercase()
                val transition = if (transitionStr == "EXIT") 2 else 1
                val radius = toolCall.args.optDouble("radius_meters", 150.0).toFloat()

                var lat: Double? = if (toolCall.args.has("latitude")) toolCall.args.optDouble("latitude") else null
                var lng: Double? = if (toolCall.args.has("longitude")) toolCall.args.optDouble("longitude") else null

                if (lat == null && locationName.isNotBlank()) {
                    val coords = geofenceManager.resolveCoordinates(locationName)
                    lat = coords?.first
                    lng = coords?.second
                }

                if (lat == null) {
                    val fallback = geofenceManager.getLastKnownLocation()
                    lat = fallback?.first
                    lng = fallback?.second
                }

                val task = TaskEntity(
                    title = title,
                    locationName = locationName.ifBlank { null },
                    latitude = lat,
                    longitude = lng,
                    geofenceRadiusMeters = radius,
                    geofenceTransition = transition,
                    priority = 2
                )
                repository.createTask(task)
            }
            "reschedule_task" -> {
                val due = toolCall.args.optString("new_due_iso", "").takeIf { it.isNotBlank() }
                    ?.let { calendarManager.parseIsoToMillis(it) } ?: return "What time should I move it to?"
                val moved = repository.rescheduleTask(toolCall.args.optString("task_title_hint", "").ifBlank { null }, due)
                    ?: return "I couldn't find that task to move."
                return "Moved \"${moved.title}\" to " +
                    java.text.SimpleDateFormat("h:mm a, MMM d", java.util.Locale.getDefault()).format(java.util.Date(due)) + "."
            }
            "update_task_status" -> {
                val taskId = if (toolCall.args.has("task_id")) toolCall.args.optLong("task_id", -1L) else -1L
                val titleHint = toolCall.args.optString("task_title_hint", "")
                val status = toolCall.args.optString("status", "COMPLETED").uppercase()

                if (status == "CANCELLED" || status == "DELETED") {
                    if (taskId != -1L) {
                        repository.deleteTask(taskId)
                    } else if (titleHint.isNotBlank()) {
                        repository.deleteTaskByTitleFuzzy(titleHint)
                    }
                } else {
                    if (taskId != -1L) {
                        repository.completeTask(taskId)
                    } else if (titleHint.isNotBlank()) {
                        repository.completeTaskByTitleFuzzy(titleHint)
                    }
                }
            }
            "schedule_calendar_event" -> {
                val title = toolCall.args.optString("title", "Scheduled Event")
                val startTime = toolCall.args.optString("start_time_iso", "")
                val endTime = if (toolCall.args.has("end_time_iso")) toolCall.args.optString("end_time_iso") else null
                val location = if (toolCall.args.has("location")) toolCall.args.optString("location") else null
                val notes = if (toolCall.args.has("notes")) toolCall.args.optString("notes") else null
                calendarManager.addEvent(title, startTime, endTime, location, notes)
            }
            "read_device_file" -> {
                val filenameHint = toolCall.args.optString("filename_hint", "")
                if (filenameHint.isBlank()) return null
                val file = storageManager.findFileByName(filenameHint)
                    ?: return "I couldn't find a file matching '$filenameHint'. Tap \"Index Device Files\" in the app first."
                val bytes = storageManager.readFileBytes(Uri.parse(file.uriString))
                    ?: return "I found ${file.fileName} but couldn't read it."
                return geminiClient.sendFileQuery(
                    "Summarize this file (${file.fileName}) and list key takeaways.",
                    bytes,
                    file.mimeType
                ).spokenText
            }
            "save_personal_fact" -> {
                val topic = toolCall.args.optString("topic", "General")
                val factContent = toolCall.args.optString("fact_content", "")
                if (factContent.isNotBlank()) {
                    repository.saveFact(topic, factContent)
                }
            }
            "search_web_grounded" -> {
                val query = toolCall.args.optString("query", "")
                if (query.isNotBlank()) return geminiClient.searchWeb(query)
            }
        }
        return null
    }

    /** Finds the contact and shows the exact message for confirmation — nothing is sent yet. */
    private fun prepareMessage(who: String, text: String): String {
        val ctx = repository.context
        if (!com.paa.assistant.core.messaging.ContactResolver.hasPermission(ctx)) {
            _uiState.value = _uiState.value.copy(errorMessage = "PAA needs Contacts permission to find $who — allow it, then ask again.")
            return "I need access to your contacts to find $who."
        }
        var matches = com.paa.assistant.core.messaging.ContactResolver.find(ctx, who)
        // Two words heard ("Riya ECE") but saved as just "Riya": try the first word.
        if (matches.isEmpty() && who.contains(' ')) matches = com.paa.assistant.core.messaging.ContactResolver.find(ctx, who.substringBefore(' '))
        if (matches.isEmpty()) {
            Log.i(tag, "send: no contact match")
            return "I couldn't find \"$who\" in your contacts."
        }
        Log.i(tag, "send: confirm popup shown (${matches.size} match(es))")
        _uiState.value = _uiState.value.copy(
            pendingSend = PendingSend(matches.first(), text, matches.drop(1).take(3), commandSource, commandVoiceVerified)
        )
        return "Send to ${matches.first().name}?\n\"$text\"" + if (matches.size > 1) "\n(Other matches below.)" else ""
    }

    /** "message Riya Sharma test": the longest leading words (up to 3) that match a saved contact are the name. */
    private fun prepareBareMessage(rest: String): String {
        val ctx = repository.context
        val words = rest.split(Regex("\\s+"))
        if (com.paa.assistant.core.messaging.ContactResolver.hasPermission(ctx)) {
            for (n in minOf(3, words.size - 1) downTo 1) {
                val name = words.take(n).map { it.lowercase() }
                val hit = com.paa.assistant.core.messaging.ContactResolver.find(ctx, name.joinToString(" ")).any { c ->
                    val parts = c.name.lowercase().split(Regex("\\s+"))
                    name.all { w -> parts.any { it.startsWith(w) } }
                }
                if (hit) return prepareMessage(words.take(n).joinToString(" "), words.drop(n).joinToString(" ").replaceFirstChar { it.uppercase() })
            }
        }
        return prepareMessage(words.first(), words.drop(1).joinToString(" ").replaceFirstChar { it.uppercase() })
    }

    /** The owner tapped Send (or said "send"/"bhej do"). */
    fun confirmSend(contact: com.paa.assistant.core.messaging.Contact? = null) {
        val p = _uiState.value.pendingSend ?: return
        val to = contact ?: p.contact
        _uiState.value = _uiState.value.copy(pendingSend = null)
        // The popup already showed the exact text and the owner tapped Send, so it counts as previewed.
        viewModelScope.launch {
            val result = executor.submit(
                com.paa.assistant.core.agent.Action.SendMessage("WhatsApp", to.phoneE164, to.name, p.text),
                p.source, preConfirmed = true, voiceVerified = p.voiceVerified
            )
            deliverResponse(if (result.ok || result.message.startsWith("I've already")) result.message else "Not sent: ${result.message}")
        }
    }

    fun cancelSend() {
        _uiState.value = _uiState.value.copy(pendingSend = null)
        deliverResponse("Okay, not sent.")
    }

    // Replies are shown as text in the popup only — never read aloud (user preference: privacy)
    fun speakText(text: String) {
        _uiState.value = _uiState.value.copy(
            state = AssistantState.IDLE,
            responseText = text,
            finalUserText = ""
        )
    }

    private fun deliverResponse(text: String) {
        val asked = _uiState.value.finalUserText
        if (asked.isNotBlank() && !lastInputPrivate) {
            turns.addLast(asked to text)
            while (turns.size > 6) turns.removeFirst()
        }
        _uiState.value = _uiState.value.copy(
            state = AssistantState.IDLE,
            responseText = text
        )
        if (followUpEnabled) viewModelScope.launch {
            kotlinx.coroutines.delay(700)
            if (_uiState.value.state == AssistantState.IDLE) {
                inFollowUp = true
                startListening(keepResponse = true)
            }
        }
    }

    private fun buildConfirmation(result: LocalParseResult): String {
        val title = result.taskTitle ?: "your task"
        return if (result.timestamp != null) {
            val formattedTime = java.text.SimpleDateFormat("h:mm a", java.util.Locale.getDefault()).format(java.util.Date(result.timestamp))
            "Got it! I've scheduled '$title' for $formattedTime."
        } else {
            "Added '$title' to your tasks."
        }
    }

    /** Popup closed. SpeechManager is app-wide, so only stop it — releasing killed speech for later popups. */
    fun release() {
        speechManager.stopListening()
        speechManager.stopSpeaking()
    }
}
