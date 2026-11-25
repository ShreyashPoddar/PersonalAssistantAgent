package com.paa.assistant.core.sos

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

data class SosContact(val name: String, val number: String)

/** SOS setup (P1-T9): up to 5 contacts, who to call, siren, countdown, repeats. Stored on the phone only. */
class SosSettings(ctx: Context) {
    private val p = ctx.getSharedPreferences("paa_sos", Context.MODE_PRIVATE)

    var contacts: List<SosContact>
        get() = runCatching {
            val a = JSONArray(p.getString("contacts", "[]"))
            (0 until a.length()).map { a.getJSONObject(it).let { o -> SosContact(o.getString("n"), o.getString("p")) } }
        }.getOrDefault(emptyList())
        set(v) = p.edit().putString("contacts", JSONArray(v.take(5).map { JSONObject().put("n", it.name).put("p", it.number) }).toString()).apply()

    /** "112" or a contact's number; blank = first contact. */
    var callTarget: String
        get() = p.getString("call", "") ?: ""
        set(v) = p.edit().putString("call", v).apply()
    var ownNumber: String  // drills go only here
        get() = p.getString("own", "") ?: ""
        set(v) = p.edit().putString("own", v).apply()
    var siren: Boolean
        get() = p.getBoolean("siren", false)
        set(v) = p.edit().putBoolean("siren", v).apply()
    var countdownSeconds: Int
        get() = p.getInt("countdown", 5)
        set(v) = p.edit().putInt("countdown", v.coerceIn(0, 30)).apply()
    val repeatMinutes: Int get() = p.getInt("repeat", 5)
    val durationMinutes: Int get() = p.getInt("duration", 60)

    fun callNumber(): String? = callTarget.ifBlank { contacts.firstOrNull()?.number.orEmpty() }.ifBlank { null }
}
