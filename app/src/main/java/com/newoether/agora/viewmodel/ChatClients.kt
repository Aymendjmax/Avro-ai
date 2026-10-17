package com.newoether.agora.viewmodel

import com.newoether.agora.model.ChatMessage
import java.util.concurrent.CopyOnWriteArrayList

/**
 * One chat client attached to [ChatRuntime]: the phone UI today, each WebUI session later.
 *
 * A client owns what it shows. The runtime never selects a conversation for a client; it only
 * fans conversation-scoped changes out to the clients that currently show that conversation.
 */
internal interface ChatClient {
    /** Conversation this client currently shows, or null when it shows none (for example New Chat). */
    val openConversationId: String?

    /** Render snapshot of [openConversationId]. The runtime writes it only while that conversation is open. */
    val renderStore: ConversationRenderStore

    /** True when the user can currently see [conversationId] on this client. */
    fun isConversationVisible(conversationId: String): Boolean

    // -- Effects. A command's effects go to the client that issued it; an automatic send
    // -- (queue drain, Loop cycle) has no origin and raises them on every client showing it.

    /** Branch-replacement animation this client plays for its own edit or regenerate. */
    val branchTransitions: BranchReplacementTransitionCoordinator

    /** Suspends until this client shows [messageId] in [conversationId] or no longer shows it. */
    suspend fun awaitProjectedPath(conversationId: String, messageId: String)

    fun requestScrollToBottomAfter(conversationId: String, messageId: String, attachedOnly: Boolean)

    /** One accepted send (direct or queued) for haptic feedback. */
    fun onSendAccepted(conversationId: String, messageId: String)

    /** Moves this client's New Chat workspace state onto the conversation its send created. */
    suspend fun applyCommittedNewConversationState(conversationId: String)

    /**
     * Opens the conversation a New Chat send created, only while that exact New Chat entry
     * ([entryId]) is still shown. Returns whether it was opened.
     */
    suspend fun publishAcceptedNewConversation(
        conversationId: String,
        modelId: String,
        entryId: Long,
    ): Boolean

    /** Covers a destructive tree mutation until the client settles the resulting path. */
    suspend fun beginTreeMutation(conversationId: String, scrollToTarget: Boolean): Long?

    fun settleTreeMutation(requestId: Long?, targetMessageId: String?)

    fun failTreeMutation(requestId: Long?)

    fun showSnackbar(message: String)
    /** Opens [conversationId] on this client (for example the conversation its fork created). */
    fun openConversation(conversationId: String)
    /** Hands share text produced for this client's share request to its share surface. */
    fun showShareText(text: String)
    /** [conversationId], which this client shows, was deleted; move this client to New Chat. */
    fun settleDeletedConversation(conversationId: String)
    /** True while this client's composer is submitting into [conversationId]. */
    fun isSubmissionFrozen(conversationId: String): Boolean

    // -- Runtime state notices every attached client receives.
    /** [conversationId]'s generation slot became active or idle; drives this client's loading state. */
    fun onGenerationActivityChanged(conversationId: String, active: Boolean)
}

/** Room projection fences opened on each client render store for one accepted input. */
internal class ChatClientRoomFences internal constructor(
    internal val byStore: Map<ConversationRenderStore, RoomMessageProjectionFence>,
)

/**
 * The clients attached to the runtime.
 *
 * A conversation counts as open (or visible) when any attached client has it open (or visible).
 * Conversation-scoped graph changes go to every client that has that conversation open at the
 * moment of the write.
 */
internal class ChatClients {
    private val attached = CopyOnWriteArrayList<ChatClient>()

    fun attach(client: ChatClient) {
        attached.addIfAbsent(client)
    }

    fun detach(client: ChatClient) {
        attached.remove(client)
    }

    fun isConversationOpen(conversationId: String): Boolean =
        attached.any { it.openConversationId == conversationId }

    fun isConversationVisible(conversationId: String): Boolean =
        attached.any { it.isConversationVisible(conversationId) }

    fun commitGraph(
        conversationId: String,
        committedMessages: List<ChatMessage>,
        selectedChildren: Map<String?, String>,
        streamingMessage: ChatMessage?,
        fences: ChatClientRoomFences? = null,
    ) {
        val stores = renderStoresShowing(conversationId)
        stores.forEach { store ->
            store.commitGraph(
                committedMessages = committedMessages,
                selectedChildren = selectedChildren,
                streamingMessage = streamingMessage,
                roomProjectionFence = fences?.byStore?.get(store),
            )
        }
        // A client that left the conversation after its fence opened must still release it.
        fences?.byStore?.forEach { (store, fence) ->
            if (store !in stores) store.releaseRoomMessageProjectionFence(fence)
        }
    }

    fun replaceGraph(
        conversationId: String,
        allMessages: List<ChatMessage>,
        selectedChildren: Map<String?, String>,
    ) {
        renderStoresShowing(conversationId).forEach { store ->
            store.replaceGraph(allMessages = allMessages, selectedChildren = selectedChildren)
        }
    }

    /** Opens a fence on every client showing [conversationId]; null when no client shows it. */
    fun beginRoomProjectionFences(conversationId: String): ChatClientRoomFences? =
        renderStoresShowing(conversationId)
            .associateWith { it.beginRoomMessageProjectionFence() }
            .takeIf { it.isNotEmpty() }
            ?.let(::ChatClientRoomFences)

    fun releaseRoomProjectionFences(fences: ChatClientRoomFences) {
        fences.byStore.forEach { (store, fence) -> store.releaseRoomMessageProjectionFence(fence) }
    }

    /** The origin client of a command, or every client showing [conversationId] when there is none. */
    fun effectTargets(conversationId: String, origin: ChatClient?): List<ChatClient> =
        origin?.let(::listOf) ?: showing(conversationId)

    /** Every attached client that has [conversationId] open. */
    fun showing(conversationId: String): List<ChatClient> =
        attached.filter { it.openConversationId == conversationId }

    /** True while any attached client is submitting into [conversationId]. */
    fun isSubmissionFrozen(conversationId: String): Boolean =
        attached.any { it.isSubmissionFrozen(conversationId) }

    fun generationActivityChanged(conversationId: String, active: Boolean) =
        attached.forEach { it.onGenerationActivityChanged(conversationId, active) }

    fun commitTerminalStreamingMessage(conversationId: String, message: ChatMessage) =
        renderStoresShowing(conversationId).forEach { it.commitTerminalStreamingMessage(message) }

    /** Render stores of every client that has [conversationId] open. */
    fun renderStoresShowing(conversationId: String): List<ConversationRenderStore> =
        attached.filter { it.openConversationId == conversationId }.map { it.renderStore }
}
