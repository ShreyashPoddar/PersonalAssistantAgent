package com.paa.assistant.core.money

import java.util.Locale

/** Labels a transaction (P2-T5). The owner's own corrections ([learned]: counterparty key → category) always win. */
object Categorizer {
    val CATEGORIES = listOf("Food", "Groceries", "Travel", "Shopping", "Bills", "Rent", "Education", "Entertainment",
        "Health", "Transfer", "Income", "Cash", "Uncategorised")

    private val RULES = listOf(
        "Food" to Regex("zomato|swiggy|eatsure|dominos|domino's|pizza|kfc|mcdonald|burger|starbucks|chai|cafe|restaurant|biryani|canteen|mess|bakery"),
        "Groceries" to Regex("blinkit|zepto|instamart|bigbasket|dmart|jiomart|grocer|kirana|supermarket|more retail|reliance fresh"),
        "Travel" to Regex("irctc|uber|ola|rapido|redbus|makemytrip|goibibo|indigo|air ?india|vistara|metro|fastag|petrol|fuel|hpcl|bpcl|iocl|cleartrip|ixigo|abhibus"),
        "Shopping" to Regex("amazon|flipkart|myntra|ajio|meesho|nykaa|croma|decathlon|style union|westside|zudio|lifestyle|tata cliq"),
        "Bills" to Regex("electricity|bescom|tneb|tangedco|recharge|jio|airtel|\\bvi\\b|vodafone|bsnl|broadband|act fibernet|dth|tata play|gas|water bill|bill ?desk|postpaid"),
        "Rent" to Regex("\\brent\\b|landlord|nobroker|housing|pg rent|hostel"),
        "Education" to Regex("srm|college|university|fees|udemy|coursera|unacademy|byju|exam fee|book ?store"),
        "Entertainment" to Regex("netflix|spotify|hotstar|prime video|bookmyshow|pvr|inox|youtube premium|steam|playstation"),
        "Health" to Regex("pharm|apollo|medplus|1mg|netmeds|hospital|clinic|diagnostic|lab test|practo"),
    )

    /** Normalised key for a counterparty, used for the owner's rules ("SWIGGY*ORDER 123" → "swiggy order"). */
    fun key(counterparty: String?): String =
        counterparty.orEmpty().lowercase(Locale.ROOT).replace(Regex("[^a-z@.]+"), " ").replace(Regex("\\b\\d+\\b"), " ").trim()
            .split(Regex("\\s+")).take(3).joinToString(" ")

    fun categorize(t: Txn, learned: Map<String, String>): String {
        learned[key(t.counterparty)]?.let { return it }
        if (t.channel == "ATM") return "Cash"
        val c = t.counterparty.orEmpty().lowercase(Locale.ROOT)
        RULES.firstOrNull { it.second.containsMatchIn(c) }?.let { return it.first }
        if (t.direction == "CREDIT") return if (looksLikePerson(t.counterparty)) "Transfer" else "Income"
        return if (looksLikePerson(t.counterparty)) "Transfer" else "Uncategorised"
    }

    /** A personal VPA (name.x@okaxis) or a plain two-word name, not a business. */
    fun looksLikePerson(cp: String?): Boolean {
        val c = cp?.trim().orEmpty()
        if (c.isEmpty()) return false
        if ('@' in c) return !Regex("pay|merchant|razorpay|paytm-|bharatpe|\\.rzp|@ybl.*shop|store|mart", RegexOption.IGNORE_CASE).containsMatchIn(c)
        val words = c.split(Regex("\\s+"))
        return words.size in 1..3 && words.all { it.length in 2..15 && it.all(Char::isLetter) } &&
            !Regex("pvt|ltd|limited|store|services|india|enterprises|traders|foods|retail", RegexOption.IGNORE_CASE).containsMatchIn(c)
    }
}
