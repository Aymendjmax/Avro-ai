package com.newoether.agora.viewmodel

import com.newoether.agora.data.repository.ConversationRepository
import com.newoether.agora.util.DebugLog
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Coordinates durable conversation metadata changes and deletion cleanup for every client.
 *
 * A deletion is issued by one [ChatClient] (its origin). The origin covers its own page while it
 * shows the conversation; every client that still shows the conversation when it is deleted falls
 * back to New Chat, and clients other than the origin are told why first.
 */
internal class ConversationLifecycleController(
    private val conversations: ConversationRepository,
    private val scope: CoroutineScope,
    private val clients: ChatClients,
    private val stopLoop: suspend (String) -> Unit,
    private val tryWithConversationLock: suspend (String, suspend () -> Unit) -> Boolean,
    private val removeRuntime: (String) -> Unit,
    private val stopGeneration: (conversationId: String, origin: ChatClient) -> Unit,
    private val deletedElsewhereText: () -> String,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val mainDispatcher: CoroutineDispatcher = Dispatchers.Main,
) {
    fun rename(conversationId: String, newTitle: String) {
        scope.launch {
            conversations.updateConversationTitle(conversationId, newTitle)
        }
    }

    fun setPinned(conversationId: String, pinned: Boolean) {
        scope.launch(ioDispatcher) {
            conversations.setConversationPinned(conversationId, pinned)
        }
    }

    fun delete(
        origin: ChatClient,
        conversationId: String,
        expectedMessageIds: Set<String>? = null,
        onResult: (Boolean) -> Unit = {},
    ): Boolean {
        if (clients.isSubmissionFrozen(conversationId)) return false
        scope.launch(ioDispatcher) {
            var deleted = false
            var viewersAtCommit = emptyList<ChatClient>()
            val selectedAtDispatch = origin.openConversationId == conversationId
            var transitionRequestId: Long? = null
            try {
                if (selectedAtDispatch) {
                    // This suspends for the overlay fade, so no destructive storage work can begin
                    // until the underlying selected-page loading surface is visible.
                    transitionRequestId = origin.beginTreeMutation(
                        conversationId = conversationId,
                        scrollToTarget = false,
                    )
                }
                tryWithConversationLock(conversationId) {
                    // Send admission uses this same lock. Only the winner may stop live work.
                    if (clients.isSubmissionFrozen(conversationId)) return@tryWithConversationLock
                    if (
                        expectedMessageIds != null &&
                        conversations.getMessageTopologySnapshot(conversationId)
                            .mapTo(linkedSetOf()) { it.id } != expectedMessageIds
                    ) return@tryWithConversationLock
                    viewersAtCommit = clients.showing(conversationId)
                    if (viewersAtCommit.isNotEmpty()) stopGeneration(conversationId, origin)
                    stopLoop(conversationId)
                    conversations.deleteConversation(conversationId)
                    deleted = true
                }
                if (deleted) {
                    removeRuntime(conversationId)
                    if (viewersAtCommit.isNotEmpty()) {
                        withContext(mainDispatcher) {
                            viewersAtCommit.forEach { viewer ->
                                if (viewer !== origin) viewer.showSnackbar(deletedElsewhereText())
                                // The origin hands its already-visible overlay to New Chat.
                                viewer.settleDeletedConversation(conversationId)
                            }
                        }
                    }
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                runCatching {
                    DebugLog.e(
                        "ConversationLifecycle",
                        "Failed to delete conversation $conversationId",
                        error,
                    )
                }
            } finally {
                withContext(NonCancellable + mainDispatcher) {
                    if (selectedAtDispatch && (!deleted || origin !in viewersAtCommit)) {
                        origin.failTreeMutation(transitionRequestId)
                    }
                    onResult(deleted)
                }
            }
        }
        return true
    }
}
