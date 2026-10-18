package com.paa.assistant.core.agent

import com.paa.assistant.data.db.ContactRuleDao
import com.paa.assistant.data.models.ContactRuleEntity

/** The "auto-reply on a call" contacts setting, mirrored into contact rules (the Guard reads the rules). */
object AutoReplyRules {
    suspend fun sync(dao: ContactRuleDao, chatNames: List<String>) {
        val keys = chatNames.map { it.trim().lowercase() }.filter { it.isNotEmpty() }.toSet()
        for (k in keys) {
            val r = dao.get(k) ?: ContactRuleEntity(contactKey = k)
            if (!r.autoReplyDuringCall) dao.upsert(r.copy(autoReplyDuringCall = true, updatedAt = System.currentTimeMillis()))
        }
        for (r in dao.autoReplyOn()) {
            if (r.contactKey !in keys) dao.upsert(r.copy(autoReplyDuringCall = false, updatedAt = System.currentTimeMillis()))
        }
    }
}
