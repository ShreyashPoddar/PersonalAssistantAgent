package com.paa.assistant.core.agent

import org.junit.Assert.assertEquals
import org.junit.Test

class LockdownTest {
    private fun s(a: Boolean, w: Boolean) = Lockdown.Saved(a, w)

    @Test fun restoresOnlyWhatWasOnAndIsOffNow() {
        assertEquals(s(true, true), Lockdown.toRestore(before = s(true, true), now = s(false, false)))
        assertEquals(s(false, true), Lockdown.toRestore(before = s(false, true), now = s(false, false)))
        // Already back on (e.g. the owner turned it on by hand) → nothing to do
        assertEquals(s(false, false), Lockdown.toRestore(before = s(true, true), now = s(true, true)))
        // Was off before → stays off
        assertEquals(s(false, false), Lockdown.toRestore(before = s(false, false), now = s(false, false)))
    }
}
