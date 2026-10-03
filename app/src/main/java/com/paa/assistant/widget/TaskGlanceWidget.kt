package com.paa.assistant.widget

import android.content.Context
import android.content.Intent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.action.ActionParameters
import androidx.glance.action.actionParametersOf
import androidx.glance.action.actionStartActivity
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.appwidget.lazy.LazyColumn
import androidx.glance.appwidget.lazy.items
import androidx.glance.appwidget.updateAll
import androidx.glance.background
import androidx.glance.currentState
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import com.paa.assistant.data.db.AppDatabase
import com.paa.assistant.data.models.TaskEntity
import com.paa.assistant.ui.overlay.VoiceOverlayActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * TaskGlanceWidget — PAA's interactive homescreen widget.
 *
 * Features:
 *  - Date header with pending task count badge
 *  - Mic button to launch voice overlay directly from widget
 *  - Prioritized task list with color-coded badges
 *  - Interactive checkboxes to complete tasks without opening the app
 *  - "No tasks" empty state with motivational message
 */
class TaskGlanceWidget : GlanceAppWidget() {

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val db = withContext(Dispatchers.IO) { AppDatabase.getInstance(context) }
        val nextEvent = withContext(Dispatchers.IO) {
            com.paa.assistant.core.calendar.CalendarManager(context).getTodayEvents()
                .firstOrNull()?.let { "${it.title} at ${it.startTime}" }
        }
        // Live query: the widget redraws whenever a task is added, completed or deleted —
        // loading once here would leave the widget showing a stale list for the whole session
        // All pending tasks (scrollable in the widget), not just the first few
        val taskFlow = db.taskDao().observeUpcomingTasks(100)
        val countFlow = db.taskDao().observePendingCount()

        provideContent {
            val tasks by taskFlow.collectAsState(initial = emptyList())
            val pending by countFlow.collectAsState(initial = 0)
            val todayLabel = SimpleDateFormat("EEE, MMM d", Locale.getDefault()).format(Date())
            WidgetContent(todayLabel = todayLabel, tasks = tasks, nextEvent = nextEvent, pendingCount = pending)
        }
    }
}

@Composable
private fun WidgetContent(todayLabel: String, tasks: List<TaskEntity>, nextEvent: String? = null, pendingCount: Int = tasks.size) {
    val bgColor = Color(0xFF1A1A2E)
    val accentColor = Color(0xFF7C3AED)
    val textPrimary = Color(0xFFE2E8F0)
    val textSecondary = Color(0xFF94A3B8)

    Column(
        modifier = GlanceModifier
            .fillMaxSize()
            .background(ColorProvider(bgColor))
            .padding(12.dp)
            .cornerRadius(20.dp)
    ) {
        // ── HEADER ─────────────────────────────────────────────────────────────
        Row(
            modifier = GlanceModifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = GlanceModifier.defaultWeight()) {
                Text(
                    text = "PAA",
                    style = TextStyle(
                        color = ColorProvider(accentColor),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold
                    )
                )
                Text(
                    text = todayLabel,
                    style = TextStyle(
                        color = ColorProvider(textPrimary),
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold
                    )
                )
            }

            // Mic button → launches VoiceOverlayActivity
            Box(
                modifier = GlanceModifier
                    .size(40.dp)
                    .background(ColorProvider(accentColor))
                    .cornerRadius(20.dp)
                    .clickable(actionStartActivity<VoiceOverlayActivity>()),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "🎙",
                    style = TextStyle(fontSize = 18.sp)
                )
            }
        }

        Spacer(GlanceModifier.height(8.dp))

        // ── DIVIDER ────────────────────────────────────────────────────────────
        Box(
            modifier = GlanceModifier
                .fillMaxWidth()
                .height(1.dp)
                .background(ColorProvider(Color(0xFF334155)))
        ) {}

        Spacer(GlanceModifier.height(8.dp))

        // ── TASK LIST ──────────────────────────────────────────────────────────
        if (tasks.isEmpty()) {
            Text(
                text = "✅ All clear! No pending tasks.",
                style = TextStyle(color = ColorProvider(textSecondary), fontSize = 12.sp)
            )
        } else {
            // Scrolls when there are more tasks than fit the widget
            LazyColumn(modifier = GlanceModifier.defaultWeight().fillMaxWidth()) {
                items(tasks, itemId = { it.id }) { task ->
                    Column {
                        TaskRow(task = task, textPrimary = textPrimary, textSecondary = textSecondary)
                        Spacer(GlanceModifier.height(6.dp))
                    }
                }
            }
        }

        // ── FOOTER: Pending count & Next Calendar Event ────────────────────────
        Spacer(GlanceModifier.height(4.dp))
        Row(
            modifier = GlanceModifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = if (pendingCount > 0) "$pendingCount pending" else "Clear agenda",
                style = TextStyle(color = ColorProvider(textSecondary), fontSize = 10.sp),
                modifier = GlanceModifier.defaultWeight()
            )
            if (nextEvent != null) {
                Text(
                    text = "📅 $nextEvent",
                    style = TextStyle(color = ColorProvider(accentColor), fontSize = 10.sp, fontWeight = FontWeight.Bold),
                    maxLines = 1
                )
            }
        }
    }
}

@Composable
private fun TaskRow(task: TaskEntity, textPrimary: Color, textSecondary: Color) {
    val priorityColor = when (task.priority) {
        3 -> Color(0xFFEF4444)  // URGENT - Red
        2 -> Color(0xFFF59E0B)  // HIGH - Amber
        1 -> Color(0xFF3B82F6)  // MEDIUM - Blue
        else -> Color(0xFF6B7280) // LOW - Gray
    }

    Row(
        modifier = GlanceModifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Priority color dot
        Box(
            modifier = GlanceModifier
                .size(8.dp)
                .background(ColorProvider(priorityColor))
                .cornerRadius(4.dp)
        ) {}

        Spacer(GlanceModifier.width(8.dp))

        // Task title
        Column(modifier = GlanceModifier.defaultWeight()) {
            Text(
                text = task.title,
                style = TextStyle(
                    color = ColorProvider(textPrimary),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium
                ),
                maxLines = 1
            )
            task.dueTimestamp?.let { ts ->
                val sameDay = SimpleDateFormat("yyyyMMdd", Locale.ROOT).let { it.format(Date(ts)) == it.format(Date()) }
                val timeLabel = SimpleDateFormat(if (sameDay) "h:mm a" else "EEE d MMM, h:mm a", Locale.getDefault()).format(Date(ts)) +
                    if (ts < System.currentTimeMillis()) " • overdue" else ""
                Text(
                    text = timeLabel,
                    style = TextStyle(color = ColorProvider(textSecondary), fontSize = 10.sp)
                )
            }
        }

        // Tap-to-complete checkbox
        Box(
            modifier = GlanceModifier
                .size(24.dp)
                .cornerRadius(12.dp)
                .background(ColorProvider(Color(0xFF1E293B)))
                .clickable(
                    actionRunCallback<ToggleTaskAction>(
                        actionParametersOf(
                            ActionParameters.Key<Long>("task_id") to task.id
                        )
                    )
                ),
            contentAlignment = Alignment.Center
        ) {
            Text(text = "○", style = TextStyle(color = ColorProvider(textSecondary), fontSize = 14.sp))
        }
    }
}

/**
 * Action callback invoked when user taps a task checkbox on the homescreen widget.
 * Marks the task as COMPLETED in Room DB and triggers widget re-render.
 */
class ToggleTaskAction : ActionCallback {
    override suspend fun onAction(
        context: Context,
        glanceId: GlanceId,
        parameters: ActionParameters
    ) {
        val taskId = parameters[ActionParameters.Key<Long>("task_id")] ?: return
        withContext(Dispatchers.IO) {
            AppDatabase.getInstance(context).taskDao().updateStatus(taskId, "COMPLETED")
        }
        // Done means no more alarms or reminder notifications for it
        com.paa.assistant.core.reminders.ReminderScheduler.cancel(context, taskId)
        context.getSystemService(android.app.NotificationManager::class.java)
            .cancel(com.paa.assistant.core.reminders.ReminderNotifier.notificationId(taskId))
        TaskGlanceWidget().updateAll(context)
    }
}

/**
 * Widget Receiver registered in AndroidManifest.xml with the widget metadata.
 */
class WidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = TaskGlanceWidget()
}
