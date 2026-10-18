package com.paa.assistant.core.skills

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import androidx.core.content.ContextCompat
import com.paa.assistant.core.agent.Action
import com.paa.assistant.core.agent.ActionResult
import com.paa.assistant.core.agent.Skill
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/** Places the call when CALL_PHONE is granted; otherwise opens the dialer with the number (owner taps call). */
@Singleton
class CallSkill @Inject constructor(@ApplicationContext private val ctx: Context) : Skill<Action.PlaceCall> {
    override suspend fun run(action: Action.PlaceCall): ActionResult {
        val canCall = ContextCompat.checkSelfPermission(ctx, Manifest.permission.CALL_PHONE) == PackageManager.PERMISSION_GRANTED
        val intent = Intent(if (canCall) Intent.ACTION_CALL else Intent.ACTION_DIAL, Uri.parse("tel:${Uri.encode(action.number)}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return runCatching { ctx.startActivity(intent) }.fold(
            onSuccess = { ActionResult(true, if (canCall) "📞 Calling ${action.contactName}." else "Tap call to ring ${action.contactName}.") },
            onFailure = { ActionResult(false, "Couldn't start the call.") }
        )
    }
}
