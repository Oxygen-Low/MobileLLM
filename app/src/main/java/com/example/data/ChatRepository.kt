package com.example.data

import kotlinx.coroutines.flow.Flow

class ChatRepository(private val chatDao: ChatDao) {
    val allSessions: Flow<List<ChatSessionEntity>> = chatDao.getAllSessions()

    fun getMessagesForSession(sessionId: String): Flow<List<ChatMessageEntity>> {
        return chatDao.getMessagesForSession(sessionId)
    }

    suspend fun createSession(session: ChatSessionEntity) {
        chatDao.insertSession(session)
    }

    suspend fun updateSession(session: ChatSessionEntity) {
        chatDao.updateSession(session)
    }

    suspend fun deleteSession(sessionId: String) {
        chatDao.deleteMessagesForSession(sessionId)
        chatDao.deleteSessionById(sessionId)
    }

    suspend fun insertMessage(message: ChatMessageEntity) {
        chatDao.insertMessage(message)
    }

    suspend fun updateMessage(message: ChatMessageEntity) {
        chatDao.updateMessage(message)
    }

    suspend fun clearAll() {
        chatDao.clearAllMessages()
        chatDao.clearAllSessions()
    }
}
