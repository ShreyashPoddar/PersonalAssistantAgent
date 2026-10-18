package com.paa.assistant.core.agent

import com.paa.assistant.data.db.ContactRuleDao
import com.paa.assistant.data.models.ContactRuleEntity
import javax.inject.Inject
import javax.inject.Singleton

sealed class Verdict {
    object Allow : Verdict()
    data class Confirm(val biometric: Boolean) : Verdict()   // show ConfirmActivity
    data class Deny(val reason: String) : Verdict()
}

/** Decides whether an action may run, needs a tap, needs a fingerprint, or is refused. */
@Singleton
class Guard @Inject constructor(private val settings: AgentSettings, private val rules: ContactRuleDao) {

    /** [voiceVerified]: this spoken command followed an "Oyee PA" that matched the owner's voiceprint. */
    suspend fun check(action: Action, source: CommandSource, preConfirmed: Boolean = false, voiceVerified: Boolean = false): Verdict =
        decide(
            action, source, preConfirmed, settings.lockdown, settings.paused, voiceVerified, ruleFor(action),
            settings.trustAllContacts
        )

    private suspend fun ruleFor(a: Action) = contactKey(a)?.let { rules.get(it) }

    companion object {
        private val OWNER = setOf(CommandSource.OWNER_VOICE, CommandSource.OWNER_UI)

        /** Pure policy — unit-tested. Rules are checked in this order. */
        fun decide(
            a: Action, s: CommandSource, preConfirmed: Boolean, lockdown: Boolean, paused: Boolean,
            voiceVerified: Boolean, rule: ContactRuleEntity?, trustAll: Boolean = false
        ): Verdict {
            if (a.tier == RiskTier.SOS) return if (s in OWNER) Verdict.Allow else Verdict.Deny("SOS only from the owner")
            if (paused) return Verdict.Deny("PAA is paused")
            if (lockdown && a.tier != RiskTier.READ && a.tier != RiskTier.PRIVATE_REVERSIBLE) {
                return Verdict.Deny("Lockdown is on")
            }
            return when (a.tier) {
                RiskTier.READ, RiskTier.PRIVATE_REVERSIBLE -> Verdict.Allow
                RiskTier.OUTWARD -> outward(a, s, preConfirmed, voiceVerified, rule, trustAll)
                RiskTier.MONEY ->
                    if (s in OWNER) Verdict.Confirm(biometric = true) else Verdict.Deny("Payments only on the owner's command")
                RiskTier.SOS -> Verdict.Allow  // handled above
            }
        }

        private fun outward(
            a: Action, s: CommandSource, preConfirmed: Boolean, voiceVerified: Boolean, rule: ContactRuleEntity?, trustAll: Boolean
        ): Verdict {
            if (s == CommandSource.SYSTEM_EVENT) {
                // The existing "busy on a call" auto-reply.
                return if (rule?.autoReplyDuringCall == true && a is Action.SendMessage) Verdict.Allow
                else Verdict.Deny("Messages only on the owner's command")
            }
            val ownerVoice = s == CommandSource.OWNER_VOICE && voiceVerified
            val v = when {
                rule?.outward == "ALWAYS_ASK" -> Verdict.Confirm(biometric = true)
                // First time to this contact: fingerprint, unless the owner's verified voice asked or they trust all contacts
                !trustAll && !ownerVoice && (rule == null || !rule.messagedBefore) -> Verdict.Confirm(biometric = true)
                rule?.outward == "AUTO" -> Verdict.Allow
                else -> if (preConfirmed) Verdict.Allow else Verdict.Confirm(biometric = false)  // PREVIEW
            }
            // Spoken but not verified as the owner: anyone near the phone could have said it.
            if (s == CommandSource.OWNER_VOICE && !voiceVerified && (v == Verdict.Allow || v == Verdict.Confirm(false))) {
                return Verdict.Confirm(biometric = true)
            }
            return v
        }

        fun contactKey(a: Action): String? = when (a) {
            is Action.SendMessage -> a.contactName
            is Action.SendSms -> a.number
            is Action.PlaceCall -> a.number
            is Action.SendEmail -> a.to
            else -> null
        }?.trim()?.lowercase()
    }
}
