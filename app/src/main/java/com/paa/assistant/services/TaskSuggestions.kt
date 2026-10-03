package com.paa.assistant.services

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.paa.assistant.data.db.FeedbackDao
import com.paa.assistant.data.models.TaskFeedbackEntity
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * "Is this a task?" — asked when the on-device model isn't confident enough to schedule on its own.
 * ✓ Yes schedules it; ✗ No drops it. Both answers are saved and shown to the model as examples,
 * so it learns the user's judgement over time.
 */
object TaskSuggestions {
    private const val CHANNEL = "paa_task_suggestions_v2"
    const val ACTION_YES = "com.paa.assistant.ACTION_SUGGESTION_YES"
    const val ACTION_NO = "com.paa.assistant.ACTION_SUGGESTION_NO"

    data class Suggestion(
        val title: String,
        val timestamp: Long?,
        val priority: Int,
        val tag: String,
        val source: String,
        val defaultDueMinutes: Int?,
        val reason: String?,
        val message: String
    )

    fun ask(context: Context, s: Suggestion) {
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "Possible tasks (needs your answer)", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "PAA isn't sure a chat message is a task and asks you"
                lockscreenVisibility = android.app.Notification.VISIBILITY_PRIVATE
            }
        )
        val id = (s.title + s.message).hashCode()

        fun action(action: String, slot: Int): PendingIntent = PendingIntent.getBroadcast(
            context, id * 2 + slot,
            Intent(context, SuggestionReceiver::class.java).setAction(action)
                .putExtra("id", id)
                .putExtra("title", s.title)
                .putExtra("timestamp", s.timestamp ?: -1L)
                .putExtra("priority", s.priority)
                .putExtra("tag", s.tag)
                .putExtra("source", s.source)
                .putExtra("defaultDueMinutes", s.defaultDueMinutes ?: -1)
                .putExtra("reason", s.reason)
                .putExtra("message", s.message),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val body = "From ${s.source}: \"${s.message.take(120)}\"" + (s.reason?.let { "\nPAA thinks: $it" } ?: "")
        val notification = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_menu_help)
            .setContentTitle("Is this a task? ${s.title}")
            .setContentText("From ${s.source}")
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(
                NotificationCompat.Builder(context, CHANNEL)
                    .setSmallIcon(android.R.drawable.ic_menu_help)
                    .setContentTitle("PAA has a question")
                    .setContentText("Unlock to view")
                    .build()
            )
            .setAutoCancel(true)
            .addAction(0, "✓ Yes, schedule", action(ACTION_YES, 0))
            .addAction(0, "✗ No", action(ACTION_NO, 1))
            .build()
        nm.notify(id, notification)
    }
}

/** Handles ✓ / ✗ on "Is this a task?" notifications. */
@AndroidEntryPoint
class SuggestionReceiver : BroadcastReceiver() {
    @Inject lateinit var scheduler: ChatTaskScheduler
    @Inject lateinit var feedbackDao: FeedbackDao

    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getIntExtra("id", 0)
        context.getSystemService(NotificationManager::class.java).cancel(id)
        val title = intent.getStringExtra("title") ?: return
        val message = intent.getStringExtra("message") ?: return
        val yes = intent.action == TaskSuggestions.ACTION_YES

        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                feedbackDao.insert(TaskFeedbackEntity(message = message, title = title, isTask = yes))
                feedbackDao.trim()
                if (yes) {
                    scheduler.schedule(
                        title = title,
                        timestamp = intent.getLongExtra("timestamp", -1L).takeIf { it > 0 },
                        priority = intent.getIntExtra("priority", 1),
                        tag = intent.getStringExtra("tag") ?: "#chat",
                        source = intent.getStringExtra("source") ?: "chat",
                        defaultDueMinutes = intent.getIntExtra("defaultDueMinutes", -1).takeIf { it > 0 },
                        reason = intent.getStringExtra("reason"),
                        sourceMessage = message
                    )
                }
                DetectionLog.add("you answered", message, if (yes) "✓ scheduled: $title" else "✗ not a task: $title")
            } finally {
                pending.finish()
            }
        }
    }
}
