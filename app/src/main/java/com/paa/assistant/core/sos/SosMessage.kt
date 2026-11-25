package com.paa.assistant.core.sos

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** SOS texts (P1-T9). Pure, so they're unit-tested. */
object SosMessage {
    fun text(name: String, lat: Double?, lon: Double?, accuracyM: Float?, at: Long, drill: Boolean): String {
        val time = SimpleDateFormat("h:mm a", Locale.ENGLISH).format(Date(at))
        val where = if (lat != null && lon != null) {
            "Location: https://maps.google.com/?q=%.6f,%.6f".format(Locale.ROOT, lat, lon) +
                (accuracyM?.let { " (±${it.toInt()} m)" } ?: "") + " at $time."
        } else "Location unavailable at $time — call me."
        return (if (drill) "TEST — ignore: " else "") + "🆘 SOS from $name. $where Sent by PAA."
    }

    fun safe(name: String, drill: Boolean) = (if (drill) "TEST — ignore: " else "") + "$name is safe now. (PAA)"

    private val TRIGGER = Regex("^(?:sos|emergency|help me|help|bachao|madad karo|madad|mujhe bachao|save me)[.!]*$")

    /** Whole-utterance SOS phrases only (≤ 3 words): "help me with the assignment" is not an SOS. */
    fun isTrigger(said: String): Boolean {
        val s = said.trim().lowercase(Locale.ROOT).replace(Regex("^(oyee? pa|hey pa|pa)[, ]+"), "").trim()
        return s.split(Regex("\\s+")).size <= 3 && TRIGGER.matches(s)
    }
}
