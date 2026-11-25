package com.paa.assistant.core.agent

import android.content.Context
import com.paa.assistant.core.shield.A11yToggle
import com.paa.assistant.services.WakeListenerService

/**
 * Lockdown (P1-T12): only alarms, reminders and SOS keep working. Turning it on also switches off screen reading
 * and the "Oyee PA" listener; turning it off brings back exactly what was on before.
 */
object Lockdown {
    private const val PREFS = "paa_agent"

    data class Saved(val screenReading: Boolean, val wakeListener: Boolean)

    /** Pure: what to switch back on when Lockdown ends — what was on before and is off now. */
    fun toRestore(before: Saved, now: Saved) = Saved(
        screenReading = before.screenReading && !now.screenReading,
        wakeListener = before.wakeListener && !now.wakeListener
    )

    fun isOn(ctx: Context) = AgentSettings(ctx).lockdown

    fun set(ctx: Context, on: Boolean) {
        val settings = AgentSettings(ctx)
        val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (on == settings.lockdown) return
        if (on) {
            prefs.edit()
                .putBoolean("before_a11y", A11yToggle.isEnabled(ctx))
                .putBoolean("before_wake", WakeListenerService.isEnabled(ctx))
                .apply()
            settings.lockdown = true
            A11yToggle.setEnabled(ctx, false)  // no-op without the one-time grant; the card says so
            WakeListenerService.setEnabled(ctx, false)
            WakeListenerService.stop(ctx)
        } else {
            settings.lockdown = false
            val r = toRestore(
                Saved(prefs.getBoolean("before_a11y", false), prefs.getBoolean("before_wake", false)),
                Saved(A11yToggle.isEnabled(ctx), WakeListenerService.isEnabled(ctx))
            )
            if (r.screenReading) A11yToggle.setEnabled(ctx, true)
            if (r.wakeListener) { WakeListenerService.setEnabled(ctx, true); WakeListenerService.startIfEnabled(ctx) }
        }
    }
}
