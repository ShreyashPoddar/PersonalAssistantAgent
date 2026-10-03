package com.paa.assistant.ui.main

import com.paa.assistant.core.privacy.PrivacyGuard
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.paa.assistant.core.ai.GeminiClient
import com.paa.assistant.core.ai.GeminiToolCall
import com.paa.assistant.core.router.ExecutionRoute
import com.paa.assistant.core.router.IntentRouter
import com.paa.assistant.core.router.LocalIntent
import com.paa.assistant.core.router.LocalParseResult
import com.paa.assistant.data.models.TaskEntity
import com.paa.assistant.data.repository.TaskRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

import android.net.Uri
import com.paa.assistant.core.calendar.CalendarManager
import com.paa.assistant.core.location.GeofenceManager
import com.paa.assistant.core.storage.StorageManager
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@HiltViewModel
class MainViewModel @Inject constructor(
    private val repository: TaskRepository,
    private val localExecutor: com.paa.assistant.core.router.LocalCommandExecutor,
    private val localLlm: com.paa.assistant.core.ai.LocalLlm,
    private val speechManager: com.paa.assistant.core.audio.SpeechManager,
    private val router: IntentRouter,
    private val geminiClient: GeminiClient,
    private val storageManager: StorageManager,
    private val calendarManager: CalendarManager,
    private val geofenceManager: GeofenceManager
) : ViewModel() {

    val pendingTasks = repository.observePendingTasks()
    val memoryFacts = repository.observeAllFacts()
    val indexedFiles = repository.observeAllFiles()

    private val _isProcessing = MutableStateFlow(false)
    val isProcessing: StateFlow<Boolean> = _isProcessing.asStateFlow()

    private val _aiResponse = MutableStateFlow<String?>(null)
    val aiResponse: StateFlow<String?> = _aiResponse.asStateFlow()

    fun aiStats(): String = localLlm.lastStats

    // ── VOICE LOCK ENROLMENT ──────────────────────────────────────────────────

    /** Phrases the owner reads: "Oyee P A" with speech after it, like real use, plus normal sentences. */
    val enrolPhrases = listOf(
        "Oyee P A, remind me to call mom at 5",
        "Oyee P A, kal subah 9 baje class hai",
        "Oyee P A, what is my next task",
        "Oyee P A, notes bhej dena yaad dilana",
        "Mera naam Shreyash hai aur main PAA use karta hoon",
        "Tomorrow I have to submit the assignment before evening",
    )

    /** null = not enrolling; else index of the phrase to read (== size: finishing). */
    private val _enrolStep = MutableStateFlow<Int?>(null)
    val enrolStep: StateFlow<Int?> = _enrolStep.asStateFlow()
    private val _enrolStatus = MutableStateFlow("")
    val enrolStatus: StateFlow<String> = _enrolStatus.asStateFlow()
    private val enrolPrints = mutableListOf<FloatArray>()

    fun voiceLockOn(): Boolean = com.paa.assistant.core.audio.VoicePrint.isOn(repository.context)

    fun setVoiceLock(on: Boolean) {
        com.paa.assistant.core.audio.VoicePrint.setOn(repository.context, on)
        restartWakeListener()
    }

    fun startEnrolment() {
        enrolPrints.clear()
        _enrolStatus.value = "Tap Record, then read the sentence aloud in your normal voice."
        _enrolStep.value = 0
        com.paa.assistant.services.WakeListenerService.pauseForPopup(repository.context)  // free the microphone
    }

    fun cancelEnrolment() {
        _enrolStep.value = null
        com.paa.assistant.services.WakeListenerService.resumeAfterPopup(repository.context)
    }

    /** Records 4 s, turns it into a voiceprint (audio is discarded right away). */
    @android.annotation.SuppressLint("MissingPermission")
    fun recordEnrolPhrase() {
        val step = _enrolStep.value ?: return
        viewModelScope.launch {
            _enrolStatus.value = "🎙️ Listening… read it now"
            val ctx = repository.context
            val result = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
                val model = com.paa.assistant.core.audio.VoskEngine(ctx).loadModel() ?: return@withContext null
                val spk = com.paa.assistant.core.audio.VoicePrint.speakerModel(ctx) ?: return@withContext null
                val rate = 16000
                val pcm = ShortArray(rate * 4)
                val rec = android.media.AudioRecord(
                    android.media.MediaRecorder.AudioSource.VOICE_RECOGNITION, rate,
                    android.media.AudioFormat.CHANNEL_IN_MONO, android.media.AudioFormat.ENCODING_PCM_16BIT, rate
                )
                try {
                    rec.startRecording()
                    var got = 0
                    while (got < pcm.size) { val n = rec.read(pcm, got, minOf(1600, pcm.size - got)); if (n <= 0) break; got += n }
                } finally { runCatching { rec.stop() }; rec.release() }
                com.paa.assistant.core.audio.VoicePrint.of(model, spk, pcm)
            }
            val (print, frames) = result ?: (null to 0)
            if (print == null || frames < com.paa.assistant.core.audio.VoicePrint.MIN_FRAMES) {
                _enrolStatus.value = "Didn't hear enough — tap Record and read it again, a bit louder."
                return@launch
            }
            enrolPrints += print
            if (step + 1 < enrolPhrases.size) {
                _enrolStep.value = step + 1
                _enrolStatus.value = "✅ Got it (${step + 1}/${enrolPhrases.size}). Next one:"
            } else {
                val owner = com.paa.assistant.core.audio.VoicePrint.average(enrolPrints)
                val selfCheck = enrolPrints.minOf { com.paa.assistant.core.audio.VoicePrint.similarity(it, owner) }
                com.paa.assistant.core.audio.VoicePrint.save(ctx, owner)
                _enrolStep.value = null
                _aiResponse.value = "🔐 Voice lock is on. \"Oyee PA\" now works only for your voice " +
                    "(your samples matched at %.2f or better; strangers usually score below 0.45).".format(selfCheck)
                restartWakeListener()
            }
        }
    }

    private fun restartWakeListener() {
        val ctx = repository.context
        com.paa.assistant.services.WakeListenerService.stop(ctx)
        com.paa.assistant.services.WakeListenerService.startIfEnabled(ctx)
    }

    /** This app's memory (PSS), as Android counts it when deciding what to kill. */
    fun memoryMb(): Int = runCatching {
        val am = repository.context.getSystemService(android.app.ActivityManager::class.java)
        am.getProcessMemoryInfo(intArrayOf(android.os.Process.myPid()))[0].totalPss / 1024
    }.getOrDefault(-1)

    fun completeTask(taskId: Long) {
        viewModelScope.launch {
            repository.completeTask(taskId)
        }
    }

    fun deleteTask(taskId: Long) {
        viewModelScope.launch {
            repository.deleteTask(taskId)
        }
    }

    fun createOrUpdateTask(task: TaskEntity) {
        viewModelScope.launch {
            if (task.id == 0L) {
                repository.createTask(task)
            } else {
                repository.updateTask(task)
            }
        }
    }

    fun addFact(topic: String, content: String) {
        viewModelScope.launch {
            repository.saveFact(topic, content)
        }
    }

    fun deleteFact(factId: Long) {
        viewModelScope.launch {
            repository.deleteFact(factId)
        }
    }

    fun clearAiResponse() {
        _aiResponse.value = null
    }

    fun indexDirectory(uri: Uri) {
        viewModelScope.launch {
            _isProcessing.value = true
            try {
                val count = storageManager.indexDirectoryTree(uri)
                _aiResponse.value = "Successfully indexed $count file(s) from selected folder into local PAA memory!"
            } catch (e: Exception) {
                _aiResponse.value = "Failed to index folder: ${e.message}"
            } finally {
                _isProcessing.value = false
            }
        }
    }

    private val _localAiInstalled = kotlinx.coroutines.flow.MutableStateFlow(localLlm.installedModelLooksValid())
    val localAiInstalled: kotlinx.coroutines.flow.StateFlow<Boolean> = _localAiInstalled
    private val _importingModel = kotlinx.coroutines.flow.MutableStateFlow(false)
    val importingModel: kotlinx.coroutines.flow.StateFlow<Boolean> = _importingModel

    /** Copies the picked Gemma model file into the app so chats are understood on-device. */
    fun importModel(uri: Uri) {
        viewModelScope.launch {
            _importingModel.value = true
            com.paa.assistant.services.DetectionLog.add("diagnostics", "Install local AI", "⏳ installing… (2–4 min for 3 GB)")
            val error = localLlm.importModel(uri)
            _importingModel.value = false
            _localAiInstalled.value = localLlm.installedModelLooksValid()
            val result = error?.let { "❌ $it" } ?: "✅ Local AI installed. Tap 🔬 Test local AI to check it runs."
            com.paa.assistant.services.DetectionLog.add("diagnostics", "Install local AI", result)
            _aiResponse.value = result
        }
    }

    fun clearChatTasks() {
        viewModelScope.launch {
            val n = repository.deleteAllChatTasks()
            _aiResponse.value = "Removed $n task(s) that were auto-detected from chats."
        }
    }

    /** Loads the on-device model and runs a tiny prompt, logging the outcome (survives a crash). */
    fun testLocalAi() {
        viewModelScope.launch {
            com.paa.assistant.services.DetectionLog.add("diagnostics", "Test local AI", "⏳ testing…")
            _aiResponse.value = "Testing local AI… (first load can take up to a minute)"
            val result = localLlm.selfTest()
            com.paa.assistant.services.DetectionLog.add("diagnostics", "Test local AI", result)
            _aiResponse.value = result
        }
    }

    /** Asks Android to download the on-device English (India) speech model. */
    fun downloadOfflineSpeech() {
        _aiResponse.value = speechManager.downloadOfflineModel()
    }

    fun processCommand(input: String) {
        val trimmed = input.trim()
        if (trimmed.isEmpty()) return

        viewModelScope.launch {
            _isProcessing.value = true
            try {
                when (val route = router.route(trimmed)) {
                    is ExecutionRoute.LocalExecution -> {
                        val response = executeLocally(route.result)
                        _aiResponse.value = response
                    }
                    is ExecutionRoute.CloudEscalation -> {
                        val memoryFacts = repository.getRecentFacts(10)
                        // Chat-derived tasks never leave the device
                        val upcomingTasks = PrivacyGuard.cloudSafe(repository.getUpcomingTasks(5))
                        val taskContext = if (upcomingTasks.isNotEmpty()) {
                            upcomingTasks.joinToString("; ") { task ->
                                task.title + (task.dueTimestamp?.let { " (due: ${SimpleDateFormat("h:mm a", Locale.getDefault()).format(Date(it))})" } ?: "")
                            }
                        } else {
                            "No pending tasks."
                        }

                        val todayEvents = calendarManager.getTodayEvents()
                        val calendarContext = if (todayEvents.isNotEmpty()) {
                            "Today's Calendar Events: " + todayEvents.joinToString("; ") { "${it.title} at ${it.startTime}" + (if (!it.location.isNullOrBlank()) " (${it.location})" else "") }
                        } else {
                            "No calendar events today."
                        }
                        val combinedContext = "$taskContext\n$calendarContext"

                        // Gemini first (~1–2 s); the slower on-device model is the offline fallback
                        val geminiResponse = kotlinx.coroutines.withTimeoutOrNull(10_000) {
                            geminiClient.sendTextQuery(userMessage = trimmed, memoryFacts = memoryFacts, taskContext = combinedContext)
                        }
                        if (geminiResponse == null || geminiResponse.failed) {
                            val local = localExecutor.interpretWithGemma(trimmed)
                            _aiResponse.value = local?.let { executeLocally(it) } ?: geminiResponse?.spokenText ?: "I'm offline and couldn't understand that."
                            return@launch
                        }
                        val toolReply = geminiResponse.toolCall?.let { executeGeminiToolCall(it) }
                        _aiResponse.value = toolReply ?: geminiResponse.spokenText
                    }
                }
            } catch (e: Exception) {
                _aiResponse.value = "Error: ${e.localizedMessage ?: "Unknown error"}"
            } finally {
                _isProcessing.value = false
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
                "Added task: \"${task.title}\""
            }
            LocalIntent.COMPLETE_TASK -> {
                val success = result.taskTitle?.let { repository.completeTaskByTitleFuzzy(it) } ?: false
                if (success) "Marked task as completed!" else "Task not found."
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
                    "Reminder set for ${task.title}."
                } ?: "Could not parse time."
            }
            LocalIntent.DELETE_TASK -> {
                val hint = result.taskTitle ?: result.rawInput
                val deleted = repository.deleteTaskByTitleFuzzy(hint)
                if (deleted) "Deleted task: \"$hint\"" else "Could not find task to delete."
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
            LocalIntent.UNKNOWN -> "I didn't recognize that command."
        }
    }

    /** Executes a Gemini tool call. Returns a reply that replaces the default confirmation, or null. */
    private suspend fun executeGeminiToolCall(toolCall: GeminiToolCall): String? {
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
                    ?: return "I couldn't find a file matching '$filenameHint'. Tap \"Index Device Files\" first."
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

    fun addSampleTasks() {
        viewModelScope.launch {
            val now = System.currentTimeMillis()
            val hourMs = 3600_000L
            repository.createTask(
                TaskEntity(
                    title = "Review PAA Assistant architecture & test Gemini AI",
                    dueTimestamp = now + (2 * hourMs),
                    priority = 3,
                    tags = "#work,#urgent"
                )
            )
            repository.createTask(
                TaskEntity(
                    title = "Team sync meeting & demo",
                    dueTimestamp = now + (5 * hourMs),
                    priority = 2,
                    tags = "#work"
                )
            )
            repository.createTask(
                TaskEntity(
                    title = "Buy groceries & coffee",
                    dueTimestamp = now + (24 * hourMs),
                    priority = 1,
                    tags = "#personal"
                )
            )
            _aiResponse.value = "Added 3 sample tasks for testing!"
        }
    }

    fun addSampleGeofenceTask() {
        viewModelScope.launch {
            val task = TaskEntity(
                title = "Buy whole milk & fresh coffee",
                locationName = "Whole Foods Market",
                latitude = 37.7749, // Default test coordinates (SF)
                longitude = -122.4194,
                geofenceRadiusMeters = 150f,
                geofenceTransition = 1,
                priority = 2,
                tags = "#errand,#location"
            )
            repository.createTask(task)
            _aiResponse.value = "📍 Registered hardware geofence for '${task.title}' at ${task.locationName}!"
        }
    }

    fun testGeminiConnection() {
        viewModelScope.launch {
            _isProcessing.value = true
            try {
                val response = geminiClient.sendTextQuery("Hello PAA! In one short sentence, confirm you are connected and ready to assist.")
                _aiResponse.value = "Gemini AI: ${response.spokenText}"
            } catch (e: Exception) {
                _aiResponse.value = "Gemini Connection Failed: ${e.message}"
            } finally {
                _isProcessing.value = false
            }
        }
    }
}
