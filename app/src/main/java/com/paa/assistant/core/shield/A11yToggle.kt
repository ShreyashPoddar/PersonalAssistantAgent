package com.paa.assistant.core.shield

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.provider.Settings
import com.paa.assistant.services.ChatTaskAccessibilityService

/**
 * The owner's "screen reading" switch: turns PAA's accessibility service off/on only when the owner acts (toggle,
 * tile, voice). Nothing here watches which app is open. Needs WRITE_SECURE_SETTINGS granted once over adb;
 * without it callers open Accessibility settings instead.
 */
object A11yToggle {
    fun service(ctx: Context) = ComponentName(ctx, ChatTaskAccessibilityService::class.java)

    fun canToggle(ctx: Context) =
        ctx.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) == PackageManager.PERMISSION_GRANTED

    fun isEnabled(ctx: Context): Boolean =
        contains(Settings.Secure.getString(ctx.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES), service(ctx))

    /** False when PAA may not change the setting (the caller then opens Accessibility settings). */
    fun setEnabled(ctx: Context, on: Boolean): Boolean {
        if (!canToggle(ctx)) return false
        val cr = ctx.contentResolver
        val current = Settings.Secure.getString(cr, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
        return runCatching {
            Settings.Secure.putString(cr, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, editList(current, service(ctx), on))
            if (on) Settings.Secure.putInt(cr, Settings.Secure.ACCESSIBILITY_ENABLED, 1)
            true
        }.getOrDefault(false)
    }

    fun contains(list: String?, me: ComponentName): Boolean = contains(list, me.flattenToString())

    /** [me] is the full "pkg/pkg.Cls" form; list entries may also use the short "pkg/.Cls" form. */
    fun contains(list: String?, me: String): Boolean = entries(list).any { full(it) == full(me) }

    fun editList(current: String?, me: ComponentName, on: Boolean): String = editList(current, me.flattenToString(), on)

    /** The services list with [me] added or removed; every other service is kept as it was. */
    fun editList(current: String?, me: String, on: Boolean): String {
        val others = entries(current).filter { full(it) != full(me) }
        return (if (on) others + me else others).joinToString(":")
    }

    private fun entries(list: String?) = list.orEmpty().split(':').map { it.trim() }.filter { it.isNotEmpty() }

    private fun full(entry: String): String {
        val pkg = entry.substringBefore('/')
        val cls = entry.substringAfter('/', "")
        return "$pkg/" + if (cls.startsWith(".")) pkg + cls else cls
    }

    private val OFF = Regex("^(stop reading (my )?screen|screen reading off|turn off screen reading|banking mode( on)?)[.!]?$")
    private val ON = Regex("^(screen reading on|turn on screen reading|start reading (my )?screen|banking mode off)[.!]?$")

    /** The owner's voice switch: false = turn reading off, true = on, null = not this command. */
    fun voiceCommand(said: String): Boolean? {
        val s = said.trim().lowercase().replace(Regex("^(oyee? pa|hey pa|pa)[, ]+"), "")
        return when {
            OFF.matches(s) -> false
            ON.matches(s) -> true
            else -> null
        }
    }

    /** Applies the voice switch and says what happened. */
    fun applyVoice(ctx: Context, on: Boolean): String = when {
        !setEnabled(ctx, on) -> "I can't switch that myself. Open Settings → Accessibility → PAA and turn it ${if (on) "on" else "off"}."
        on -> "Screen reading on."
        else -> "Screen reading off. Say 'screen reading on' when you're done."
    }
}
