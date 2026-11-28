package com.paa.assistant.core.money

import com.paa.assistant.core.agent.Action
import java.util.Locale

/**
 * The shared "now you pay" step (P3-T1). Pure rules only — [com.paa.assistant.core.skills.PaymentSkill] does the
 * Android part. PAA never types a PIN/CVV/OTP and never drives a UPI or bank app: it only opens the owner's chosen
 * UPI app with its normal launch intent (or leaves the merchant app on its own payment step) and stops there.
 */
object PaymentHandoff {
    const val PIN_NOTE = "You'll type your UPI PIN yourself."

    /** Well-known UPI apps the owner may pick; anything else is refused (no banking apps, no unknown packages). */
    val UPI_APPS = mapOf(
        "com.google.android.apps.nbu.paisa.user" to "Google Pay",
        "com.phonepe.app" to "PhonePe",
        "net.one97.paytm" to "Paytm",
        "in.org.npci.upiapp" to "BHIM",
        "in.amazon.mShop.android.shopping" to "Amazon Pay",
    )

    sealed class Step {
        /** Open this UPI app for the owner, then stop. */
        data class OpenUpi(val packageName: String, val label: String, val message: String) : Step()
        /** Let the merchant app carry on to its own payment screen; PAA does nothing more. */
        data class StayInMerchant(val message: String) : Step()
        data class Refuse(val reason: String) : Step()
    }

    /** Max single order PAA will hand off (₹1,00,000); bigger amounts are almost surely a parse error. */
    const val MAX_PAISE = 1_00_000_00L

    /** P3-T7 per-order cap (owner can raise it): above this PAA warns and asks for a second, explicit confirm. */
    const val DEFAULT_CAP_PAISE = 2_000_00L

    /**
     * The owner's typed cap in whole rupees ("5000", "₹5,000", "5,000 rs") → paise; null when it isn't a whole
     * number from ₹100 to ₹1,00,000 (the cap can be moved, never removed).
     */
    fun parseCapRupees(text: String): Long? {
        val t = text.trim().lowercase().removePrefix("₹").removePrefix("rs").removePrefix(".")
            .removeSuffix("rupees").removeSuffix("rs").removeSuffix("₹").trim().replace(",", "")
        if (t.isEmpty() || !t.all { it.isDigit() } || t.length > 7) return null
        val paise = t.toLong() * 100
        return if (paise in 100_00L..MAX_PAISE) paise else null
    }

    /** True when [amountPaise] is above the owner's cap (a cap <= 0 falls back to the default, never "no cap"). */
    fun overCap(amountPaise: Long, capPaise: Long): Boolean =
        amountPaise > (if (capPaise > 0) capPaise else DEFAULT_CAP_PAISE)

    /** Warning on the confirm sheet for an order above the cap; null when under it. */
    fun capWarning(amountPaise: Long, capPaise: Long): String? =
        if (!overCap(amountPaise, capPaise)) null
        else "${rupees(amountPaise)} is above your ${rupees(if (capPaise > 0) capPaise else DEFAULT_CAP_PAISE)} limit. Check the amount, then confirm again."

    fun rupees(paise: Long): String {
        val r = paise / 100
        val p = paise % 100
        val s = r.toString()
        // Indian grouping: last 3 digits, then pairs (1,23,456).
        val grouped = if (s.length <= 3) s else {
            val head = s.dropLast(3)
            head.reversed().chunked(2).joinToString(",").reversed() + "," + s.takeLast(3)
        }
        return "₹$grouped" + if (p != 0L) String.format(Locale.ENGLISH, ".%02d", p) else ""
    }

    /** What the owner sees before paying: summary, amount, and the PIN note. Built from typed fields only. */
    fun sheetText(order: Action.PlaceOrder): String =
        "${order.app}: ${order.cartSummary.trim()}\nTotal ${rupees(order.amountPaise)}\n$PIN_NOTE"

    /** Decide the hand-off for an order that already passed Guard + fingerprint (only the Executor calls the skill). */
    fun plan(order: Action.PlaceOrder, upiAppPkg: String?, installed: (String) -> Boolean): Step {
        if (order.amountPaise <= 0) return Step.Refuse("The amount looks wrong, so I stopped.")
        if (order.amountPaise > MAX_PAISE) return Step.Refuse("${rupees(order.amountPaise)} is more than I hand off; pay it yourself.")
        if (order.cartSummary.isBlank()) return Step.Refuse("I don't know what's in the cart, so I stopped.")
        val amount = rupees(order.amountPaise)
        if (upiAppPkg.isNullOrBlank()) {
            return Step.StayInMerchant("Pay $amount in ${order.app}. $PIN_NOTE")
        }
        val label = UPI_APPS[upiAppPkg] ?: return Step.Refuse("I only open known UPI apps.")
        if (!installed(upiAppPkg)) return Step.Refuse("$label isn't installed.")
        return Step.OpenUpi(upiAppPkg, label, "Opening $label for $amount. $PIN_NOTE")
    }
}
