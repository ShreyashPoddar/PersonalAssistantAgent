package com.paa.assistant.services

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.media.RingtoneManager
import android.os.PowerManager
import android.util.Log
import com.google.android.gms.location.Geofence
import com.google.android.gms.location.GeofencingEvent
import com.paa.assistant.data.db.AppDatabase
import com.paa.assistant.ui.overlay.VoiceOverlayActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * GeofenceBroadcastReceiver — triggered by Android OS when a user crosses a geofence boundary.
 *
 * Posts a high-priority heads-up notification alerting the user of their location-based reminder.
 * Provides a "✓ Done" action to complete the task directly from the notification.
 */
class GeofenceBroadcastReceiver : BroadcastReceiver() {

    private val tag = "GeofenceReceiver"

    override fun onReceive(context: Context, intent: Intent) {
        val geofencingEvent = GeofencingEvent.fromIntent(intent)
        if (geofencingEvent == null) {
            Log.w(tag, "GeofencingEvent is null")
            return
        }

        if (geofencingEvent.hasError()) {
            Log.e(tag, "GeofencingEvent error code: ${geofencingEvent.errorCode}")
            return
        }

        val transition = geofencingEvent.geofenceTransition
        val triggeringGeofences = geofencingEvent.triggeringGeofences ?: return

        Log.d(tag, "Geofence transition $transition triggered by ${triggeringGeofences.size} geofence(s)")

        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val db = AppDatabase.getInstance(context)
                for (geofence in triggeringGeofences) {
                    val taskId = geofence.requestId.toLongOrNull() ?: continue
                    val task = db.taskDao().getById(taskId) ?: continue

                    // Wake device screen briefly
                    try {
                        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
                        val wakeLock = pm.newWakeLock(
                            PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP,
                            "PAA:GeofenceWakeLock"
                        )
                        wakeLock.acquire(3000)
                    } catch (e: Exception) {
                        Log.e(tag, "Could not acquire wake lock", e)
                    }

                    showGeofenceNotification(context, task.id, task.title, task.locationName, transition)
                }
            } catch (e: Exception) {
                Log.e(tag, "Error handling geofence event", e)
            } finally {
                pendingResult.finish()
            }
        }
    }

    private fun showGeofenceNotification(
        context: Context,
        taskId: Long,
        title: String,
        locationName: String?,
        transition: Int
    ) {
        val channelId = "paa_geofence_reminders"
        val nm = context.getSystemService(NotificationManager::class.java)

        nm.createNotificationChannel(
            NotificationChannel(channelId, "PAA Location Reminders", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Geofence location arrival and departure alerts"
                enableVibration(true)
                enableLights(true)
                setSound(RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION), null)
            }
        )

        // Action: Mark as Done
        val doneIntent = Intent(context, ReminderActionReceiver::class.java).apply {
            action = "com.paa.assistant.ACTION_MARK_DONE"
            putExtra("task_id", taskId)
        }
        val donePi = PendingIntent.getBroadcast(
            context,
            taskId.toInt() + 3000,
            doneIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // Action: Open PAA
        val openIntent = Intent(context, VoiceOverlayActivity::class.java).apply {
            putExtra("auto_listen", false)
        }
        val openPi = PendingIntent.getActivity(
            context,
            taskId.toInt() + 4000,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val destination = locationName ?: "your destination"
        val headerText = if (transition == Geofence.GEOFENCE_TRANSITION_EXIT) {
            "📍 Leaving $destination"
        } else {
            "📍 Arrived at $destination!"
        }

        val notification = Notification.Builder(context, channelId)
            .setSmallIcon(android.R.drawable.ic_dialog_map)
            .setContentTitle(headerText)
            .setContentText(title)
            .setPriority(Notification.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(openPi)
            .addAction(
                Notification.Action.Builder(null, "✓ Complete", donePi).build()
            )
            .build()

        nm.notify(taskId.toInt() + 10000, notification)
    }
}
