package com.paa.assistant.core.money

import com.paa.assistant.core.agent.Action
import com.paa.assistant.data.db.AuditDao
import com.paa.assistant.data.models.AuditEntryEntity
import javax.inject.Inject
import javax.inject.Singleton

/**
 * After a hand-off (P3-T1) the owner pays in the UPI or merchant app; PAA reads that app's next notification to
 * log whether the order went through. Pure rules only. Notification text is matched, never stored or logged: the
 * audit line is built from the order's own typed fields.
 */
object OrderConfirmation {
    /** Watch for a confirmation this long after the hand-off. */
    const val WINDOW_MS = 30 * 60 * 1000L

    /** Merchant apps by the label PlaceOrder carries (lower-case) → package. */
    val MERCHANT_APPS = mapOf(
        "swiggy" to "in.swiggy.android",
        "zomato" to "com.application.zomato",
        "blinkit" to "com.grofers.customerapp",
        "zepto" to "com.zeptoconsumerapp",
        "amazon" to "in.amazon.mShop.android.shopping",
        "flipkart" to "com.flipkart.android",
        "myntra" to "com.myntra.android",
    )

    enum class Outcome { PAID, FAILED, PENDING }

    data class Pending(val order: Action.PlaceOrder, val packages: Set<String>, val startedAt: Long)

    fun pendingFor(order: Action.PlaceOrder, now: Long): Pending {
        val merchant = MERCHANT_APPS[order.app.trim().lowercase()] ?: order.app.trim().takeIf { '.' in it }
        return Pending(order, setOfNotNull(merchant, order.upiApp?.takeIf { it.isNotBlank() }), now)
    }

    // Failure words first: "payment failed" must not count as "payment".
    private val FAILED = Regex(
        """\b(failed|failure|declined|unsuccessful|not successful|cancelled|canceled|could ?n[o']t be (?:placed|processed)|reversed|refund(?:ed)? initiated)\b""",
        RegexOption.IGNORE_CASE
    )
    private val PENDING = Regex("""\b(pending|processing|in progress|awaiting)\b""", RegexOption.IGNORE_CASE)
    private val PAID = Regex(
        """\b(order (?:placed|confirmed|received)|payment (?:successful|received|done|complete[d]?)|paid|successfully|success|is confirmed|on (?:its|the) way|being prepared)\b""",
        RegexOption.IGNORE_CASE
    )
    private val AMOUNT = Regex("""(?:₹|rs\.?|inr)\s*([0-9][0-9,]*(?:\.[0-9]{1,2})?)""", RegexOption.IGNORE_CASE)

    fun classify(text: String): Outcome? = when {
        FAILED.containsMatchIn(text) -> Outcome.FAILED
        PENDING.containsMatchIn(text) -> Outcome.PENDING
        PAID.containsMatchIn(text) -> Outcome.PAID
        else -> null
    }

    /** Every rupee amount in the text, in paise. */
    fun amountsPaise(text: String): List<Long> = AMOUNT.findAll(text).mapNotNull { m ->
        val s = m.groupValues[1].replace(",", "")
        val parts = s.split('.')
        val r = parts[0].toLongOrNull() ?: return@mapNotNull null
        val p = parts.getOrNull(1)?.padEnd(2, '0')?.toLongOrNull() ?: 0L
        r * 100 + p
    }.toList()

    /**
     * The outcome this notification reports for [pending], or null when it isn't about it: other app, outside the
     * window, no outcome words, or it names amounts and none is within ₹1 of the order (delivery fees round).
     */
    fun match(pending: Pending, pkg: String, text: String, postTime: Long): Outcome? {
        if (pkg !in pending.packages) return null
        if (postTime < pending.startedAt || postTime - pending.startedAt > WINDOW_MS) return null
        val outcome = classify(text) ?: return null
        val amounts = amountsPaise(text)
        if (amounts.isNotEmpty() && amounts.none { kotlin.math.abs(it - pending.order.amountPaise) <= 100 }) return null
        return outcome
    }

    fun message(pending: Pending, outcome: Outcome): String {
        val what = "${pending.order.app}, ${PaymentHandoff.rupees(pending.order.amountPaise)}"
        return when (outcome) {
            Outcome.PAID -> "Order paid: $what"
            Outcome.FAILED -> "Payment didn't go through: $what"
            Outcome.PENDING -> "Payment pending: $what"
        }
    }
}

/** Holds the one order waiting for its confirmation; the notification listener feeds it. */
@Singleton
class OrderConfirmationTracker(private val audit: AuditDao, private val now: () -> Long) {
    @Inject constructor(audit: AuditDao) : this(audit, System::currentTimeMillis)

    @Volatile private var pending: OrderConfirmation.Pending? = null

    @Volatile private var last: Pair<Action.PlaceOrder, Long>? = null

    fun expect(order: Action.PlaceOrder) {
        pending = OrderConfirmation.pendingFor(order, now())
        last = order to now()
    }

    /** The last order PAA handed off, if within [maxAgeMs] — what "save this as my usual X" means. */
    fun lastHandedOff(maxAgeMs: Long = LAST_ORDER_MS): Action.PlaceOrder? =
        last?.takeIf { now() - it.second <= maxAgeMs }?.first

    companion object { const val LAST_ORDER_MS = 6 * 60 * 60 * 1000L }

    /** True when [pkg] might confirm the waiting order (cheap check before reading the notification). */
    fun watching(pkg: String): Boolean {
        val p = pending ?: return false
        if (now() - p.startedAt > OrderConfirmation.WINDOW_MS) { pending = null; return false }
        return pkg in p.packages
    }

    /** Logs PAID/FAILED and stops watching; PENDING is logged and keeps watching for the final word. */
    suspend fun onNotification(pkg: String, text: String, postTime: Long): OrderConfirmation.Outcome? {
        val p = pending ?: return null
        val outcome = OrderConfirmation.match(p, pkg, text, postTime) ?: return null
        if (outcome != OrderConfirmation.Outcome.PENDING) pending = null
        audit.insert(
            AuditEntryEntity(
                at = now(), actionType = "OrderConfirmation", tier = "MONEY", source = "NOTIF",
                summary = p.order.summary(), verdict = outcome.name,
                ok = outcome == OrderConfirmation.Outcome.PAID, resultMessage = OrderConfirmation.message(p, outcome)
            )
        )
        return outcome
    }
}
