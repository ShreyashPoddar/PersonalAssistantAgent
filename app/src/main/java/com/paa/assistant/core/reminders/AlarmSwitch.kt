package com.paa.assistant.core.reminders

import android.content.Context
import com.paa.assistant.data.db.AppDatabase
import kotlinx.coroutines.flow.first

/**
 * The owner's "All alarms off" switch. Off: every pending task's alarms are cancelled and nothing new is set (the
 * tasks stay). On again: alarms are recreated for tasks still due in the future. The choice is saved.
 */
object AlarmSwitch {

    suspend fun set(context: Context, off: Boolean) {
        ReminderScheduler.setAlarmsOff(context, off)
        if (off) {
            AppDatabase.getInstance(context).taskDao().observePendingTasks().first().forEach { ReminderScheduler.cancel(context, it.id) }
        } else {
            androidx.work.WorkManager.getInstance(context).enqueue(
                androidx.work.OneTimeWorkRequestBuilder<com.paa.assistant.workers.RescheduleAlarmsWorker>().build()
            )
        }
    }

    private val OFF = Regex(
        "^(?:(all )?alarms? (off|band)|turn off (all )?(the )?alarms?|(sab|saare|sare) alarms? band( kar(o|do|de| do| de)?)?|no (more )?alarms?|mute (all )?alarms?)$"
    )
    private val ON = Regex("^(?:(all )?alarms? (on|chalu)|turn on (all )?(the )?alarms?|(sab|saare|sare) alarms? (on|chalu)( kar(o|do|de| do| de)?)?)$")

    /** Voice: false = turn alarms off, true = back on, null = not this command. */
    fun voiceCommand(said: String): Boolean? {
        val s = said.trim().lowercase().trimEnd('.', '!').replace(Regex("^(oyee? pa|hey pa|pa)[, ]+"), "")
        return when {
            OFF.matches(s) -> false
            ON.matches(s) -> true
            else -> null
        }
    }
}
