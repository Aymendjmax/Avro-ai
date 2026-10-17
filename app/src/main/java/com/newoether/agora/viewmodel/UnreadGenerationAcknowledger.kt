package com.newoether.agora.viewmodel

import com.newoether.agora.data.repository.ConversationRepository
import com.newoether.agora.model.ChatConversation
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch

/** Clears unread state only while the selected conversation is actually visible. */
internal class UnreadGenerationAcknowledger(
    private val currentConversation: Flow<ChatConversation?>,
    private val appForeground: Flow<Boolean>,
    private val chatPresented: Flow<Boolean>,
    private val conversations: ConversationRepository,
    private val scope: CoroutineScope,
    private val onConversationRead: (String) -> Unit,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    fun start() {
        scope.launch(ioDispatcher) {
            combine(
                currentConversation,
                appForeground,
                chatPresented,
            ) { conversation, foreground, presented ->
                conversation.takeIf { foreground && presented }
            }
                .filterNotNull()
                .filter { it.hasUnreadGeneration }
                .collect { conversation ->
                    if (
                        conversations.setConversationUnreadGeneration(
                            conversation.id,
                            unread = false,
                        )
                    ) {
                        onConversationRead(conversation.id)
                    }
                }
        }
    }
}
