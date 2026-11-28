package com.paa.assistant.core.money

import com.paa.assistant.core.agent.Action
import java.util.Locale

/**
 * P3-T6 "order my usual X". Pure rules only: phrase parsing, label matching and rebuilding the order. A saved order
 * is just the owner's last confirmed [Action.PlaceOrder] under a label; reordering builds a fresh PlaceOrder that
 * goes through Guard + the fingerprint confirm again (nothing is ever paid from memory alone).
 */
object SavedOrders {
    /** Label used when the owner says just "my usual". */
    const val DEFAULT_LABEL = "usual"

    data class Saved(
        val label: String, val app: String, val cartSummary: String, val amountPaise: Long, val upiApp: String?,
        val savedAt: Long, val timesUsed: Int = 0,
    )

    private val SAVE = Regex(
        "^(?:please |pls |ok |okay )?(?:save|remember|keep|store) (?:this|that|it|this order|that order|the last order|my last order)" +
            " as (?:my |the )?usual(?: (.+?))?(?: order)?(?: please| pls)?$"
    )
    private val REORDER = Regex(
        "^(?:please |pls |can you |could you )?(?:order|get|reorder|repeat|book)(?: me)? (?:my|the) usual(?: (.+?))?" +
            "(?: order)?(?: again)?(?: please| pls)?$"
    )

    private fun clean(text: String): String =
        text.lowercase(Locale.ENGLISH).replace(Regex("[^a-z0-9 ]"), " ").replace(Regex("\\s+"), " ").trim()

    /** "Breakfast", " my  breakfast!" → "breakfast"; blank → [DEFAULT_LABEL]. */
    fun normalizeLabel(raw: String?): String {
        val t = clean(raw ?: "").removePrefix("my ").removePrefix("the ").removeSuffix(" order").trim()
        return t.ifEmpty { DEFAULT_LABEL }
    }

    /** "save this as my usual breakfast" → "breakfast"; null when it isn't a save command. */
    fun parseSave(text: String): String? = SAVE.matchEntire(clean(text))?.let { normalizeLabel(it.groupValues[1]) }

    /** "order my usual breakfast" → "breakfast", "order my usual" → [DEFAULT_LABEL]; null when not a reorder. */
    fun parseReorder(text: String): String? = REORDER.matchEntire(clean(text))?.let { normalizeLabel(it.groupValues[1]) }

    /**
     * The saved order the owner means: exact label first; plain "usual" with one saved order → that one; else the one
     * label that contains (or is contained in) the wanted words. Ambiguous or nothing → null (PAA asks instead).
     */
    fun find(saved: List<Saved>, wanted: String): Saved? {
        val w = normalizeLabel(wanted)
        saved.firstOrNull { it.label == w }?.let { return it }
        if (w == DEFAULT_LABEL) return saved.singleOrNull()
        val near = saved.filter { it.label.contains(w) || w.contains(it.label) }
        return near.singleOrNull()
    }

    /** Remember a confirmed order; refuses orders PAA could not hand off anyway. */
    fun save(order: Action.PlaceOrder, label: String, now: Long): Saved? {
        if (order.amountPaise <= 0 || order.amountPaise > PaymentHandoff.MAX_PAISE || order.cartSummary.isBlank()) return null
        return Saved(normalizeLabel(label), order.app, order.cartSummary.trim(), order.amountPaise, order.upiApp, now)
    }

    /** Rebuild the order from memory; the summary says the price is last time's (the merchant shows today's). */
    fun rebuild(s: Saved): Action.PlaceOrder =
        Action.PlaceOrder(s.app, "${s.cartSummary} (your usual ${s.label}; last time's price)", s.amountPaise, s.upiApp)

    /** Reply when the owner asks for a usual that isn't saved (or is ambiguous). */
    fun notFound(saved: List<Saved>, wanted: String): String =
        if (saved.isEmpty()) "You have no usual orders yet. After an order, say \"save this as my usual ${normalizeLabel(wanted)}\"."
        else "Which usual? I have: ${saved.joinToString(", ") { it.label }}."
}
