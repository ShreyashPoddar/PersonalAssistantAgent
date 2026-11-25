package com.paa.assistant.core.shield

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.core.app.NotificationCompat

/**
 * Banking apps often refuse to open while Developer options / USB debugging are on (P1-T13). PAA only reminds —
 * at most once a day, quietly, and the owner can mute it while they're developing. It never changes the setting.
 */
object DevOptionsReminder {
    private const val PREFS = "paa_devopts"
    private const val CHANNEL = "paa_devopts"
    private const val ID = 7420

    fun muted(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean("muted", false)
    fun setMuted(ctx: Context, m: Boolean) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean("muted", m).apply()

    fun devOptionsOn(ctx: Context): Boolean = runCatching {
        Settings.Global.getInt(ctx.contentResolver, Settings.Global.DEVELOPMENT_SETTINGS_ENABLED, 0) == 1 ||
            Settings.Global.getInt(ctx.contentResolver, Settings.Global.ADB_ENABLED, 0) == 1
    }.getOrDefault(false)

    /** Pure: remind when on, not muted, and not already reminded in the last 20 h. */
    fun shouldRemind(on: Boolean, muted: Boolean, lastAt: Long, now: Long) = on && !muted && now - lastAt >= 20 * 3_600_000L

    fun check(ctx: Context) {
        val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        if (!shouldRemind(devOptionsOn(ctx), muted(ctx), prefs.getLong("last", 0L), now)) return
        prefs.edit().putLong("last", now).apply()
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Developer options reminder", NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(
            ctx, ID, Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE
        )
        nm.notify(
            ID, NotificationCompat.Builder(ctx, CHANNEL)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle("Developer options are on")
                .setContentText("Banking apps may refuse to open. Turn them off when you're done.")
                .setContentIntent(open).setAutoCancel(true).build()
        )
    }
}
