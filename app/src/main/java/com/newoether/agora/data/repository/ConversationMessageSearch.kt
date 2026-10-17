package com.newoether.agora.data.repository

import com.newoether.agora.data.local.ChatDao
import com.newoether.agora.data.local.MessageEntity
import com.newoether.agora.model.MessageSegment
import com.newoether.agora.model.citationRecords
import com.newoether.agora.model.matchesCitationTitle
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json

internal fun MessageEntity.matchesCitationTitle(query: String): Boolean {
    val segments = toolCallJson?.let { raw ->
        runCatching { Json.decodeFromString<List<MessageSegment>>(raw) }.getOrNull()
    }.orEmpty()
    return segments.citationRecords(text).matchesCitationTitle(query)
}
private val citationSearchNewestFirst =
    compareByDescending<MessageEntity>(MessageEntity::timestamp).thenByDescending(MessageEntity::id)

internal suspend fun boundedCitationTitleMatches(
    query: String,
    limit: Int,
    pageSize: Int = 128,
    loadPage: suspend (afterId: String, pageSize: Int) -> List<MessageEntity>,
): List<MessageEntity> {
    if (query.isBlank() || limit <= 0) return emptyList()
    val boundedPageSize = pageSize.coerceIn(1, 256)
    val newestMatches = mutableListOf<MessageEntity>()
    var afterId = ""
    while (true) {
        val page = loadPage(afterId, boundedPageSize)
        if (page.isEmpty()) break
        page.asSequence()
            .filter { it.id > afterId && it.matchesCitationTitle(query) }
            .forEach { candidate ->
                if (newestMatches.none { it.id == candidate.id }) {
                    newestMatches += candidate
                    newestMatches.sortWith(citationSearchNewestFirst)
                    if (newestMatches.size > limit) newestMatches.removeAt(newestMatches.lastIndex)
                }
            }
        val nextAfterId = page.maxOf(MessageEntity::id)
        if (nextAfterId <= afterId) break
        afterId = nextAfterId
        if (page.size < boundedPageSize) break
    }
    return newestMatches
}

internal suspend fun searchConversationMessages(chatDao: ChatDao, query: String, limit: Int): List<MessageEntity> {
    if (limit <= 0) return emptyList()
    val directMatches = chatDao.searchMessages(escapeLikePattern(query), limit)
    val citationMatches = boundedCitationTitleMatches(query, limit) { afterId, pageSize ->
        chatDao.getMessagesWithCitationSegmentsPage(afterId, pageSize)
    }
    return (directMatches + citationMatches)
        .distinctBy(MessageEntity::id)
        .sortedWith(citationSearchNewestFirst)
        .take(limit)
}

/** Escapes LIKE wildcards so a literal "%"/"_" in the user's query matches itself
 *  instead of matching everything (paired with ESCAPE '\' in the DAO query). */
private fun escapeLikePattern(query: String): String =
    query.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")
