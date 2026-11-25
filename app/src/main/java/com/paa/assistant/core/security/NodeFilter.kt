package com.paa.assistant.core.security

/** What PAA needs to know about one screen node, so the rule can be unit-tested without Android (P1-T11). */
data class NodeLite(val text: String?, val visible: Boolean, val password: Boolean, val w: Int, val h: Int, val pkg: String?)

object NodeFilter {
    /** Only real, visible, non-password text of the expected app reaches PAA (hidden/overlay/tiny text is ignored). */
    fun keep(n: NodeLite, expectedPkg: String): Boolean =
        !n.text.isNullOrBlank() && n.visible && !n.password && n.w >= 8 && n.h >= 8 && n.pkg == expectedPkg
}
