package com.newoether.agora.data.repository

import com.newoether.agora.data.local.MessageEntity
import com.newoether.agora.model.CitationPolicy
import com.newoether.agora.model.MessagePersistenceGuard
import com.newoether.agora.model.MessageStatus
import com.newoether.agora.model.Participant
import com.newoether.agora.model.toMessageSegment
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CitationSearchPolicyTest {
    @Test
    fun persistedCitationSearchUsesTitleButNotUrlOrPrivateId() {
        val citation = requireNotNull(
            CitationPolicy.create(
                provider = "openai",
                kind = "url",
                title = "Kotlin language guide",
                url = "https://private.example/internal-path",
                providerSourceId = "turn0search7",
            ),
        )
        val message = MessageEntity(
            id = "message",
            conversationId = "conversation",
            text = "Answer",
            status = MessageStatus.SUCCESS,
            participant = Participant.MODEL,
            timestamp = 1L,
            toolCallJson = MessagePersistenceGuard.encodeSegmentsBounded(
                listOf(citation.toMessageSegment()),
            ),
            runId = "run",
        )

        assertTrue(message.matchesCitationTitle("LANGUAGE"))
        assertFalse(message.matchesCitationTitle("private.example"))
        assertFalse(message.matchesCitationTitle("turn0search7"))
        assertFalse(message.copy(toolCallJson = "not-json").matchesCitationTitle("language"))
    }

    @Test
    fun boundedSearchUsesPrimaryKeyPagesAndRetainsOnlyNewestMatches() = runTest {
        val candidates = listOf(
            citationMessage("a", 100L, "Other"),
            citationMessage("b", 500L, "Needle one"),
            citationMessage("c", 300L, "Needle two"),
            citationMessage("d", 600L, "Other again"),
            citationMessage("e", 400L, "Needle three"),
        )
        val cursors = mutableListOf<String>()
        var largestPage = 0

        val result = boundedCitationTitleMatches(
            query = "needle",
            limit = 2,
            pageSize = 2,
        ) { afterId, pageSize ->
            cursors += afterId
            candidates.filter { it.id > afterId }.take(pageSize).also { page ->
                largestPage = maxOf(largestPage, page.size)
            }
        }

        assertEquals(listOf("b", "e"), result.map(MessageEntity::id))
        assertEquals(listOf("", "b", "d"), cursors)
        assertTrue(largestPage <= 2)
    }

    @Test
    fun boundedSearchUsesIdAsDeterministicTieBreaker() = runTest {
        val candidates = listOf(
            citationMessage("a", 10L, "Needle A"),
            citationMessage("b", 10L, "Needle B"),
            citationMessage("c", 10L, "Needle C"),
        )

        val result = boundedCitationTitleMatches(
            query = "needle",
            limit = 2,
            pageSize = 1,
        ) { afterId, pageSize ->
            candidates.filter { it.id > afterId }.take(pageSize)
        }

        assertEquals(listOf("c", "b"), result.map(MessageEntity::id))
    }

    @Test
    fun boundedSearchScansNoMatchPagesWithoutAccumulatingCandidates() = runTest {
        val candidates = (0 until 5).map { index ->
            citationMessage(index.toString(), index.toLong(), "Other $index")
        }
        var calls = 0

        val result = boundedCitationTitleMatches(
            query = "missing",
            limit = 3,
            pageSize = 2,
        ) { afterId, pageSize ->
            calls++
            candidates.filter { it.id > afterId }.take(pageSize)
        }

        assertTrue(result.isEmpty())
        assertEquals(3, calls)
    }

    private fun citationMessage(id: String, timestamp: Long, title: String): MessageEntity {
        val citation = requireNotNull(
            CitationPolicy.create(
                provider = "openai",
                kind = "url",
                title = title,
                url = "https://example.com/$id",
            ),
        )
        return MessageEntity(
            id = id,
            conversationId = "conversation",
            text = "Answer",
            status = MessageStatus.SUCCESS,
            participant = Participant.MODEL,
            timestamp = timestamp,
            toolCallJson = MessagePersistenceGuard.encodeSegmentsBounded(
                listOf(citation.toMessageSegment()),
            ),
            runId = "run-$id",
        )
    }
}
