package com.newoether.agora.viewmodel

import kotlinx.coroutines.flow.StateFlow

/**
 * Routes one conversation's coherent generation snapshot into the open-conversation UI mirror.
 * The snapshot is written by the runtime resource owner while the conversation's generation lock
 * is held, so Compact and ordinary-answer eligibility cannot be observed as mixed flow versions.
 */
internal class ConversationGenerationMirror(
    private val currentConversationId: StateFlow<String?>,
    private val onSnapshot: (conversationId: String, snapshot: ConversationGenerationSnapshot) -> Unit,
) {
    fun publishCurrent(conversationId: String, state: ConversationGenerationState) {
        publishIfCurrent(conversationId, state.generationSnapshot.value)
    }

    suspend fun collect(conversationId: String, state: ConversationGenerationState) {
        state.generationSnapshot.collect { snapshot ->
            publishIfCurrent(conversationId, snapshot)
        }
    }

    private fun publishIfCurrent(
        conversationId: String,
        snapshot: ConversationGenerationSnapshot,
    ) {
        if (currentConversationId.value == conversationId) {
            onSnapshot(conversationId, snapshot)
        }
    }
}
