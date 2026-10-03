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

    override fun onReceive(context: Context, intent: Intent) {
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
