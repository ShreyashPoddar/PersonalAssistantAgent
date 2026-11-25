package com.paa.assistant.core.skills

import android.content.Context
import android.content.Intent
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

data class AppEntry(val label: String, val pkg: String)

/** Installed launcher apps, for "open insta" / "youtube kholo" (P1-T8). Fully on-device. */
@Singleton
class AppDirectory @Inject constructor(@ApplicationContext private val ctx: Context) {
    @Volatile private var cache: List<AppEntry> = emptyList()
    @Volatile private var loadedAt = 0L

    fun apps(): List<AppEntry> {
        if (System.currentTimeMillis() - loadedAt > 10 * 60_000L || cache.isEmpty()) {
            val pm = ctx.packageManager
            cache = pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
                .map { AppEntry(it.loadLabel(pm).toString(), it.activityInfo.packageName) }
                .distinctBy { it.pkg }
            loadedAt = System.currentTimeMillis()
        }
        return cache
    }

    companion object {
        private val OPEN_FRONT = Regex("^(?:please\\s+|pls\\s+)?(?:open|launch|start)\\s+(.+)$", RegexOption.IGNORE_CASE)
        private val OPEN_BACK = Regex("^(.+?)\\s+(?:kholo|khol do|khol de|khol|chalao|chala do|open karo|open kar do|open kar)$", RegexOption.IGNORE_CASE)
        private val NOT_AN_APP = Regex("\\b(file|pdf|document|doc|link|photo|image|folder|email|mail from|chat with|message)\\b", RegexOption.IGNORE_CASE)

        /** The app phrase of an "open X" command, or null. "open the file …" is not an app. */
        fun parseOpen(said: String): String? {
            val s = said.trim().trimEnd('.', '!', '?').replace(Regex("^(oyee? pa|hey pa|pa)[, ]+", RegexOption.IGNORE_CASE), "")
            val phrase = (OPEN_FRONT.find(s) ?: OPEN_BACK.find(s))?.groupValues?.get(1)?.trim() ?: return null
            if (NOT_AN_APP.containsMatchIn(phrase) || phrase.split(Regex("\\s+")).size > 4) return null
            return phrase
        }

        private val STOP = setOf("app", "the", "open", "kholo", "khol", "launch", "start", "chalao", "please", "my")
        private val ALIASES = mapOf(
            "insta" to "instagram", "ig" to "instagram", "yt" to "youtube", "wa" to "whatsapp", "gpay" to "google pay",
            "fk" to "flipkart", "mmt" to "makemytrip", "amazon" to "amazon shopping", "cam" to "camera",
            "phonepay" to "phonepe", "fb" to "facebook", "gmaps" to "maps", "google maps" to "maps", "spotify" to "spotify"
        )

        private fun norm(s: String) = s.lowercase(Locale.ROOT).split(Regex("[^\\p{L}\\p{N}]+"))
            .filter { it.isNotEmpty() && it !in STOP }.joinToString(" ")

        private fun lev(a: String, b: String): Int {
            val d = IntArray(b.length + 1) { it }
            for (i in 1..a.length) {
                var prev = d[0]; d[0] = i
                for (j in 1..b.length) {
                    val t = d[j]
                    d[j] = minOf(d[j] + 1, d[j - 1] + 1, prev + if (a[i - 1] == b[j - 1]) 0 else 1)
                    prev = t
                }
            }
            return d[b.length]
        }

        /** Best app for [query]; null when nothing scores at least 30. Ties → shorter label. */
        fun match(query: String, apps: List<AppEntry>): AppEntry? {
            val q0 = norm(query)
            if (q0.isEmpty()) return null
            val q = ALIASES[q0] ?: q0
            val qFlat = q.replace(" ", "")
            fun score(a: AppEntry): Int {
                val l = norm(a.label)
                val lFlat = l.replace(" ", "")
                val words = l.split(" ")
                return when {
                    l == q || lFlat == qFlat -> 100
                    l.startsWith(q) || lFlat.startsWith(qFlat) -> 80
                    words.any { it.startsWith(q) } -> 60
                    qFlat.length >= 3 && lFlat.contains(qFlat) -> 40
                    q.length >= 4 && words.any { lev(it, q) <= 2 } -> 30
                    else -> 0
                }
            }
            return apps.map { it to score(it) }.filter { it.second >= 30 }
                .sortedWith(compareByDescending<Pair<AppEntry, Int>> { it.second }.thenBy { it.first.label.length })
                .firstOrNull()?.first
        }
    }
}
