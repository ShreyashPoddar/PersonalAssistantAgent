package com.paa.assistant.core.reminders

import java.util.Calendar

/**
 * Next occurrence of a repeating task, from the iCal RRULE subset PAA writes:
 * FREQ=DAILY|WEEKLY|MONTHLY, optional INTERVAL=n, BYDAY=MO,WE (weekly), BYMONTHDAY=n (monthly).
 * The time of day is kept from the current deadline.
 */
object Recurrence {
    private val DAYS = mapOf(
        "SU" to Calendar.SUNDAY, "MO" to Calendar.MONDAY, "TU" to Calendar.TUESDAY, "WE" to Calendar.WEDNESDAY,
        "TH" to Calendar.THURSDAY, "FR" to Calendar.FRIDAY, "SA" to Calendar.SATURDAY
    )

    /** First occurrence strictly after [due] (and after [now], so a long-missed task jumps to the future). */
    fun next(rule: String, due: Long, now: Long = System.currentTimeMillis()): Long? {
        val parts = rule.removePrefix("RRULE:").split(";").mapNotNull {
            it.split("=", limit = 2).takeIf { kv -> kv.size == 2 }?.let { kv -> kv[0].uppercase() to kv[1].uppercase() }
        }.toMap()
        val interval = parts["INTERVAL"]?.toIntOrNull()?.coerceAtLeast(1) ?: 1
        val cal = Calendar.getInstance().apply { timeInMillis = due }
        // Bounded loop: at most ~2 years of steps
        repeat(800) {
            when (parts["FREQ"]) {
                "DAILY" -> cal.add(Calendar.DAY_OF_YEAR, interval)
                "WEEKLY" -> {
                    val days = parts["BYDAY"]?.split(",")?.mapNotNull { DAYS[it.trim()] }.orEmpty()
                    if (days.isEmpty()) cal.add(Calendar.WEEK_OF_YEAR, interval)
                    else do { cal.add(Calendar.DAY_OF_YEAR, 1) } while (cal.get(Calendar.DAY_OF_WEEK) !in days)
                }
                "MONTHLY" -> {
                    val day = parts["BYMONTHDAY"]?.toIntOrNull() ?: Calendar.getInstance().apply { timeInMillis = due }.get(Calendar.DAY_OF_MONTH)
                    // Months without that day (31st in April) are skipped
                    do {
                        cal.set(Calendar.DAY_OF_MONTH, 1)
                        cal.add(Calendar.MONTH, interval)
                    } while (cal.getActualMaximum(Calendar.DAY_OF_MONTH) < day)
                    cal.set(Calendar.DAY_OF_MONTH, day)
                }
                else -> return null
            }
            if (cal.timeInMillis > now) return cal.timeInMillis
        }
        return null
    }
}
