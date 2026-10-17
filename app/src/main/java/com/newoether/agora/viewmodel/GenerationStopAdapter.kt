package com.newoether.agora.viewmodel

import com.newoether.agora.model.ChatMessage
import com.newoether.agora.model.MessageStatus
import com.newoether.agora.model.Participant
import com.newoether.agora.model.isContextCompact
import com.newoether.agora.util.DebugLog

/**
 * Adapts a client's Stop intent for one conversation to that conversation's runtime and its
 * authorized durable finalization effect. It owns no Run state, Job, scope, barrier, or effect
 * identity. Render corrections go to every client showing the conversation; a persistence failure
 * is reported to the client that asked to stop.
 */
internal class GenerationStopAdapter(
    private val registry: ConversationStateRegistry,
    private val clients: ChatClients,
    private val finalizer: GenerationFinalizer,
    private val failureText: () -> String,
) {
    fun stop(conversationId: String?, origin: ChatClient) {
        conversationId ?: return
        val state = registry.get(conversationId) ?: return
        state.requestStop { result ->
            val stoppedMessage = result.stoppedMessage
            val messages = stoppedMessage?.let(::listOf)
                ?: snapshotStoppedRows(clients.renderStoresShowing(result.conversationId))
            val effect = result.finalizationEffect ?: return@requestStop
            // The finalizer receives exactly the reducer-emitted identity and returns an identified
            // persistence result to this same runtime host. It cannot release the slot itself.
            finalizer.launchStopFinalization(
                scope = state.scope,
                identity = effect.identity,
                messages = messages,
                onFinalized = { completion ->
                    val outcome = state.finishStopFinalization(completion)
                    // Delayed/duplicate/stale completions cannot change runtime or presentation.
                    if (!outcome.accepted) return@launchStopFinalization
                    if (completion.success) {
                        // Room invalidation and the runtime projection settle asynchronously. Keep
                        // the exact terminal overlay visible until the durable result is accepted.
                        if (stoppedMessage != null) {
                            clients.renderStoresShowing(result.conversationId).forEach { store ->
                                store.commitTerminalStreamingMessage(stoppedMessage)
                            }
                        }
                        state.clearStoppedOverlay()
                    } else {
                        origin.showSnackbar(failureText())
                    }
                },
            )
        }
    }

    /**
     * Without a streaming overlay, the stopped rows are the in-flight rows the clients render.
     * They are read from the first store showing the conversation (all show the same graph) and
     * marked STOPPED in every store; no store showing it means nothing to finalize.
     */
    private fun snapshotStoppedRows(stores: List<ConversationRenderStore>): List<ChatMessage> = runCatching {
        val source = stores.firstOrNull() ?: return@runCatching emptyList()
        source.allMessages.mapNotNull { message ->
            if (
                (message.participant == Participant.MODEL || message.isContextCompact()) &&
                message.status.isInFlight()
            ) {
                message.copy(status = MessageStatus.STOPPED)
            } else {
                null
            }
        }.also { stopped ->
            val byId = stopped.associateBy(ChatMessage::id)
            if (byId.isNotEmpty()) {
                stores.forEach { store ->
                    store.updateAllMessages { rows -> rows.map { byId[it.id] ?: it } }
                }
            }
        }
    }.getOrElse { error ->
        DebugLog.e("GenerationStopAdapter", "Failed to snapshot stopped render rows", error)
        emptyList()
    }
}

private fun MessageStatus.isInFlight(): Boolean =
    this == MessageStatus.SENDING ||
        this == MessageStatus.THINKING ||
        this == MessageStatus.TOOL_CALLING ||
        this == MessageStatus.TRANSCRIBING
