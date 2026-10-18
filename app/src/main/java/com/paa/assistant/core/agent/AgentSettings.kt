package com.paa.assistant.core.agent

import android.content.Context
import com.paa.assistant.core.audio.VoicePrint
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/** Owner switches the [Guard] reads: lockdown, the kill switch, and whether voice lock is on. */
@Singleton
class AgentSettings @Inject constructor(@ApplicationContext private val ctx: Context) {
    private val prefs = ctx.getSharedPreferences("paa_agent", Context.MODE_PRIVATE)

    /** Only READ / PRIVATE_REVERSIBLE actions may run. */
    var lockdown: Boolean
        get() = prefs.getBoolean("lockdown", false)
        set(v) = prefs.edit().putBoolean("lockdown", v).apply()

    /** Kill switch: nothing runs except the owner's SOS. */
    var paused: Boolean
        get() = prefs.getBoolean("paused", false)
        set(v) = prefs.edit().putBoolean("paused", v).apply()

    fun voiceLockOn(): Boolean = VoicePrint.isOn(ctx)
}
