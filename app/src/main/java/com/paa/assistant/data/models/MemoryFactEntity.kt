package com.paa.assistant.data.models

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * A persistent personal memory fact stored by PAA.
 * These are injected into Gemini's system prompt context for personalization.
 *
 * Examples:
 *  keyTopic = "preference"     factContent = "Prefers meetings after 11 AM"
 *  keyTopic = "person"         factContent = "Wife's name is Priya, birthday June 14"
 *  keyTopic = "travel"         factContent = "Frequent flyer number: 6X-1234567"
 *  keyTopic = "health"         factContent = "Is lactose intolerant"
 */
@Entity(tableName = "memory_facts")
data class MemoryFactEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val keyTopic: String,
    val factContent: String,
    val confidence: Float = 1.0f,
    val isUserVerified: Boolean = false,    // true = user manually confirmed
    val lastReferencedAt: Long = System.currentTimeMillis(),
    val createdAt: Long = System.currentTimeMillis()
)
