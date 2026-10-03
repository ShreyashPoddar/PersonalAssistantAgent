package com.paa.assistant.workers

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.paa.assistant.core.calendar.CalendarManager
import com.paa.assistant.data.db.AppDatabase
import com.paa.assistant.ui.main.MainActivity
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * MorningBriefingWorker — generates a proactive morning agenda summary at 7:00 AM.
 * Evaluates today's pending tasks, overdue items, calendar events, and synthesizes
 * a briefing and a time plan with the on-device model (all tasks, nothing leaves the phone).
 */
@HiltWorker
class MorningBriefingWorker @AssistedInject constructor(
    @Assisted private val context: Context,
    @Assisted workerParams: WorkerParameters,
    private val database: AppDatabase,
    private val calendarManager: CalendarManager,
    private val localExecutor: com.paa.assistant.core.router.LocalCommandExecutor
) : CoroutineWorker(context, workerParams) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        try {
            val overdue = database.taskDao().getOverdueTasks()
            val allUpcoming = database.taskDao().getNextTasks(limit = 10)
            val calendarEvents = calendarManager.getTodayEvents()

            val calendarSummary = if (calendarEvents.isNotEmpty()) {
                calendarEvents.joinToString("; ") { event ->
                    "${event.title} (at ${SimpleDateFormat("h:mm a", Locale.getDefault()).format(Date(event.startMillis))})"
                }
            } else {
                "No calendar events today."
            }

            // Planned on the phone (Gemma) with ALL tasks incl. private chat ones; nothing goes to the cloud
            val plan = runCatching { localExecutor.planTasks(null) }.getOrNull()
            val summary = buildString {
                append(buildFallbackSummary(overdue.size, allUpcoming.size))
                if (calendarEvents.isNotEmpty()) append("\nCalendar: ").append(calendarSummary)
                if (!plan.isNullOrBlank() && !plan.startsWith("Nothing")) append("\n\n").append(plan)
            }

            postBriefingNotification("☀️ Morning Executive Briefing", summary)
            Result.success()
        } catch (e: Exception) {
            Log.e("MorningBriefingWorker", "Error generating morning briefing", e)
            Result.failure()
        }
    }

    private fun buildFallbackSummary(overdueCount: Int, upcomingCount: Int): String {
        return when {
            overdueCount > 0 -> "Good morning! You have $overdueCount overdue items and $upcomingCount upcoming tasks today."
            upcomingCount > 0 -> "Good morning! You have $upcomingCount tasks planned for today."
            else -> "Good morning! Your agenda is clear today. Enjoy your day!"
        }
    }

    private fun postBriefingNotification(title: String, body: String) {
        val channelId = "paa_briefings"
        val nm = context.getSystemService(NotificationManager::class.java)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(
                NotificationChannel(channelId, "PAA Executive Briefings", NotificationManager.IMPORTANCE_HIGH)
            )
        }

        val openIntent = Intent(context, MainActivity::class.java)
        val pi = PendingIntent.getActivity(
            context, 9001, openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )


        val notification = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(android.R.drawable.ic_menu_agenda)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setContentIntent(pi)
            .setAutoCancel(true)
            .build()

        nm.notify(9001, notification)
    }
}
