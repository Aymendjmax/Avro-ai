package com.newoether.agora.ui.chat

import com.newoether.agora.model.ChatMessage
import com.newoether.agora.model.Participant
import com.newoether.agora.ui.chat.message.escapeForMarkdown
import com.newoether.agora.util.Constants
import org.intellij.markdown.MarkdownElementTypes
import org.intellij.markdown.MarkdownTokenTypes
import org.intellij.markdown.ast.ASTNode
import org.intellij.markdown.flavours.gfm.GFMFlavourDescriptor
import org.intellij.markdown.parser.MarkdownParser

internal data class ConversationSearchMatch(
    val messageId: String,
    val start: Int,
    val endExclusive: Int,
    val occurrenceInMessage: Int,
    val citationSourceId: String? = null,
) {
    val key: String get() = citationSourceId?.let { sourceId ->
        "$messageId:citation:$sourceId:$start:$endExclusive"
    } ?: "$messageId:$start:$endExclusive"
}

private const val CONVERSATION_SEARCH_PAYLOAD_PAGE_SIZE = 64

internal fun isConversationSearchBodyEligible(message: ChatMessage): Boolean =
    (message.participant == Participant.USER || message.participant == Participant.MODEL) &&
        !message.id.startsWith(Constants.TOOL_MSG_PREFIX) &&
        !message.id.startsWith(Constants.RESULT_MSG_PREFIX) &&
        !message.id.startsWith(Constants.COMPACT_MSG_PREFIX)

internal fun conversationSearchMessageIds(messages: List<ChatMessage>): List<String> =
    messages.filter(::isConversationSearchBodyEligible).map(ChatMessage::id)

internal suspend fun scanConversationSearchMatches(
    selectedPathMessageIds: List<String>,
    query: String,
    loadMessages: suspend (List<String>) -> List<ChatMessage>,
): List<ConversationSearchMatch> {
    if (query.isBlank()) return emptyList()
    return buildList {
        selectedPathMessageIds.chunked(CONVERSATION_SEARCH_PAYLOAD_PAGE_SIZE).forEach { pageIds ->
            val messagesById = loadMessages(pageIds).associateBy(ChatMessage::id)
            val orderedPage = pageIds.mapNotNull(messagesById::get)
            addAll(findConversationSearchMatches(orderedPage, query))
        }
    }
}

internal fun caseInsensitiveMatchRanges(text: String, query: String): List<IntRange> {
    if (query.isBlank()) return emptyList()
    return buildList {
        var from = 0
        while (from <= text.length - query.length) {
            val index = text.indexOf(query, startIndex = from, ignoreCase = true)
            if (index < 0) break
            add(index until index + query.length)
            from = index + query.length.coerceAtLeast(1)
        }
    }
}

internal fun findConversationSearchMatches(
    messages: List<ChatMessage>,
    query: String,
): List<ConversationSearchMatch> {
    if (query.isBlank()) return emptyList()
    return buildList {
        messages.forEach { message ->
            if (!isConversationSearchBodyEligible(message)) return@forEach
            conversationSearchMatchRanges(message, query).forEachIndexed { occurrence, range ->
                add(
                    ConversationSearchMatch(
                        messageId = message.id,
                        start = range.first,
                        endExclusive = range.last + 1,
                        occurrenceInMessage = occurrence,
                    ),
                )
            }
        }
    }
}

internal fun conversationSearchMatchRanges(
    message: ChatMessage,
    query: String,
): List<IntRange> {
    val sourceMatches = caseInsensitiveMatchRanges(message.text, query)
    if (message.participant == Participant.USER || sourceMatches.isEmpty()) return sourceMatches

    // Markdown rendering inserts a few protective characters without changing occurrence order.
    // Pair the visible prepared-source occurrences back to persisted-source ranges so match keys
    // remain stable and hidden URL/image syntax never inflates the visible result count.
    val prepared = message.text.escapeForMarkdown()
    val preparedMatches = caseInsensitiveMatchRanges(prepared, query)
    val visiblePrepared = visibleMarkdownMatchRanges(prepared, query).toSet()
    return preparedMatches.indices.mapNotNull { index ->
        preparedMatches[index]
            .takeIf(visiblePrepared::contains)
            ?.let { sourceMatches.getOrNull(index) }
    }
}

internal fun visibleMarkdownMatchRanges(
    content: String,
    query: String,
): List<IntRange> {
    val matches = caseInsensitiveMatchRanges(content, query)
    if (matches.isEmpty()) return emptyList()
    val hiddenRanges = runCatching {
        val root = MarkdownParser(GFMFlavourDescriptor()).buildMarkdownTreeFromString(content)
        buildList { root.collectHiddenMarkdownRanges(this) }
    }.getOrDefault(emptyList())
    return matches.filter { match ->
        hiddenRanges.none { hidden ->
            match.first >= hidden.first && match.last <= hidden.last
        }
    }
}

private fun ASTNode.collectHiddenMarkdownRanges(target: MutableList<IntRange>) {
    val hidden = type == MarkdownElementTypes.LINK_DESTINATION ||
        type == MarkdownElementTypes.LINK_DEFINITION ||
        type == MarkdownElementTypes.IMAGE ||
        type == MarkdownTokenTypes.FENCE_LANG
    if (hidden) {
        if (endOffset > startOffset) target += startOffset until endOffset
        return
    }
    children.forEach { child -> child.collectHiddenMarkdownRanges(target) }
}

internal fun nearestConversationSearchMatchIndex(
    matches: List<ConversationSearchMatch>,
    turnIndexByMessageId: Map<String, Int>,
    anchorTurnIndex: Int,
): Int = matches.indices.minByOrNull { index ->
    kotlin.math.abs(
        (turnIndexByMessageId[matches[index].messageId] ?: Int.MAX_VALUE / 2) -
            anchorTurnIndex
    )
} ?: -1

internal fun nearestVisibleConversationSearchMatchIndex(
    matches: List<ConversationSearchMatch>,
    distanceByMatchKey: Map<String, Float>,
): Int? = matches.indices
    .filter { index -> matches[index].key in distanceByMatchKey }
    .minByOrNull { index -> distanceByMatchKey.getValue(matches[index].key) }

internal fun retainedConversationSearchMatchIndex(
    previous: List<ConversationSearchMatch>,
    current: List<ConversationSearchMatch>,
    previousIndex: Int,
): Int? = previous.getOrNull(previousIndex)?.key?.let { key ->
    current.indexOfFirst { it.key == key }.takeIf { it >= 0 }
}
