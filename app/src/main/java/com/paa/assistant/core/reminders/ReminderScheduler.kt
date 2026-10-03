package com.paa.assistant.core.reminders

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.util.Log
import com.paa.assistant.core.profile.UserProfile
import com.paa.assistant.services.ReminderReceiver
import com.paa.assistant.ui.main.MainActivity
import java.util.Calendar

/**
 * Single place that schedules and cancels task alarms.
 *
 * Each task gets two alarms:
 *  - EARLY: a heads-up [earlyMinutes] before the deadline (skipped if already past)
 *  - DUE:   an alarm-style alert at the deadline itself
 *
 * DUE alarms use setAlarmClock(), which Android (and aggressive OEM skins like iQOO/Funtouch)
 * treat like a real alarm clock: they fire on time even in Doze.
 */
object ReminderScheduler {
    private const val TAG = "ReminderScheduler"
    const val ACTION_FIRE = "com.paa.assistant.ACTION_REMINDER_FIRE"
    const val EXTRA_TASK_ID = "task_id"
    const val EXTRA_TITLE = "task_title"
    const val EXTRA_KIND = "kind"
    const val EXTRA_DUE = "due"
    const val KIND_EARLY = "early"
    const val KIND_DUE = "due"
    /** 7 AM ring for a deadline that passed during quiet hours. */
    const val KIND_CATCHUP = "catchup"

    /** Default heads-up lead time for tasks that didn't specify one. */
    const val DEFAULT_EARLY_MINUTES = 30

    /** End of today (23:59) — the default deadline when a message names none. */
    fun endOfToday(): Long = Calendar.getInstance().apply {
        set(Calendar.HOUR_OF_DAY, 23); set(Calendar.MINUTE, 59)
        set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    /** Minutes between 9 PM and the EOD deadline, so EOD tasks get an evening heads-up. */
    const val EOD_EARLY_MINUTES = 179

    fun schedule(context: Context, taskId: Long, title: String, due: Long, earlyMinutes: Int) {
        val now = System.currentTimeMillis()
        val am = context.getSystemService(AlarmManager::class.java)

        // Heads-up must be able to ring: pull it into the 7 AM–7 PM window if it falls outside
        val early = latestRingableAtOrBefore(due - earlyMinutes * 60_000L)
        if (earlyMinutes > 0 && early > now && early < due) {
            setExact(am, early, pendingIntent(context, taskId, title, KIND_EARLY, due))
        }
        // Deadline during quiet hours → its alert is silent, so ring once at the next 7 AM if still open
        if (due > now && !isRingTime(due)) {
            setExact(am, nextWindowStart(due), pendingIntent(context, taskId, title, KIND_CATCHUP, due))
        }
        if (due > now) {
            val pi = pendingIntent(context, taskId, title, KIND_DUE, due)
            try {
                val show = PendingIntent.getActivity(
                    context, 0, Intent(context, MainActivity::class.java),
                    PendingIntent.FLAG_IMMUTABLE
                )
                am.setAlarmClock(AlarmManager.AlarmClockInfo(due, show), pi)
            } catch (e: SecurityException) {
                setExact(am, due, pi)
            }
        } else {
            Log.w(TAG, "Task $taskId deadline already passed; no alarm set")
        }
    }

    fun cancel(context: Context, taskId: Long) {
        val am = context.getSystemService(AlarmManager::class.java)
        for (kind in listOf(KIND_EARLY, KIND_DUE, KIND_CATCHUP)) {
            PendingIntent.getBroadcast(
                context, 0, baseIntent(context, taskId, kind),
                PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
            )?.let { am.cancel(it); it.cancel() }
        }
    }

    /** Re-fires the DUE alert after [minutes]. */
    fun snooze(context: Context, taskId: Long, title: String, minutes: Int) {
        val at = System.currentTimeMillis() + minutes * 60_000L
        val am = context.getSystemService(AlarmManager::class.java)
        setExact(am, at, pendingIntent(context, taskId, title, KIND_DUE, at))
    }

    /** True if [time] is inside the daily ringing window (7 AM–7 PM by default). */
    fun isRingTime(time: Long = System.currentTimeMillis()): Boolean {
        val h = Calendar.getInstance().apply { timeInMillis = time }.get(Calendar.HOUR_OF_DAY)
        return h in UserProfile.ALARM_START_HOUR until UserProfile.ALARM_END_HOUR
    }

    /** [time] if it can ring; otherwise the last ringable moment before it (6:30 PM that day or the day before). */
    fun latestRingableAtOrBefore(time: Long): Long {
        if (isRingTime(time)) return time
        val c = Calendar.getInstance().apply { timeInMillis = time }
        if (c.get(Calendar.HOUR_OF_DAY) < UserProfile.ALARM_START_HOUR) c.add(Calendar.DAY_OF_YEAR, -1)
        c.set(Calendar.HOUR_OF_DAY, UserProfile.ALARM_END_HOUR - 1)
        c.set(Calendar.MINUTE, 30); c.set(Calendar.SECOND, 0); c.set(Calendar.MILLISECOND, 0)
        return c.timeInMillis
    }

    /** The next window opening (7:00 AM) after [time]. */
    fun nextWindowStart(time: Long): Long {
        val c = Calendar.getInstance().apply {
            timeInMillis = time
            set(Calendar.HOUR_OF_DAY, UserProfile.ALARM_START_HOUR)
            set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }
        if (c.timeInMillis <= time) c.add(Calendar.DAY_OF_YEAR, 1)
        return c.timeInMillis
    }

    private fun setExact(am: AlarmManager, at: Long, pi: PendingIntent) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !am.canScheduleExactAlarms()) {
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
            } else {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
            }
        } catch (e: SecurityException) {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
        }
    }

    // The data URI makes each (task, kind) pair a distinct, cancellable PendingIntent.
    private fun baseIntent(context: Context, taskId: Long, kind: String) =
        Intent(context, ReminderReceiver::class.java)
            .setAction(ACTION_FIRE)
            .setData(Uri.parse("paa://task/$taskId/$kind"))

    private fun pendingIntent(context: Context, taskId: Long, title: String, kind: String, due: Long): PendingIntent =
        PendingIntent.getBroadcast(
            context, 0,
            baseIntent(context, taskId, kind)
                .putExtra(EXTRA_TASK_ID, taskId)
                .putExtra(EXTRA_TITLE, title)
                .putExtra(EXTRA_KIND, kind)
                .putExtra(EXTRA_DUE, due),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
}
