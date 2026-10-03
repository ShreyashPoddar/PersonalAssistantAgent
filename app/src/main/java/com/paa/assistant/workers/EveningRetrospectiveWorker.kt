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
import com.paa.assistant.data.db.AppDatabase
import com.paa.assistant.ui.main.MainActivity
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * EveningRetrospectiveWorker — generates a proactive evening review at 9:00 PM.
 * Evaluates tasks not completed today and offers to roll them over or review.
 */
@HiltWorker
class EveningRetrospectiveWorker @AssistedInject constructor(
    @Assisted private val context: Context,
    @Assisted workerParams: WorkerParameters,
    private val database: AppDatabase
) : CoroutineWorker(context, workerParams) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        try {
            val pendingTasks = database.taskDao().getUpcomingTasks(10)
            if (pendingTasks.isNotEmpty()) {
                val body = "You have ${pendingTasks.size} pending tasks from today. Tap to review or reschedule them for tomorrow."
                postNotification("🌙 Evening Retrospective", body)
            }
            Result.success()
        } catch (e: Exception) {
            Log.e("EveningWorker", "Error in evening retrospective", e)
            Result.failure()
        }
    }

    private fun postNotification(title: String, body: String) {
        val channelId = "paa_briefings"
        val nm = context.getSystemService(NotificationManager::class.java)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(
                NotificationChannel(channelId, "PAA Executive Briefings", NotificationManager.IMPORTANCE_DEFAULT)
            )
        }

        val openIntent = Intent(context, MainActivity::class.java)
        val pi = PendingIntent.getActivity(
            context, 9002, openIntent,
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

        nm.notify(9002, notification)
    }
}
