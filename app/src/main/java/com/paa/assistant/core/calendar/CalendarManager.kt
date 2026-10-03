package com.paa.assistant.core.calendar

import android.content.ContentValues
import android.content.Context
import android.provider.CalendarContract
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import javax.inject.Inject
import javax.inject.Singleton

data class CalendarEventInfo(
    val id: Long,
    val title: String,
    val startMillis: Long,
    val endMillis: Long,
    val location: String? = null
) {
    val startTime: String
        get() = SimpleDateFormat("h:mm a", Locale.getDefault()).format(java.util.Date(startMillis))
}

/**
 * CalendarManager — interfaces with Android CalendarProvider.
 * Creates calendar events in the device's default calendar when triggered
 * by Gemini tool calling or local agenda parser, and reads upcoming events.
 */
@Singleton
class CalendarManager @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val tag = "CalendarManager"

    fun getPrimaryCalendarId(): Long {
        try {
            val projection = arrayOf(CalendarContract.Calendars._ID, CalendarContract.Calendars.IS_PRIMARY)
            context.contentResolver.query(
                CalendarContract.Calendars.CONTENT_URI,
                projection,
                null, null, null
            )?.use { cursor ->
                var fallbackId = 1L
                while (cursor.moveToNext()) {
                    val id = cursor.getLong(0)
                    val isPrimary = if (cursor.columnCount > 1) cursor.getInt(1) == 1 else false
                    if (isPrimary) return id
                    fallbackId = id
                }
                return fallbackId
            }
        } catch (e: Exception) {
            Log.e(tag, "Failed to query calendars", e)
        }
        return 1L
    }

    suspend fun getTodayEvents(): List<CalendarEventInfo> = withContext(Dispatchers.IO) {
        val events = mutableListOf<CalendarEventInfo>()
        try {
            val cal = java.util.Calendar.getInstance().apply {
                set(java.util.Calendar.HOUR_OF_DAY, 0)
                set(java.util.Calendar.MINUTE, 0)
                set(java.util.Calendar.SECOND, 0)
            }
            val startDay = cal.timeInMillis
            val endDay = startDay + 86_400_000L

            val projection = arrayOf(
                CalendarContract.Events._ID,
                CalendarContract.Events.TITLE,
                CalendarContract.Events.DTSTART,
                CalendarContract.Events.DTEND,
                CalendarContract.Events.EVENT_LOCATION
            )
            val selection = "${CalendarContract.Events.DTSTART} >= ? AND ${CalendarContract.Events.DTSTART} < ?"
            val selectionArgs = arrayOf(startDay.toString(), endDay.toString())

            context.contentResolver.query(
                CalendarContract.Events.CONTENT_URI,
                projection,
                selection,
                selectionArgs,
                "${CalendarContract.Events.DTSTART} ASC"
            )?.use { cursor ->
                while (cursor.moveToNext()) {
                    val id = cursor.getLong(0)
                    val title = cursor.getString(1) ?: "Untitled Event"
                    val start = cursor.getLong(2)
                    val end = cursor.getLong(3)
                    val loc = cursor.getString(4)
                    events.add(CalendarEventInfo(id, title, start, end, loc))
                }
            }
        } catch (e: Exception) {
            Log.e(tag, "Error reading today's calendar events", e)
        }
        events
    }

    suspend fun addEvent(
        title: String,
        startTimeIso: String,
        endTimeIso: String?,
        location: String? = null,
        description: String? = null
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            val startMillis = parseIsoToMillis(startTimeIso) ?: System.currentTimeMillis()
            val endMillis = endTimeIso?.let { parseIsoToMillis(it) } ?: (startMillis + 3600_000L) // Default 1 hr
            val calendarId = getPrimaryCalendarId()

            val values = ContentValues().apply {
                put(CalendarContract.Events.DTSTART, startMillis)
                put(CalendarContract.Events.DTEND, endMillis)
                put(CalendarContract.Events.TITLE, title)
                put(CalendarContract.Events.DESCRIPTION, description ?: "Scheduled by PAA")
                put(CalendarContract.Events.CALENDAR_ID, calendarId)
                put(CalendarContract.Events.EVENT_TIMEZONE, TimeZone.getDefault().id)
                location?.let { put(CalendarContract.Events.EVENT_LOCATION, it) }
            }

            val uri = context.contentResolver.insert(CalendarContract.Events.CONTENT_URI, values)
            Log.d(tag, "Calendar event inserted: $uri")
            uri != null
        } catch (e: Exception) {
            Log.e(tag, "Failed to insert calendar event", e)
            false
        }
    }

    fun parseIsoToMillis(isoString: String): Long? {
        val formats = listOf(
            "yyyy-MM-dd'T'HH:mm:ssXXX",
            "yyyy-MM-dd'T'HH:mm:ss.SSSXXX",
            "yyyy-MM-dd'T'HH:mm:ss",
            "yyyy-MM-dd"
        )
        for (pattern in formats) {
            try {
                val sdf = SimpleDateFormat(pattern, Locale.getDefault())
                sdf.timeZone = TimeZone.getDefault()
                val date = sdf.parse(isoString)
                if (date != null) return date.time
            } catch (e: Exception) {
                // Continue to next format
            }
        }
        return null
    }
}
