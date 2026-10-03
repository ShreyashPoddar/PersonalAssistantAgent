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
    val errorMessage: String = ""
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
    private val claude: com.paa.assistant.core.ai.ClaudeClient
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
            processCommand(captured)
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
                processCommand(event.text)
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
                    processCommand(captured)
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
    fun processCommand(input: String, localOnly: Boolean = false) {
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
                    val task = TaskEntity(
                        title = result.taskTitle ?: "Alarm",
                        dueTimestamp = ts,
                        priority = 2
                    )
                    repository.createTask(task)
                    "Alarm set! I'll remind you at the scheduled time."
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
