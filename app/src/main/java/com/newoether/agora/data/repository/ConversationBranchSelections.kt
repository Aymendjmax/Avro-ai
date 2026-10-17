package com.newoether.agora.data.repository

import com.newoether.agora.data.local.ChatDao
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** Persists branch selection maps through the repository's existing DAO. */
internal class ConversationBranchSelections(private val chatDao: ChatDao) {
    suspend fun saveBranchSelections(
        conversationId: String,
        selections: Map<String?, String>,
    ) = withContext(Dispatchers.Default) {
        val conversation = chatDao.getConversation(conversationId) ?: return@withContext
        val stringKeyMap = selections.mapKeys { it.key ?: "null" }
        val json = Json.encodeToString(stringKeyMap)
        if (conversation.selectedBranchesJson != json) {
            check(
                chatDao.updateMessageBranchSelections(
                    conversationId = conversationId,
                    selectedBranchesJson = json,
                    at = System.currentTimeMillis(),
                ) == 1
            ) { "Conversation $conversationId disappeared during message branch selection" }
        }
    }

    suspend fun restoreBranchSelections(
        conversationId: String,
    ): Map<String?, String> = withContext(Dispatchers.Default) {
        val conversation = chatDao.getConversation(conversationId)
            ?: return@withContext emptyMap()
        val raw = conversation.selectedBranchesJson ?: return@withContext emptyMap()
        try {
            val map = Json.decodeFromString<Map<String, String>>(raw)
            map.mapKeys { if (it.key == "null") null else it.key }
        } catch (_: Exception) {
            emptyMap()
        }
    }

    suspend fun saveRunBranchSelections(
        conversationId: String,
        selections: Map<String?, String>,
    ) = withContext(Dispatchers.Default) {
        val conversation = chatDao.getConversation(conversationId) ?: return@withContext
        val stored = Json.encodeToString(selections.mapKeys { it.key ?: "null" })
        if (conversation.selectedRunBranchesJson != stored) {
            check(
                chatDao.updateRunBranchSelections(
                    conversationId = conversationId,
                    selectedRunBranchesJson = stored,
                    at = System.currentTimeMillis(),
                ) == 1
            ) { "Conversation $conversationId disappeared during Run branch selection" }
        }
    }

    suspend fun restoreRunBranchSelections(
        conversationId: String,
    ): Map<String?, String> = withContext(Dispatchers.Default) {
        val raw = chatDao.getConversation(conversationId)?.selectedRunBranchesJson
            ?: return@withContext emptyMap()
        runCatching {
            Json.decodeFromString<Map<String, String>>(raw)
                .mapKeys { if (it.key == "null") null else it.key }
        }.getOrDefault(emptyMap())
    }

    suspend fun selectRunBranch(
        conversationId: String,
        parentRunId: String?,
        runId: String,
    ) {
        val selections = restoreRunBranchSelections(conversationId).toMutableMap()
        selections[parentRunId] = runId
        saveRunBranchSelections(conversationId, selections)
    }

    /** Persists Run and legacy message selection maps in the same row update. */
    suspend fun selectRunBranch(
        conversationId: String,
        parentRunId: String?,
        runId: String,
        messageSelections: Map<String?, String>,
    ) = withContext(Dispatchers.Default) {
        val runSelections = restoreRunBranchSelections(conversationId).toMutableMap()
        runSelections[parentRunId] = runId
        check(
            chatDao.updateBranchSelections(
                conversationId = conversationId,
                selectedBranchesJson = Json.encodeToString(messageSelections.mapKeys { it.key ?: "null" }),
                selectedRunBranchesJson = Json.encodeToString(runSelections.mapKeys { it.key ?: "null" }),
                at = System.currentTimeMillis(),
            ) == 1
        ) { "Conversation $conversationId disappeared during branch selection" }
    }
}
