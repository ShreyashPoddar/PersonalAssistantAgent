package com.paa.assistant.core.location

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Named places the user saved ("home", "office", "college", …) for location reminders.
 * Stored only in the app's private storage on this phone.
 */
@Singleton
class SavedPlaces @Inject constructor(
    @ApplicationContext context: Context
) {
    private val prefs = context.getSharedPreferences("paa_saved_places", Context.MODE_PRIVATE)

    private val aliases = mapOf("work" to "office", "pg" to "home", "hostel" to "home", "room" to "home", "house" to "home")

    fun normalize(name: String): String = name.lowercase().trim().let { aliases[it] ?: it }

    fun save(name: String, lat: Double, lng: Double) {
        prefs.edit().putString(normalize(name), "$lat,$lng").apply()
    }

    fun get(name: String): Pair<Double, Double>? {
        val raw = prefs.getString(normalize(name), null) ?: return null
        val (lat, lng) = raw.split(",").map { it.toDouble() }
        return lat to lng
    }
}
