package com.paa.assistant.core.agent

import com.paa.assistant.data.db.ContactRuleDao
import com.paa.assistant.data.models.ContactRuleEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

/** In-memory [ContactRuleDao] for unit tests. */
class FakeRules : ContactRuleDao {
    val map = mutableMapOf<String, ContactRuleEntity>()
    override suspend fun get(key: String) = map[key]
    override suspend fun upsert(rule: ContactRuleEntity) { map[rule.contactKey] = rule }
    override suspend fun autoReplyOn() = map.values.filter { it.autoReplyDuringCall }
    override fun all(): Flow<List<ContactRuleEntity>> = flowOf(map.values.toList())
}
