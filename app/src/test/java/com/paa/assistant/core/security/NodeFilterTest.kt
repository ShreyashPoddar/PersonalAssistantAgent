package com.paa.assistant.core.security

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NodeFilterTest {
    private val wa = "com.whatsapp"
    private fun n(text: String? = "notes bhej de", visible: Boolean = true, password: Boolean = false, w: Int = 300, h: Int = 60, pkg: String? = wa) =
        NodeLite(text, visible, password, w, h, pkg)

    @Test fun normalBubbleKept() = assertTrue(NodeFilter.keep(n(), wa))
    @Test fun hiddenDropped() = assertFalse(NodeFilter.keep(n(visible = false), wa))
    @Test fun passwordDropped() = assertFalse(NodeFilter.keep(n(password = true), wa))
    @Test fun tinyDropped() { assertFalse(NodeFilter.keep(n(w = 1, h = 1), wa)); assertFalse(NodeFilter.keep(n(h = 4), wa)) }
    @Test fun otherAppDropped() { assertFalse(NodeFilter.keep(n(pkg = "com.evil.overlay"), wa)); assertFalse(NodeFilter.keep(n(pkg = null), wa)) }
    @Test fun blankDropped() { assertFalse(NodeFilter.keep(n(text = "  "), wa)); assertFalse(NodeFilter.keep(n(text = null), wa)) }
}
