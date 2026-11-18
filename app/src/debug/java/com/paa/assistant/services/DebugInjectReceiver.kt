package com.paa.assistant.services

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Debug only: `adb shell am broadcast -a com.paa.assistant.DEBUG_INJECT --es text "..." [--es chat X] [--ez group true] [--ez out true]`. */
@AndroidEntryPoint
class DebugInjectReceiver : BroadcastReceiver() {
    @Inject lateinit var processor: ChatMessageProcessor
    @Inject lateinit var repository: com.paa.assistant.data.repository.TaskRepository

    override fun onReceive(context: Context, intent: Intent) {
        // --ez reset true: clean slate for tools/eval_chats.py (chat tasks, chat memory, ✓/✗ examples, log)
        if (intent.getBooleanExtra("reset", false)) {
            // Wipes every chat task, the chat history and the owner's ✓/✗: emulator only. On 2026-11-19 a test
            // script sent this to the owner's phone and destroyed their feedback (tasks/history were restored).
            val emulator = android.os.Build.FINGERPRINT.contains("generic") || android.os.Build.HARDWARE in setOf("goldfish", "ranchu") ||
                android.os.Build.PRODUCT.contains("sdk")
            if (!emulator) { android.util.Log.e("PAA_Debug", "reset refused: not an emulator"); return }
            CoroutineScope(Dispatchers.IO).launch {
                repository.deleteAllChatTasks()
                com.paa.assistant.data.db.AppDatabase.getInstance(context).openHelper.writableDatabase.apply {
                    execSQL("DELETE FROM chat_history")
                    execSQL("DELETE FROM task_feedback")
                }
                DetectionLog.init(context)
                DetectionLog.clear()
            }
            return
        }
        // --es delete_ids "1,2,3": delete exactly these tasks (owner-approved cleanup) and their alarms
        intent.getStringExtra("delete_ids")?.let { csv ->
            val ids = csv.split(",").mapNotNull { it.trim().toLongOrNull() }
            val pending = goAsync()
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    val db = com.paa.assistant.data.db.AppDatabase.getInstance(context).openHelper.writableDatabase
                    ids.forEach { id ->
                        com.paa.assistant.core.reminders.ReminderScheduler.cancel(context, id)
                        db.execSQL("DELETE FROM tasks WHERE id = ?", arrayOf<Any>(id))
                    }
                    android.util.Log.i("PAA_Debug", "deleted tasks ${ids.joinToString(",")}")
                } finally { pending.finish() }
            }
            return
        }
        // --es delete_source_like X: delete tasks whose source contains X (leftover test scenarios) and their alarms
        intent.getStringExtra("delete_source_like")?.takeIf { it.length >= 4 }?.let { like ->
            val pending = goAsync()
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    val db = com.paa.assistant.data.db.AppDatabase.getInstance(context).openHelper.writableDatabase
                    val ids = mutableListOf<Long>()
                    db.query("SELECT id FROM tasks WHERE sourceApp LIKE ?", arrayOf<Any>("%$like%")).use { c -> while (c.moveToNext()) ids += c.getLong(0) }
                    ids.forEach { com.paa.assistant.core.reminders.ReminderScheduler.cancel(context, it) }
                    db.execSQL("DELETE FROM tasks WHERE sourceApp LIKE ?", arrayOf<Any>("%$like%"))
                    db.execSQL("DELETE FROM chat_history WHERE chatKey LIKE ?", arrayOf<Any>("%$like%"))
                    android.util.Log.i("PAA_Debug", "deleted ${ids.size} tasks (and chat lines) like $like")
                } finally { pending.finish() }
            }
            return
        }
        // --ez restore true: put back tasks, chat lines and log entries from files/restore.json (same shape as dumpall),
        // then delete that file
        if (intent.getBooleanExtra("restore", false)) {
            val pending = goAsync()
            CoroutineScope(Dispatchers.IO).launch {
                val f = java.io.File(context.filesDir, "restore.json")
                try {
                    val j = org.json.JSONObject(f.readText())
                    val db = com.paa.assistant.data.db.AppDatabase.getInstance(context).openHelper.writableDatabase
                    fun v(o: org.json.JSONObject, k: String): Any? = if (o.isNull(k)) null else o.getString(k)
                    val tasks = j.getJSONArray("tasks")
                    db.beginTransaction()
                    try {
                        for (i in 0 until tasks.length()) {
                            val t = tasks.getJSONObject(i)
                            db.execSQL(
                                "INSERT OR REPLACE INTO tasks (id,title,status,priority,dueTimestamp,tags,sourceApp,sourceMessage,aiReason," +
                                    "createdAt,updatedAt,category,reminderMinutesBefore,geofenceRadiusMeters,geofenceTransition) " +
                                    "VALUES (?,?,?,?,?,?,?,?,?,?,?,'general',0,150,1)",
                                arrayOf(v(t, "id"), v(t, "title"), v(t, "status"), v(t, "priority"), v(t, "dueTimestamp"), v(t, "tags"),
                                    v(t, "sourceApp"), v(t, "sourceMessage"), v(t, "aiReason"), v(t, "createdAt"), v(t, "updatedAt"))
                            )
                        }
                        val chat = j.getJSONArray("chat")
                        for (i in 0 until chat.length()) {
                            val c = chat.getJSONObject(i)
                            db.execSQL(
                                "INSERT INTO chat_history (chatKey,line,createdAt,pending,isGroup) VALUES (?,?,?,?,?)",
                                arrayOf(v(c, "chatKey"), v(c, "line"), v(c, "createdAt"), v(c, "pending"), v(c, "isGroup"))
                            )
                        }
                        db.setTransactionSuccessful()
                    } finally { db.endTransaction() }
                    DetectionLog.init(context)
                    DetectionLog.restore(j.getJSONArray("log").toString())
                    android.util.Log.i("PAA_Debug", "restored ${tasks.length()} tasks, ${j.getJSONArray("chat").length()} chat lines")
                } catch (e: Exception) {
                    android.util.Log.e("PAA_Debug", "restore failed: ${e.javaClass.simpleName}: ${e.message}")
                } finally { f.delete(); pending.finish() }
            }
            return
        }
        // --ez dumpall true: tasks (with source message + AI reason), saved chat lines and the detection log, as one
        // plaintext JSON in the app's private files (pull with run-as, then delete) — for reviewing faults
        if (intent.getBooleanExtra("dumpall", false)) {
            val pending = goAsync()
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    DetectionLog.init(context)
                    val db = com.paa.assistant.data.db.AppDatabase.getInstance(context).openHelper.readableDatabase
                    fun rows(sql: String): org.json.JSONArray = org.json.JSONArray().also { arr ->
                        db.query(sql).use { c ->
                            while (c.moveToNext()) arr.put(org.json.JSONObject().also { o ->
                                for (i in 0 until c.columnCount) o.put(c.getColumnName(i), if (c.isNull(i)) org.json.JSONObject.NULL else c.getString(i))
                            })
                        }
                    }
                    val since = System.currentTimeMillis() - 45L * 24 * 3600 * 1000
                    val out = org.json.JSONObject()
                        .put("tasks", rows("SELECT id,title,status,priority,dueTimestamp,tags,sourceApp,sourceMessage,aiReason,createdAt,updatedAt FROM tasks WHERE createdAt > $since ORDER BY createdAt"))
                        .put("chat", rows("SELECT chatKey,line,createdAt,pending,isGroup FROM chat_history ORDER BY createdAt"))
                        .put("log", org.json.JSONArray(DetectionLog.json()))
                    java.io.File(context.filesDir, "dumpall_debug.json").writeText(out.toString())
                } finally { pending.finish() }
            }
            return
        }
        // --ez dumplog true: plaintext copy of the (encrypted) detection log for tools/eval_chats.py
        if (intent.getBooleanExtra("dumplog", false)) {
            DetectionLog.init(context)
            java.io.File(context.filesDir, "detection_log_debug.json").writeText(DetectionLog.json())
            return
        }
        val text = intent.getStringExtra("text") ?: return
        // Test chats are made-up names: treat them as people unless the test says --ez business true
        if (!intent.getBooleanExtra("business", false)) SenderKinds.testPeople += (intent.getStringExtra("chat") ?: "Rahul").lowercase()
        // Not goAsync(): the AI takes ~1 min, far beyond the broadcast time limit (→ ANR kill)
        CoroutineScope(Dispatchers.Default).launch {
            run {
                DetectionLog.init(context)
                processor.process(
                    ChatMessage(
                        text = text, app = ChatApp.WHATSAPP,
                        outgoing = intent.getBooleanExtra("out", false),
                        chatName = intent.getStringExtra("chat") ?: "Rahul",
                        sender = intent.getStringExtra("sender"),
                        isGroup = intent.getBooleanExtra("group", false)
                    )
                )
            }
        }
    }
}
