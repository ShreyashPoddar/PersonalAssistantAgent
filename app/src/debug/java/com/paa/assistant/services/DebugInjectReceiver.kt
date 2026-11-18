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
