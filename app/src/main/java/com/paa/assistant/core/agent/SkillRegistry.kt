package com.paa.assistant.core.agent

import com.paa.assistant.core.skills.AlarmSkill
import com.paa.assistant.core.skills.CallSkill
import com.paa.assistant.core.skills.MessageSkill
import javax.inject.Inject
import javax.inject.Singleton

/** Which skill runs which action. Later tasks add skills to the constructor and the `when`. */
@Singleton
class SkillRegistry @Inject constructor(
    private val message: MessageSkill,
    private val alarm: AlarmSkill,
    private val call: CallSkill
) {
    @Suppress("UNCHECKED_CAST")
    fun forAction(a: Action): Skill<Action>? = when (a) {
        is Action.SendMessage -> message
        is Action.SetAlarm -> alarm
        is Action.PlaceCall -> call
        else -> null
    } as Skill<Action>?
}
