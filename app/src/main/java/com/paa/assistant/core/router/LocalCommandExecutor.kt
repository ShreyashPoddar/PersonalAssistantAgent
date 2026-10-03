package com.paa.assistant.core.router

import com.paa.assistant.core.ai.LocalLlm
import com.paa.assistant.core.location.GeofenceManager
import com.paa.assistant.core.location.SavedPlaces
import com.paa.assistant.data.models.TaskEntity
import com.paa.assistant.data.repository.TaskRepository
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/**
 * On-device handling for commands that read the user's task list or places.
 * Shared by the main screen and the voice overlay. Nothing here touches the network.
 */
@Singleton
class LocalCommandExecutor @Inject constructor(
    private val repository: TaskRepository,
    private val geofenceManager: GeofenceManager,
    private val savedPlaces: SavedPlaces,
    private val localLlm: LocalLlm
) {
    private val timeFmt = SimpleDateFormat("h:mm a", Locale.getDefault())
    private val dayTimeFmt = SimpleDateFormat("EEE h:mm a", Locale.getDefault())

    /** "What tasks do I have by tonight?" → full list with times. */
    suspend fun listTasks(cutoff: Long?): String {
        val tasks = pendingTasks(cutoff)
        if (tasks.isEmpty()) {
            return if (cutoff != null) "Nothing due by then. You're clear! 🎉" else "You have no pending tasks."
        }
        val header = if (cutoff != null) "You have ${tasks.size} task(s) due by ${describeCutoff(cutoff)}:" else "Your ${tasks.size} pending task(s):"
        return header + "\n" + tasks.joinToString("\n") { "• ${describe(it)}" }
    }

    /** "How/when can I finish everything tonight?" → a time plan from on-device Gemma. */
    suspend fun planTasks(cutoff: Long?): String {
        val limit = cutoff ?: endOfToday()
        val tasks = pendingTasks(limit)
        if (tasks.isEmpty()) return "Nothing due by ${describeCutoff(limit)}. Nothing to plan! 🎉"

        val now = System.currentTimeMillis()
        val list = tasks.joinToString("\n") { "- ${describe(it)}" }
        val prompt = """
You are a personal assistant planning the user's time. Current time: ${timeFmt.format(Date(now))}.
Tasks to finish by ${describeCutoff(limit)} (overdue ones first):
$list
Make a short, realistic plan: order the tasks, give each a time slot starting from now with estimated durations,
and add 5-minute breaks. Put overdue and urgent tasks first. Use plain text lines like "7:30–8:15 PM: task". Max 10 lines.
""".trimIndent()

        return localLlm.generate(prompt)?.takeIf { it.isNotBlank() }
            ?: fallbackPlan(tasks, now)
    }

    /**
     * Understands a voice/typed command the regex parser couldn't (e.g. free-form Hinglish) using
     * on-device Gemma, so it doesn't have to go to Gemini. Returns null if Gemma isn't installed,
     * fails, or says the request needs the internet — then the caller may escalate to Gemini.
     */
    suspend fun interpretWithGemma(input: String): LocalParseResult? {
        val now = SimpleDateFormat("EEEE, yyyy-MM-dd HH:mm", Locale.ENGLISH).format(Date())
        val prompt = """
You convert a personal-assistant command (English, Hindi or Hinglish) into JSON. Current date and time: $now
Reply with ONLY one JSON object:
{"intent": "create" | "complete" | "delete" | "list" | "plan" | "needs_internet", "title": "short task in English", "day": "today" | "tomorrow" | "monday".."sunday" | "YYYY-MM-DD" | null, "time": "HH:MM" (24-hour) | null, "in_minutes": number | null, "place": "home" | "office" | "college" | ... | null, "on_leave": true/false}
- create: add a task/reminder. complete: mark a task done. delete: remove a task. list: which tasks are pending/due. plan: how/when to finish tasks.
- needs_internet: anything needing live info or general knowledge (weather, news, facts, search, prices, explanations).
- "place" only for reminders triggered on reaching/leaving a place ("ghar pahunch ke" = reaching home). on_leave = true for leaving.

Command: "kal subah 8 baje doodh lana yaad dilana"
{"intent": "create", "title": "Buy milk", "day": "tomorrow", "time": "08:00", "in_minutes": null, "place": null, "on_leave": false}
Command: "ghar pahunch ke laundry nikalni hai"
{"intent": "create", "title": "Take out laundry", "day": null, "time": null, "in_minutes": null, "place": "home", "on_leave": false}
Command: "doodh wala kaam ho gaya"
{"intent": "complete", "title": "Buy milk", "day": null, "time": null, "in_minutes": null, "place": null, "on_leave": false}
Command: "aaj mausam kaisa hai"
{"intent": "needs_internet", "title": "", "day": null, "time": null, "in_minutes": null, "place": null, "on_leave": false}

Command: "${input.replace("\"", "'")}"
""".trimIndent()

        val raw = localLlm.generate(prompt) ?: return null
        return try {
            val json = org.json.JSONObject(Regex("\\{[^{}]*\\}").find(raw)?.value ?: return null)
            fun str(k: String) = if (json.isNull(k)) null else json.optString(k).takeIf { it.isNotBlank() && it != "null" }
            val title = str("title")
            val timestamp = json.optInt("in_minutes", 0).takeIf { it > 0 }?.let { System.currentTimeMillis() + it * 60_000L }
                ?: localLlm.resolveTimestamp(str("day"), str("time"))
            val intent = when (str("intent")) {
                "create" -> LocalIntent.CREATE_TASK
                "complete" -> LocalIntent.COMPLETE_TASK
                "delete" -> LocalIntent.DELETE_TASK
                "list" -> LocalIntent.LIST_TASKS
                "plan" -> LocalIntent.PLAN_TASKS
                else -> return null  // needs_internet / unknown → Gemini
            }
            if (intent in setOf(LocalIntent.CREATE_TASK, LocalIntent.COMPLETE_TASK, LocalIntent.DELETE_TASK) && title == null) return null
            LocalParseResult(
                intent = intent,
                taskTitle = title,
                timestamp = if (intent == LocalIntent.LIST_TASKS || intent == LocalIntent.PLAN_TASKS) {
                    str("day")?.let { localLlm.resolveTimestamp(it, "23:59") }
                } else timestamp,
                confidence = 0.9f,
                rawInput = input,
                locationName = str("place"),
                locationTransition = if (json.optBoolean("on_leave", false)) 2 else 1
            )
        } catch (e: Exception) {
            null
        }
    }

    /** "Save this location as home" → stores the current GPS position. */
    suspend fun savePlace(name: String?): String {
        if (name == null) return "Which place is this? Say e.g. \"save this location as home\"."
        val here = geofenceManager.getLastKnownLocation()
            ?: return "I couldn't get your location. Turn on location and allow PAA to use it, then try again."
        savedPlaces.save(name, here.first, here.second)
        return "Saved your current location as ${savedPlaces.normalize(name)}. Now you can say \"remind me to … when I reach ${savedPlaces.normalize(name)}\"."
    }

    /** "Remind me to X when I reach home" → geofence task. */
    suspend fun createLocationTask(result: LocalParseResult): String {
        val place = result.locationName ?: return "Where should I remind you?"
        val coords = savedPlaces.get(place)
            ?: return "I don't know where your ${savedPlaces.normalize(place)} is yet. When you're there, say \"save this location as ${savedPlaces.normalize(place)}\"."
        val title = result.taskTitle ?: result.rawInput
        repository.createTask(
            TaskEntity(
                title = title,
                priority = result.priority,
                tags = result.tags.joinToString(","),
                locationName = savedPlaces.normalize(place),
                latitude = coords.first,
                longitude = coords.second,
                geofenceTransition = result.locationTransition
            )
        )
        val verb = if (result.locationTransition == 2) "leave" else "reach"
        return "Got it! I'll remind you to \"$title\" when you $verb ${savedPlaces.normalize(place)}."
    }

    private suspend fun pendingTasks(cutoff: Long?): List<TaskEntity> =
        repository.getUpcomingTasks(100)
            .filter { cutoff == null || (it.dueTimestamp != null && it.dueTimestamp <= cutoff) }
            .sortedWith(compareBy<TaskEntity> { it.dueTimestamp ?: Long.MAX_VALUE }.thenByDescending { it.priority })

    private fun describe(task: TaskEntity): String {
        val due = task.dueTimestamp ?: return task.title + (task.locationName?.let { " (at $it)" } ?: "")
        val overdue = due < System.currentTimeMillis()
        val fmt = if (isToday(due)) timeFmt else dayTimeFmt
        return "${fmt.format(Date(due))} — ${task.title}" + if (overdue) " ⚠️ overdue" else ""
    }

    private fun describeCutoff(cutoff: Long): String =
        if (isToday(cutoff)) "tonight" else "end of " + SimpleDateFormat("EEEE", Locale.getDefault()).format(Date(cutoff))

    private fun isToday(ts: Long): Boolean {
        val a = Calendar.getInstance()
        val b = Calendar.getInstance().apply { timeInMillis = ts }
        return a.get(Calendar.YEAR) == b.get(Calendar.YEAR) && a.get(Calendar.DAY_OF_YEAR) == b.get(Calendar.DAY_OF_YEAR)
    }

    private fun endOfToday(): Long = Calendar.getInstance().apply {
        set(Calendar.HOUR_OF_DAY, 23); set(Calendar.MINUTE, 59); set(Calendar.SECOND, 59)
    }.timeInMillis

    /** Used if the on-device model isn't installed: deadline order, 45 min per task. */
    private fun fallbackPlan(tasks: List<TaskEntity>, now: Long): String {
        var t = now
        return "Suggested order (by deadline):\n" + tasks.joinToString("\n") { task ->
            val start = t
            t += 45 * 60_000L
            "${timeFmt.format(Date(start))}–${timeFmt.format(Date(t))}: ${task.title}".also { t += 5 * 60_000L }
        }
    }
}
