package com.paa.assistant.core.skills

import android.content.Context
import android.content.Intent
import android.net.Uri
import com.paa.assistant.core.agent.Action
import com.paa.assistant.core.agent.ActionResult
import com.paa.assistant.core.agent.Skill
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/** Email (P2-T3): opens Gmail's compose screen filled in; the owner's own tap on Send sends it. No OAuth, no new scopes. */
@Singleton
class EmailSkill @Inject constructor(@ApplicationContext private val ctx: Context) : Skill<Action.SendEmail> {
    override suspend fun run(action: Action.SendEmail): ActionResult {
        val uri = Uri.parse("mailto:" + Uri.encode(action.to) + "?subject=" + Uri.encode(action.subject) + "&body=" + Uri.encode(action.body))
        val gmail = Intent(Intent.ACTION_SENDTO, uri).setPackage("com.google.android.gm").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val any = Intent(Intent.ACTION_SENDTO, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return runCatching { ctx.startActivity(gmail) }.recoverCatching { ctx.startActivity(any) }
            .fold({ ActionResult(true, "Email to ${action.to} is ready in Gmail — check it and tap Send.") }, { ActionResult(false, "No email app found.") })
    }

    companion object {
        private val EMAIL = Regex("^(?:please\\s+)?(?:write|send|draft)\\s+(?:an?\\s+)?(?:e-?mail|mail)\\s+to\\s+([\\w.+-]+@[\\w.-]+\\.[a-z]{2,})\\s+(?:saying|that|about|:)\\s*(.+)$", RegexOption.IGNORE_CASE)

        /** "write an email to a@b.com saying I'll join at 4" → (to, subject, body). */
        fun parse(said: String): Triple<String, String, String>? {
            val m = EMAIL.find(said.trim().trimEnd('.')) ?: return null
            val body = m.groupValues[2].trim().replaceFirstChar { it.uppercase() }
            val subject = body.split(Regex("\\s+")).take(6).joinToString(" ").trimEnd(',', '.')
            return Triple(m.groupValues[1], subject, body)
        }
    }
}
