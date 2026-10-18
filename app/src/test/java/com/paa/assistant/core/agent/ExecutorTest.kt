package com.paa.assistant.core.agent

import com.paa.assistant.data.db.AuditDao
import com.paa.assistant.data.db.ContactRuleDao
import com.paa.assistant.data.models.AuditEntryEntity
import com.paa.assistant.data.models.ContactRuleEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ExecutorTest {

    private class FakeAudit : AuditDao {
        val entries = mutableListOf<AuditEntryEntity>()
        override suspend fun insert(e: AuditEntryEntity): Long { entries += e; return entries.size.toLong() }
        override fun recent(limit: Int): Flow<List<AuditEntryEntity>> = flowOf(entries.toList())
        override suspend fun deleteOlderThan(time: Long) { entries.removeAll { it.at < time } }
    }

    private class FakeSkill(private val result: ActionResult = ActionResult(true, "Sent")) : Skill<Action> {
        var runs = 0
        override suspend fun run(action: Action): ActionResult { runs++; return result }
    }

    private val msg = Action.SendMessage("WhatsApp", "id1", "Ahana", "hi")
    private val audit = FakeAudit()
    private val rules = FakeRules()
    private val logged = mutableListOf<Triple<String, String, String>>()

    private fun executor(verdict: Verdict, approve: Boolean = true, skill: Skill<Action>? = FakeSkill()) = Executor(
        check = { _, _, _ -> verdict },
        ask = { _, _, _ -> approve },
        skillFor = { skill },
        audit = audit, rules = rules,
        log = { a, b, c -> logged += Triple(a, b, c) },
        now = { 1000L }
    )

    @Test
    fun deniedActionNeverReachesSkill() = runBlocking {
        val skill = FakeSkill()
        val r = executor(Verdict.Deny("Lockdown is on"), skill = skill).submit(msg, CommandSource.OWNER_UI)
        assertFalse(r.ok)
        assertEquals("Lockdown is on", r.message)
        assertEquals(0, skill.runs)
        assertEquals(1, audit.entries.size)
        assertEquals("DENY", audit.entries[0].verdict)
    }

    @Test
    fun cancelledConfirmNeverReachesSkill() = runBlocking {
        val skill = FakeSkill()
        val r = executor(Verdict.Confirm(true), approve = false, skill = skill).submit(msg, CommandSource.OWNER_UI)
        assertEquals(ActionResult(false, "Cancelled"), r)
        assertEquals(0, skill.runs)
        assertEquals(1, audit.entries.size)
        assertTrue(rules.map.isEmpty())
    }

    @Test
    fun successfulOutwardMarksContactMessaged() = runBlocking {
        val skill = FakeSkill()
        val r = executor(Verdict.Confirm(false), skill = skill).submit(msg, CommandSource.OWNER_UI)
        assertTrue(r.ok)
        assertEquals(1, skill.runs)
        assertEquals(true, rules.map["ahana"]?.messagedBefore)
        assertEquals(1, audit.entries.size)
        audit.entries[0].let {
            assertEquals("SendMessage", it.actionType)
            assertEquals("OUTWARD", it.tier)
            assertEquals("OWNER_UI", it.source)
            assertEquals(msg.summary(), it.summary)
            assertTrue(it.ok)
        }
    }

    @Test
    fun failedSkillDoesNotMarkMessaged() = runBlocking {
        executor(Verdict.Allow, skill = FakeSkill(ActionResult(false, "No WhatsApp"))).submit(msg, CommandSource.OWNER_UI)
        assertTrue(rules.map.isEmpty())
        assertEquals(1, audit.entries.size)
    }

    @Test
    fun skillThatThrowsIsRecordedAsFailed() = runBlocking {
        val boom = object : Skill<Action> {
            override suspend fun run(action: Action): ActionResult = throw IllegalStateException()
        }
        val r = executor(Verdict.Allow, skill = boom).submit(msg, CommandSource.OWNER_UI)
        assertEquals(ActionResult(false, "Failed: IllegalStateException"), r)
        assertEquals(1, audit.entries.size)
    }

    @Test
    fun missingSkillIsNotSupported() = runBlocking {
        val r = executor(Verdict.Allow, skill = null).submit(Action.SetAlarm(0, "x"), CommandSource.OWNER_UI)
        assertEquals(ActionResult(false, "Not supported yet"), r)
        assertEquals(1, audit.entries.size)
    }

    @Test
    fun detectionLogNeverGetsTheSummary() = runBlocking {
        executor(Verdict.Allow).submit(msg, CommandSource.OWNER_UI)
        assertEquals(1, logged.size)
        assertFalse(logged[0].toList().any { it.contains("hi") || it.contains("Ahana") })
    }
}
