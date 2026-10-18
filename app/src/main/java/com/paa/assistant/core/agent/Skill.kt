package com.paa.assistant.core.agent

/** Does one kind of [Action]. Only the Executor calls [run], after the Guard allowed it. */
interface Skill<A : Action> {
    suspend fun run(action: A): ActionResult
}

data class ActionResult(val ok: Boolean, val message: String)
