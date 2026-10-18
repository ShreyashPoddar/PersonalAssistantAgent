package com.paa.assistant.core.agent

import com.paa.assistant.data.db.AuditDao
import com.paa.assistant.data.db.ContactRuleDao
import com.paa.assistant.data.models.AuditEntryEntity
import com.paa.assistant.data.models.ContactRuleEntity
import com.paa.assistant.services.DetectionLog
import javax.inject.Inject
import javax.inject.Singleton

/** The ONLY entry point for doing things: Guard → confirm → skill → audit. Returns the result or why it didn't run. */
@Singleton
class Executor internal constructor(
    private val check: suspend (Action, CommandSource, Boolean, Boolean) -> Verdict,
    private val ask: suspend (Action, CommandSource, Boolean) -> Boolean,
    private val skillFor: (Action) -> Skill<Action>?,
    private val audit: AuditDao,
    private val rules: ContactRuleDao,
    private val log: (String, String, String) -> Unit = DetectionLog::add,
    private val now: () -> Long = System::currentTimeMillis
) {
    @Inject constructor(guard: Guard, broker: ConfirmBroker, skills: SkillRegistry, audit: AuditDao, rules: ContactRuleDao) :
        this(guard::check, broker::ask, skills::forAction, audit, rules)

    suspend fun submit(
        action: Action, source: CommandSource, preConfirmed: Boolean = false, voiceVerified: Boolean = false
    ): ActionResult {
        val verdict = check(action, source, preConfirmed, voiceVerified)
        val approved = when (verdict) {
            Verdict.Allow -> true
            is Verdict.Deny -> return record(action, source, verdict, ActionResult(false, verdict.reason))
            is Verdict.Confirm -> ask(action, source, verdict.biometric)
        }
        if (!approved) return record(action, source, verdict, ActionResult(false, "Cancelled"))
        val skill = skillFor(action) ?: return record(action, source, verdict, ActionResult(false, "Not supported yet"))
        val result = runCatching { skill.run(action) }.getOrElse { ActionResult(false, "Failed: ${it.javaClass.simpleName}") }
        if (result.ok) Guard.contactKey(action)?.let { markMessaged(it) }
        return record(action, source, verdict, result)
    }

    private suspend fun record(action: Action, source: CommandSource, verdict: Verdict, result: ActionResult): ActionResult {
        val type = action::class.simpleName ?: "Action"
        runCatching {
            audit.insert(
                AuditEntryEntity(
                    at = now(), actionType = type, tier = action.tier.name, source = source.name,
                    summary = action.summary(), verdict = verdictName(verdict), ok = result.ok, resultMessage = result.message
                )
            )
        }
        // No summary here: the detection log is for debugging and must not hold message bodies.
        log("agent", type, if (result.ok) "ok" else result.message)
        return result
    }

    private suspend fun markMessaged(key: String) {
        val rule = rules.get(key) ?: ContactRuleEntity(contactKey = key)
        if (!rule.messagedBefore) rules.upsert(rule.copy(messagedBefore = true, updatedAt = now()))
    }

    companion object {
        fun verdictName(v: Verdict): String = when (v) {
            Verdict.Allow -> "ALLOW"
            is Verdict.Confirm -> if (v.biometric) "CONFIRM_BIOMETRIC" else "CONFIRM"
            is Verdict.Deny -> "DENY"
        }
    }
}
