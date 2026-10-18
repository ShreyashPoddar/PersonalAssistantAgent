package com.paa.assistant.core.agent

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** How much harm an action can do; [Guard] decides by tier. */
enum class RiskTier { READ, PRIVATE_REVERSIBLE, OUTWARD, MONEY, SOS }

/** A typed thing PAA can do. Every skill takes one; every run goes through the Executor. */
sealed class Action(val tier: RiskTier) {
    /** One line shown to the owner on the confirm screen, built from typed fields only (never model text). */
    abstract fun summary(): String

    data class OpenApp(val packageName: String, val label: String) : Action(RiskTier.READ) {
        override fun summary() = "Open $label"
    }
    data class SetAlarm(val atMillis: Long, val label: String) : Action(RiskTier.PRIVATE_REVERSIBLE) {
        override fun summary() = "Alarm \"$label\" at ${fmt(atMillis)}"
    }
    data class SendMessage(val app: String, val contactId: String, val contactName: String, val body: String) :
        Action(RiskTier.OUTWARD) { override fun summary() = "$app to $contactName: \"$body\"" }
    data class SendSms(val number: String, val contactName: String, val body: String) : Action(RiskTier.OUTWARD) {
        override fun summary() = "SMS to $contactName: \"$body\""
    }
    data class SendEmail(val to: String, val subject: String, val body: String) : Action(RiskTier.OUTWARD) {
        override fun summary() = "Email to $to: \"$subject\""
    }
    data class PlaceCall(val number: String, val contactName: String) : Action(RiskTier.OUTWARD) {
        override fun summary() = "Call $contactName"
    }
    data class PlaceOrder(val app: String, val cartSummary: String, val amountPaise: Long) : Action(RiskTier.MONEY) {
        override fun summary() = "Order on $app: $cartSummary — ₹${rupees(amountPaise)}"
    }
    data class Sos(val reason: String) : Action(RiskTier.SOS) { override fun summary() = "SOS" }
    // later phases add more subclasses here
}

private fun fmt(atMillis: Long): String =
    SimpleDateFormat("EEE d MMM, h:mm a", Locale.ENGLISH).format(Date(atMillis))

private fun rupees(paise: Long): String = String.format(Locale.ENGLISH, "%.2f", paise / 100.0)
