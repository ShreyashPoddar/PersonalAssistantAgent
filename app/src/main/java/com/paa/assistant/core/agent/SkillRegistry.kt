package com.paa.assistant.core.agent

import javax.inject.Inject
import javax.inject.Singleton

/** Which skill runs which action. Later tasks add skills to the constructor and the `when`. */
@Singleton
class SkillRegistry @Inject constructor() {
    fun forAction(a: Action): Skill<Action>? = when (a) {
        else -> null
    }
}
