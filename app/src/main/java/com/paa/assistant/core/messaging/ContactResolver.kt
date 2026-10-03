package com.paa.assistant.core.messaging

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.ContactsContract
import androidx.core.content.ContextCompat

/** A phone contact matched from a spoken name ("Riya", "riya ece"). */
data class Contact(val name: String, val phoneE164: String)

/**
 * Finds the contact the owner means. Exact name first, then names starting with / containing the
 * spoken words. Returns several when ambiguous so the popup can ask.
 */
object ContactResolver {
    fun hasPermission(context: Context) =
        ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED

    fun find(context: Context, spoken: String): List<Contact> {
        if (!hasPermission(context)) return emptyList()
        val all = mutableListOf<Contact>()
        context.contentResolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            arrayOf(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME, ContactsContract.CommonDataKinds.Phone.NUMBER),
            null, null, null
        )?.use { c ->
            while (c.moveToNext()) {
                val name = c.getString(0) ?: continue
                val number = c.getString(1)?.let(::toE164) ?: continue
                all += Contact(name, number)
            }
        }
        return rank(all.distinctBy { it.phoneE164 }, spoken)
    }

    /** Best matches first; only the top tier is returned. */
    fun rank(contacts: List<Contact>, spoken: String): List<Contact> {
        val q = spoken.lowercase().trim()
        if (q.isBlank()) return emptyList()
        val words = q.split(Regex("\\s+"))
        fun score(c: Contact): Int {
            val n = c.name.lowercase()
            return when {
                n == q -> 4
                n.startsWith(q) -> 3
                words.all { w -> n.split(Regex("\\s+")).any { it.startsWith(w) } } -> 2
                n.contains(q) -> 1
                else -> 0
            }
        }
        val scored = contacts.map { it to score(it) }.filter { it.second > 0 }
        val best = scored.maxOfOrNull { it.second } ?: return emptyList()
        return scored.filter { it.second == best }.map { it.first }.sortedBy { it.name.length }
    }

    /** "098765 43210" / "+91 98765-43210" → "+919876543210" (Indian numbers by default). */
    fun toE164(raw: String): String? {
        val digits = raw.filter { it.isDigit() || it == '+' }
        return when {
            digits.startsWith("+") && digits.length >= 11 -> digits
            digits.startsWith("00") -> "+" + digits.drop(2)
            digits.startsWith("0") && digits.length == 11 -> "+91" + digits.drop(1)
            digits.length == 10 -> "+91$digits"
            digits.startsWith("91") && digits.length == 12 -> "+$digits"
            else -> null
        }
    }
}
