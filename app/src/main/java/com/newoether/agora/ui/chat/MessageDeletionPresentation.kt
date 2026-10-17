package com.newoether.agora.ui.chat

import com.newoether.agora.model.ChatMessage

internal fun deletionRemovesEntireConversation(
    messages: List<ChatMessage>,
    rootMessageId: String,
    compactOnly: Boolean = false,
): Boolean {
    if (messages.isEmpty() || messages.none { it.id == rootMessageId }) return false
    if (compactOnly) return messages.size == 1
    val childrenByParent = messages.groupBy(ChatMessage::parentId)
    val deletedIds = linkedSetOf(rootMessageId)
    val pending = ArrayDeque<String>().apply { add(rootMessageId) }
    while (pending.isNotEmpty()) {
        childrenByParent[pending.removeFirst()].orEmpty().forEach { child ->
            if (deletedIds.add(child.id)) pending.add(child.id)
        }
    }
    return deletedIds == messages.mapTo(linkedSetOf(), ChatMessage::id)
}
