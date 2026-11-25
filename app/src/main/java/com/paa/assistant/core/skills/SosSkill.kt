package com.paa.assistant.core.skills

import android.content.Context
import com.paa.assistant.core.agent.Action
import com.paa.assistant.core.agent.ActionResult
import com.paa.assistant.core.agent.Skill
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
        SosService.start(ctx, drill)
        return ActionResult(true, if (drill) "SOS drill starting (only your own number)." else "SOS starting — cancel within ${s.countdownSeconds} s if it's a mistake.")
    }
}
