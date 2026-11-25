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
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/** SMS (P2-T2): sends directly when SEND_SMS is granted, else opens the SMS app with the text ready (owner taps Send). */
@Singleton
class SmsSkill @Inject constructor(@ApplicationContext private val ctx: Context) : Skill<Action.SendSms> {
    override suspend fun run(action: Action.SendSms): ActionResult {
        val number = action.number.filter { it.isDigit() || it == '+' }
        if (number.length < 3) return ActionResult(false, "No phone number for ${action.contactName}.")
        if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.SEND_SMS) != PackageManager.PERMISSION_GRANTED) {
            return runCatching {
                ctx.startActivity(Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:$number")).putExtra("sms_body", action.body).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }.fold({ ActionResult(true, "SMS to ${action.contactName} is ready — tap Send.") }, { ActionResult(false, "Couldn't open the SMS app.") })
        }
        val sms = if (Build.VERSION.SDK_INT >= 31) ctx.getSystemService(SmsManager::class.java) else @Suppress("DEPRECATION") SmsManager.getDefault()
        return runCatching { sms.sendMultipartTextMessage(number, null, sms.divideMessage(action.body), null, null) }
            .fold({ ActionResult(true, "📨 SMS sent to ${action.contactName}.") }, { ActionResult(false, "SMS failed: ${it.javaClass.simpleName}") })
    }
}
