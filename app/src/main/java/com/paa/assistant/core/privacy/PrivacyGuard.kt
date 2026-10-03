package com.paa.assistant.core.privacy

import com.paa.assistant.data.models.TaskEntity

/**
 * Privacy boundary for chat-derived data.
 *
 * Tasks created from WhatsApp / Telegram / Gmail notifications or typed chat text are
 * stored with a sourceApp starting with [CHAT_SOURCE_PREFIX]. They must never be sent
 * to Gemini, Tavily, or any other network service — only processed on-device.
 */
object PrivacyGuard {
    const val CHAT_SOURCE_PREFIX = "WhatsApp"

    fun isLocalOnly(task: TaskEntity): Boolean = task.sourceApp.startsWith(CHAT_SOURCE_PREFIX)

    /** Returns only the tasks that are safe to include in a cloud prompt. */
    fun cloudSafe(tasks: List<TaskEntity>): List<TaskEntity> = tasks.filterNot(::isLocalOnly)
}
