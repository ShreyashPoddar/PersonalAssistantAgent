package com.paa.assistant.core.skills

import android.content.Context
import android.content.Intent
import com.paa.assistant.core.agent.Action
import com.paa.assistant.core.agent.ActionResult
import com.paa.assistant.core.agent.Skill
import com.paa.assistant.core.money.PaymentHandoff
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Runs [Action.PlaceOrder] — reachable only through the Executor after Guard's MONEY verdict (owner command +
 * fingerprint on the exact amount). Opens the owner's UPI app or leaves the merchant on its own payment step, then
 * stops: it never touches the PIN screen.
 */
@Singleton
class PaymentSkill @Inject constructor(@ApplicationContext private val ctx: Context) : Skill<Action.PlaceOrder> {
    override suspend fun run(action: Action.PlaceOrder): ActionResult {
        val pm = ctx.packageManager
        return when (val step = PaymentHandoff.plan(action, action.upiApp) { pm.getLaunchIntentForPackage(it) != null }) {
            is PaymentHandoff.Step.Refuse -> ActionResult(false, step.reason)
            is PaymentHandoff.Step.StayInMerchant -> ActionResult(true, step.message)
            is PaymentHandoff.Step.OpenUpi -> {
                val intent = pm.getLaunchIntentForPackage(step.packageName)?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    ?: return ActionResult(false, "${step.label} isn't installed.")
                runCatching { ctx.startActivity(intent) }
                    .fold({ ActionResult(true, step.message) }, { ActionResult(false, "I couldn't open ${step.label}.") })
            }
        }
    }
}
