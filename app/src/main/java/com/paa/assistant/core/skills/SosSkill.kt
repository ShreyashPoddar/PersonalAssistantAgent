package com.paa.assistant.core.skills

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.telephony.SmsManager
import androidx.core.content.ContextCompat
import com.paa.assistant.core.agent.Action
import com.paa.assistant.core.agent.ActionResult
import com.paa.assistant.core.agent.Skill
import com.paa.assistant.core.profile.UserProfile
import com.paa.assistant.core.sos.SosMessage
import com.paa.assistant.core.sos.SosService
import com.paa.assistant.core.sos.SosSettings
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/** Starts the SOS service. Action.Sos("drill") runs a drill (only the owner's own number, no call, no siren). */
@Singleton
class SosSkill @Inject constructor(@ApplicationContext private val ctx: Context) : Skill<Action.Sos> {
    override suspend fun run(action: Action.Sos): ActionResult {
        val drill = action.reason == "drill"
        val s = SosSettings(ctx)
        if (!drill && s.contacts.isEmpty() && s.callNumber() == null) {
            return ActionResult(false, "SOS isn't set up — add contacts in PAA → SOS. Calling 112 is always available from the dialer.")
        }
        if (drill && s.ownNumber.isBlank()) return ActionResult(false, "Add your own number in PAA → SOS to run a drill.")
        // The location service can't start without the location permission (Android 14+ throws, and SOS would die
        // silently): then send the alert without a location and call, right away
        if (!granted(Manifest.permission.ACCESS_FINE_LOCATION)) return liteSos(s, drill)
        SosService.start(ctx, drill)
        return ActionResult(true, if (drill) "SOS drill starting (only your own number)." else "SOS starting — cancel within ${s.countdownSeconds} s if it's a mistake.")
    }

    private fun liteSos(s: SosSettings, drill: Boolean): ActionResult {
        val name = UserProfile.names.firstOrNull()?.replaceFirstChar { it.uppercase() } ?: "me"
        val text = SosMessage.text(name, null, null, null, System.currentTimeMillis(), drill)
        var sent = 0
        if (granted(Manifest.permission.SEND_SMS)) {
            val sms = if (Build.VERSION.SDK_INT >= 31) ctx.getSystemService(SmsManager::class.java) else @Suppress("DEPRECATION") SmsManager.getDefault()
            val numbers = if (drill) listOf(s.ownNumber) else s.contacts.map { it.number }
            for (n in numbers.filter { it.isNotBlank() }) runCatching { sms.sendMultipartTextMessage(n, null, sms.divideMessage(text), null, null); sent++ }
        }
        if (!drill) s.callNumber()?.let { num ->
            val action = if (granted(Manifest.permission.CALL_PHONE)) Intent.ACTION_CALL else Intent.ACTION_DIAL
            runCatching { ctx.startActivity(Intent(action, Uri.parse("tel:${Uri.encode(num)}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        }
        return ActionResult(sent > 0 || !drill, "SOS sent to $sent contact(s) without location (allow Location for PAA to share it).")
    }

    private fun granted(p: String) = ContextCompat.checkSelfPermission(ctx, p) == PackageManager.PERMISSION_GRANTED
}
