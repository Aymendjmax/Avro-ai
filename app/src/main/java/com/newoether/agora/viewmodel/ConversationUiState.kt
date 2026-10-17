package com.newoether.agora.viewmodel

import com.newoether.agora.model.ChatMessage
import com.newoether.agora.model.MessageGenerationBoundaryResolver
import com.newoether.agora.model.Participant
import com.newoether.agora.util.Constants

/**
 * Freezes the nearest real USER ancestor before a destructive subtree mutation.
 *
 * Tool/result protocol rows can also use Participant.USER, so they are traversed but never selected
 * as a visible scroll destination. The visited set makes corrupted legacy cycles fail closed.
 */
internal fun nearestUserAncestorId(
    messages: List<ChatMessage>,
    messageId: String,
): String? {
    val byId = messages.distinctBy(ChatMessage::id).associateBy(ChatMessage::id)
    var parentId = byId[messageId]?.parentId
    val visited = hashSetOf<String>()
    while (parentId != null && visited.add(parentId)) {
        val parent = byId[parentId] ?: return null
        if (MessageGenerationBoundaryResolver.isRealUser(parent)) return parent.id
        parentId = parent.parentId
    }
    return null
}

/**
 * Chooses the covered jump-cut destination after deleting a structural message subtree.
 *
 * A real USER message is one edit branch among siblings. If another sibling survives, keep the
 * viewport at that branch level; only deleting the last sibling may fall back to the nearest real
 * USER ancestor. Other message types retain the historical nearest-USER-ancestor behavior.
 */
internal fun deleteSettlementTargetMessageId(
    messagesBeforeDelete: List<ChatMessage>,
    deletedRootMessageId: String,
    remainingPath: List<ChatMessage>,
): String? {
    val deletedRoot = messagesBeforeDelete.firstOrNull { it.id == deletedRootMessageId }
    if (deletedRoot?.isRealUserMessage() == true) {
        remainingPath.firstOrNull { message ->
            message.isRealUserMessage() && message.parentId == deletedRoot.parentId
        }?.let { survivingSibling ->
            return survivingSibling.id
        }
    }

    return nearestUserAncestorId(messagesBeforeDelete, deletedRootMessageId)
        ?.takeIf { ancestorId -> remainingPath.any { it.id == ancestorId } }
        ?: remainingPath.firstOrNull(ChatMessage::isRealUserMessage)?.id
}

private fun ChatMessage.isRealUserMessage(): Boolean =
    MessageGenerationBoundaryResolver.isRealUser(this)

/** One selected-branch resolver shared by full UI messages and payload-free context topology. */
internal fun <T> resolveSelectedPath(
    allMessages: List<T>,
    streamingMessage: T?,
    selectedChildren: Map<String?, String>,
    idOf: (T) -> String,
    parentIdOf: (T) -> String?,
    timestampOf: (T) -> Long,
    isSynthetic: (T) -> Boolean,
    hideWhileStreaming: (message: T, streaming: T) -> Boolean = { _, _ -> false },
): List<T> {
    val path = mutableListOf<T>()
    val messagesForPath = streamingMessage?.let { streaming ->
        allMessages.filterNot { message -> hideWhileStreaming(message, streaming) }
    } ?: allMessages
    val messagesByParent = messagesForPath.groupBy(parentIdOf)
        .mapValues { (_, list) -> list.sortedBy(timestampOf) }
    val visited = mutableSetOf<String>()
    var cursor: String? = null

    while (true) {
        val siblings = messagesByParent[cursor].orEmpty()
        if (siblings.isEmpty()) break

        val selectedId = selectedChildren[cursor]
        val visibleSiblings = siblings.filterNot(isSynthetic)
        val selected = if (visibleSiblings.isNotEmpty()) {
            visibleSiblings.find { idOf(it) == selectedId } ?: visibleSiblings.last()
        } else {
            siblings.find { idOf(it) == selectedId } ?: siblings.last()
        }
        val selectedMessageId = idOf(selected)
        check(visited.add(selectedMessageId)) { "Selected message path contains a cycle" }
        if (!isSynthetic(selected) ||
            (streamingMessage != null && selectedMessageId == idOf(streamingMessage))
        ) {
            path += selected
        }
        cursor = selectedMessageId
    }

    return applyStreamingItemToResolvedPath(
        resolvedPath = path,
        streamingItem = streamingMessage,
        idOf = idOf,
        parentIdOf = parentIdOf,
    )
}

private fun <T> applyStreamingItemToResolvedPath(
    resolvedPath: List<T>,
    streamingItem: T?,
    idOf: (T) -> String,
    parentIdOf: (T) -> String?,
): List<T> {
    if (streamingItem == null) return resolvedPath
    val streamingId = idOf(streamingItem)
    val existingIndex = resolvedPath.indexOfFirst { item -> idOf(item) == streamingId }
    if (existingIndex >= 0) {
        if (resolvedPath[existingIndex] == streamingItem) return resolvedPath
        return resolvedPath.toMutableList().also { path ->
            path[existingIndex] = streamingItem
        }
    }

    val lastId = resolvedPath.lastOrNull()?.let(idOf)
    return if (
        parentIdOf(streamingItem) == lastId ||
        (parentIdOf(streamingItem) == null && resolvedPath.isEmpty())
    ) {
        resolvedPath + streamingItem
    } else {
        resolvedPath
    }
}

internal fun applyRenderSnapshotToResolvedPath(
    resolvedPath: List<ChatMessage>,
    snapshot: ConversationRenderSnapshot,
    latestMessagesById: Map<String, ChatMessage> = snapshot.allMessages.associateBy(ChatMessage::id),
): List<ChatMessage> {
    val reboundPath = resolvedPath.map { message ->
        latestMessagesById[message.id] ?: message
    }
    return applyStreamingMessageToResolvedPath(reboundPath, snapshot.streamingMessage)
}

internal fun applyStreamingMessageToResolvedPath(
    resolvedPath: List<ChatMessage>,
    streamingMessage: ChatMessage?,
): List<ChatMessage> {
    if (streamingMessage == null) return resolvedPath
    val pendingIndex = resolvedPath.indexOfFirst { message ->
        isPendingVisibleIntervention(
            message = message,
            streamingRunId = streamingMessage.runId,
        )
    }
    val eligiblePath = if (pendingIndex >= 0) resolvedPath.take(pendingIndex) else resolvedPath
    return applyStreamingItemToResolvedPath(
        resolvedPath = eligiblePath,
        streamingItem = streamingMessage,
        idOf = ChatMessage::id,
        parentIdOf = ChatMessage::parentId,
    )
}

/**
 * Only a real user intervention can be queue-only while a Pass is streaming. Tool-result rows
 * also use Participant.USER and consumedAtPass=null, but they remain durable protocol edges.
 */
internal fun isPendingVisibleIntervention(
    message: ChatMessage,
    streamingRunId: String?,
): Boolean =
    message.participant == Participant.USER &&
        !message.id.startsWith(Constants.TOOL_MSG_PREFIX) &&
        !message.id.startsWith(Constants.RESULT_MSG_PREFIX) &&
        !streamingRunId.isNullOrBlank() &&
        message.runId == streamingRunId &&
        message.consumedAtPass == null

data class ConversationUiState(
    val path: List<ChatMessage> = emptyList(),
    val allMessages: List<ChatMessage> = emptyList(),
    val streamingMsg: ChatMessage? = null,
    val isLoading: Boolean = false,
    val selectedChildren: Map<String?, String> = emptyMap()
) {
    companion object {
        /** Walk the conversation tree to produce the visible path. */
        fun resolvePath(
            allMessages: List<ChatMessage>,
            streamingMsg: ChatMessage?,
            selectedChildren: Map<String?, String>
        ): List<ChatMessage> = resolveSelectedPath(
            allMessages = allMessages,
            streamingMessage = streamingMsg,
            selectedChildren = selectedChildren,
            idOf = ChatMessage::id,
            parentIdOf = ChatMessage::parentId,
            timestampOf = ChatMessage::timestamp,
            isSynthetic = ::isSynthetic,
            hideWhileStreaming = { message, streaming ->
                isPendingVisibleIntervention(
                    message = message,
                    streamingRunId = streaming.runId,
                )
            },
        )

        private fun isSynthetic(message: ChatMessage): Boolean =
            message.id.startsWith(Constants.TOOL_MSG_PREFIX) ||
                message.id.startsWith(Constants.RESULT_MSG_PREFIX)
    }
}
