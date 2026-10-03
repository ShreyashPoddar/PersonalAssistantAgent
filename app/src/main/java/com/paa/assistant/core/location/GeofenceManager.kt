package com.paa.assistant.core.location

import android.Manifest
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Geocoder
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import com.google.android.gms.location.Geofence
import com.google.android.gms.location.GeofencingClient
import com.google.android.gms.location.GeofencingRequest
import com.google.android.gms.location.LocationServices
import com.paa.assistant.data.db.TaskDao
import com.paa.assistant.data.models.TaskEntity
import com.paa.assistant.services.GeofenceBroadcastReceiver
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/**
 * GeofenceManager — manages device hardware geofences via Google Play Services Location.
 *
 * Provides:
 *  1. Energy-efficient hardware geofencing (Wi-Fi + Cell offloading, low battery impact)
 *  2. Registration of perimeter enter/exit triggers
 *  3. Address/venue geocoding (place name -> lat/lng)
 *  4. Survival across reboots via recreateAllGeofences()
 */
@Singleton
class GeofenceManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val taskDao: TaskDao
) {
    private val tag = "GeofenceManager"
    private val geofencingClient: GeofencingClient = LocationServices.getGeofencingClient(context)

    companion object {
        const val ACTION_GEOFENCE_TRIGGER = "com.paa.assistant.ACTION_GEOFENCE_TRIGGER"
    }

    /**
     * Add a hardware geofence for a task.
     */
    fun addGeofence(task: TaskEntity): Boolean {
        val lat = task.latitude
        val lng = task.longitude
        if (lat == null || lng == null) {
            Log.w(tag, "Cannot add geofence: missing coordinates for task #${task.id}")
            return false
        }

        if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            Log.w(tag, "ACCESS_FINE_LOCATION not granted, cannot register geofence")
            return false
        }

        try {
            val transitionType = if (task.geofenceTransition == 2) {
                Geofence.GEOFENCE_TRANSITION_EXIT
            } else {
                Geofence.GEOFENCE_TRANSITION_ENTER
            }

            val geofence = Geofence.Builder()
                .setRequestId(task.id.toString())
                .setCircularRegion(lat, lng, task.geofenceRadiusMeters)
                .setExpirationDuration(Geofence.NEVER_EXPIRE)
                .setTransitionTypes(transitionType)
                .setNotificationResponsiveness(5000) // 5 seconds
                .build()

            val request = GeofencingRequest.Builder()
                .setInitialTrigger(GeofencingRequest.INITIAL_TRIGGER_ENTER)
                .addGeofence(geofence)
                .build()

            val pendingIntent = getGeofencePendingIntent(task.id)

            geofencingClient.addGeofences(request, pendingIntent)
                .addOnSuccessListener {
                    Log.d(tag, "Geofence registered successfully for task #${task.id} at (${lat}, ${lng})")
                }
                .addOnFailureListener { e ->
                    Log.e(tag, "Failed to register geofence for task #${task.id}", e)
                }

            return true
        } catch (e: SecurityException) {
            Log.e(tag, "SecurityException registering geofence", e)
            return false
        } catch (e: Exception) {
            Log.e(tag, "Error registering geofence", e)
            return false
        }
    }

    /**
     * Remove a geofence when a task is completed or deleted.
     */
    fun removeGeofence(taskId: Long) {
        try {
            geofencingClient.removeGeofences(listOf(taskId.toString()))
                .addOnSuccessListener {
                    Log.d(tag, "Geofence removed for task #$taskId")
                }
                .addOnFailureListener { e ->
                    Log.e(tag, "Failed to remove geofence for task #$taskId", e)
                }
        } catch (e: Exception) {
            Log.e(tag, "Error removing geofence", e)
        }
    }

    /**
     * Recreate all active geofences from Room DB (called on reboot).
     */
    suspend fun recreateAllGeofences() = withContext(Dispatchers.IO) {
        val tasks = taskDao.getPendingTasksWithGeofence()
        Log.d(tag, "Recreating ${tasks.size} active geofences from DB")
        for (task in tasks) {
            addGeofence(task)
        }
    }

    /**
     * Geocode a place name into coordinates (lat, lng).
     */
    suspend fun resolveCoordinates(placeName: String): Pair<Double, Double>? = withContext(Dispatchers.IO) {
        try {
            val geocoder = Geocoder(context, Locale.getDefault())
            @Suppress("DEPRECATION")
            val results = geocoder.getFromLocationName(placeName, 1)
            if (!results.isNullOrEmpty()) {
                val addr = results[0]
                return@withContext Pair(addr.latitude, addr.longitude)
            }
        } catch (e: Exception) {
            Log.e(tag, "Geocoding failed for place '$placeName'", e)
        }
        null
    }

    /**
     * Get last known device location as fallback when Geocoder is unavailable.
     */
    suspend fun getLastKnownLocation(): Pair<Double, Double>? = withContext(Dispatchers.IO) {
        try {
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
                val fusedLocationClient = LocationServices.getFusedLocationProviderClient(context)
                val location = com.google.android.gms.tasks.Tasks.await(fusedLocationClient.lastLocation)
                if (location != null) {
                    return@withContext Pair(location.latitude, location.longitude)
                }
            }
        } catch (e: Exception) {
            Log.e(tag, "Failed to get last known location", e)
        }
        null
    }

    private fun getGeofencePendingIntent(taskId: Long): PendingIntent {
        val intent = Intent(context, GeofenceBroadcastReceiver::class.java).apply {
            action = ACTION_GEOFENCE_TRIGGER
        }
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }
        return PendingIntent.getBroadcast(
            context,
            taskId.toInt(),
            intent,
            flags
        )
    }
}
