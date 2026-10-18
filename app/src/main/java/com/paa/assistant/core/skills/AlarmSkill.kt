package com.paa.assistant.core.skills

import com.paa.assistant.core.agent.Action
import com.paa.assistant.core.agent.ActionResult
import com.paa.assistant.core.agent.Skill
import com.paa.assistant.data.models.TaskEntity
import com.paa.assistant.data.repository.TaskRepository
import javax.inject.Inject
import javax.inject.Singleton

/** Alarms are high-priority tasks; the task scheduler rings them (inside the owner's alarm hours). */
@Singleton
class AlarmSkill @Inject constructor(private val repository: TaskRepository) : Skill<Action.SetAlarm> {
    override suspend fun run(action: Action.SetAlarm): ActionResult {
        repository.createTask(TaskEntity(title = action.label, dueTimestamp = action.atMillis, priority = 2))
        return ActionResult(true, "Alarm set! I'll remind you at the scheduled time.")
    }
}
