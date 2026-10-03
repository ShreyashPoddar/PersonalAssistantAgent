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
