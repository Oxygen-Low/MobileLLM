package com.example.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "chat_sessions")
data class ChatSessionEntity(
    @PrimaryKey
    val id: String,
    val title: String,
    val modelId: String,
    val createdAt: Long = System.currentTimeMillis(),
    val messageCount: Int = 0
)

@Entity(tableName = "chat_messages")
data class ChatMessageEntity(
    @PrimaryKey
    val id: String,
    val sessionId: String,
    val role: String, // "user", "assistant", "system", "tool"
    val content: String,
    val reasoningContent: String? = null,
    val tokensCount: Int = 0,
    val tokensPerSecond: Double = 0.0,
    val isToolExecution: Boolean = false,
    val toolName: String? = null,
    val toolQuery: String? = null,
    val toolSourceUrl: String? = null,
    val timestamp: Long = System.currentTimeMillis()
)
