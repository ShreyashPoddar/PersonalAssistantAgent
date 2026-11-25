package com.paa.assistant.core.skills

import android.content.Context
import android.content.Intent
import com.paa.assistant.core.agent.Action
import com.paa.assistant.core.agent.ActionResult
import com.paa.assistant.core.agent.Skill
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/** Opens an app with its normal launch intent — every app, banking apps included, the same ordinary way. */
@Singleton
class OpenAppSkill @Inject constructor(@ApplicationContext private val ctx: Context) : Skill<Action.OpenApp> {
    override suspend fun run(action: Action.OpenApp): ActionResult {
        val intent = ctx.packageManager.getLaunchIntentForPackage(action.packageName)
            ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            ?: return ActionResult(false, "I couldn't open ${action.label}.")
        return runCatching { ctx.startActivity(intent) }
            .fold({ ActionResult(true, "Opening ${action.label}") }, { ActionResult(false, "I couldn't open ${action.label}.") })
    }
}
