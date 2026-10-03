package com.paa.assistant.workers

import android.content.Context
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.paa.assistant.data.db.AppDatabase
import com.paa.assistant.data.repository.TaskRepository
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * RescheduleAlarmsWorker — reschedules all pending task alarms after device reboot.
 * Runs as a WorkManager one-time background job triggered by BOOT_COMPLETED.
 */
@HiltWorker
class RescheduleAlarmsWorker @AssistedInject constructor(
    @Assisted private val context: Context,
    @Assisted workerParams: WorkerParameters,
    private val repository: TaskRepository,
    private val database: AppDatabase
) : CoroutineWorker(context, workerParams) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        try {
            Log.d("RescheduleWorker", "Rescheduling task alarms on boot...")
            repository.advanceMissedRecurring()
            val pendingTasks = database.taskDao().getUpcomingTasks(100)
            val now = System.currentTimeMillis()

            for (task in pendingTasks) {
                if (task.dueTimestamp != null && task.dueTimestamp > now) {
                    repository.updateTask(task)
                }
            }

            Log.d("RescheduleWorker", "Recreating hardware geofences on boot...")
            repository.recreateAllGeofences()

            Result.success()
        } catch (e: Exception) {
            Log.e("RescheduleWorker", "Failed to reschedule alarms/geofences", e)
            Result.retry()
        }
    }
}
