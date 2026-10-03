package com.paa.assistant.services

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Record of the last chat messages PAA checked and what it decided, shown in the app so the user
 * can see why something was (or wasn't) scheduled.
 *
 * Kept in the app's private storage, encrypted with the same Keystore key as the database (it holds
 * message snippets), so it survives a crash — if the last entry says "⏳ checking…" with no result,
 * the app died while processing that message.
 */
object DetectionLog {
    data class Entry(val time: Long, val source: String, val snippet: String, val result: String)

    private const val MAX = 40
    private const val FILE = "detection_log.enc"
    /** Plaintext file from before encryption; converted once, then deleted. */
    private const val OLD_FILE = "detection_log.json"
    private val _entries = MutableStateFlow<List<Entry>>(emptyList())
    val entries: StateFlow<List<Entry>> = _entries
    private var file: File? = null

    /** Loads the saved log; call once at app start. */
    fun init(context: Context) {
        synchronized(this) {
            if (file != null) return
            file = File(context.filesDir, FILE)
            val old = File(context.filesDir, OLD_FILE)
            _entries.value = runCatching {
                val json = if (file!!.exists()) com.paa.assistant.data.db.DatabaseEncryption.decrypt(file!!.readText()) else old.readText()
                val arr = JSONArray(json)
                (0 until arr.length()).map { i ->
                    arr.getJSONObject(i).let { Entry(it.getLong("t"), it.getString("s"), it.getString("m"), it.getString("r")) }
                }
            }.getOrDefault(emptyList())
            if (old.exists()) { save(); old.delete() }
        }
    }

    fun add(source: String, text: String, result: String) {
        val snippet = text.replace(Regex("\\s+"), " ").trim().let { if (it.length > 60) it.take(57) + "…" else it }
        synchronized(this) {
            _entries.value = (listOf(Entry(System.currentTimeMillis(), source, snippet, result)) + _entries.value).take(MAX)
            save()
        }
    }

    /** Plain JSON of the entries (debug tooling only — see DebugInjectReceiver). */
    fun json(): String = JSONArray().also { arr ->
        _entries.value.forEach { e -> arr.put(JSONObject().put("t", e.time).put("s", e.source).put("m", e.snippet).put("r", e.result)) }
    }.toString()

    fun clear() {
        synchronized(this) { _entries.value = emptyList(); save() }
    }

    private fun save() {
        val f = file ?: return
        val arr = JSONArray()
        _entries.value.forEach { e -> arr.put(JSONObject().put("t", e.time).put("s", e.source).put("m", e.snippet).put("r", e.result)) }
        runCatching { f.writeText(com.paa.assistant.data.db.DatabaseEncryption.encrypt(arr.toString())) }
    }
}
