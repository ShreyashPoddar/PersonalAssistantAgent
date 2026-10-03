package com.paa.assistant

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.paa.assistant.workers.EveningRetrospectiveWorker
import com.paa.assistant.workers.MorningBriefingWorker
import dagger.hilt.android.HiltAndroidApp
import java.util.concurrent.TimeUnit
import javax.inject.Inject

/**
 * PAA Application class.
 * Bootstraps Hilt dependency injection and WorkManager with Hilt integration.
 * Schedules proactive morning briefing and evening retrospective jobs.
 */
@HiltAndroidApp
class PAAApplication : Application(), Configuration.Provider {

    @Inject
    lateinit var workerFactory: HiltWorkerFactory

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .build()

    override fun onCreate() {
        super.onCreate()
        com.paa.assistant.services.DetectionLog.init(this)
        scheduleProactiveJobs()
        // Re-apply current alarm rules (e.g. the 7 AM–7 PM ringing window) to all pending tasks
        WorkManager.getInstance(this).enqueueUniqueWork(
            "paa_reschedule_alarms",
            androidx.work.ExistingWorkPolicy.KEEP,
            androidx.work.OneTimeWorkRequestBuilder<com.paa.assistant.workers.RescheduleAlarmsWorker>().build()
        )
    }

    @Inject
    lateinit var localLlm: com.paa.assistant.core.ai.LocalLlm

    /** Free the ~3 GB model before Android starts killing apps (it killed PAA for LOW_MEMORY before). */
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level >= android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW) localLlm.releaseIfIdle()
    }

    private fun scheduleProactiveJobs() {
        val workManager = WorkManager.getInstance(this)

        val morningDelay = calculateInitialDelay(7, 0)
        val morningWork = PeriodicWorkRequestBuilder<MorningBriefingWorker>(24, TimeUnit.HOURS)
            .setInitialDelay(morningDelay, TimeUnit.MILLISECONDS)
            .build()
        workManager.enqueueUniquePeriodicWork(
            "paa_morning_briefing",
            ExistingPeriodicWorkPolicy.UPDATE,
            morningWork
        )

        // Nudges for overdue chat tasks (the worker itself stays quiet outside 07:00–19:00)
        workManager.enqueueUniquePeriodicWork(
            "paa_followups",
            ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<com.paa.assistant.workers.FollowUpWorker>(2, TimeUnit.HOURS).build()
        )

        val eveningDelay = calculateInitialDelay(21, 0)
        val eveningWork = PeriodicWorkRequestBuilder<EveningRetrospectiveWorker>(24, TimeUnit.HOURS)
            .setInitialDelay(eveningDelay, TimeUnit.MILLISECONDS)
            .build()
        workManager.enqueueUniquePeriodicWork(
            "paa_evening_retrospective",
            ExistingPeriodicWorkPolicy.UPDATE,
            eveningWork
        )
    }

    private fun calculateInitialDelay(targetHour: Int, targetMinute: Int = 0): Long {
        val now = java.util.Calendar.getInstance()
        val target = java.util.Calendar.getInstance().apply {
            set(java.util.Calendar.HOUR_OF_DAY, targetHour)
            set(java.util.Calendar.MINUTE, targetMinute)
            set(java.util.Calendar.SECOND, 0)
            set(java.util.Calendar.MILLISECOND, 0)
        }
        if (target.before(now)) {
            target.add(java.util.Calendar.DAY_OF_YEAR, 1)
        }
        return target.timeInMillis - now.timeInMillis
    }
}
