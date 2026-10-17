package com.newoether.agora.ui.chat

import com.newoether.agora.model.ChatMessage
import com.newoether.agora.model.Participant
import org.junit.Assert.*
import org.junit.Test

class MessageListPageSpacingTest {
    private fun message(id: String, role: Participant, page: String?, turn: String? = "turn") =
        ChatMessage(id = id, text = "body", participant = role, timestamp = 0, displayPageId = page, runId = turn)

    @Test fun sameAssistantTurnHasNoMessageShellGapAtEitherPageSeam() {
        val fragments = (1..3).map { message("fragment-$it", Participant.MODEL, "page-$it") }
        val spacing = messageListPageTrailingSpacing(fragments)
        assertEquals(0, spacing[fragments[0].id])
        assertEquals(0, spacing[fragments[1].id])
        assertEquals(24, spacing[fragments[2].id])
    }

    @Test fun ordinaryAdjacentMessagesRetainTheirOriginalCombinedSpacing() {
        val user = message("u", Participant.USER, "page")
        val answer = message("a", Participant.MODEL, "page")
        val nextUser = message("next-u", Participant.USER, "next-page", "next-turn")
        val nextAnswer = message("next-a", Participant.MODEL, "next-page", "next-turn")
        val spacing = messageListPageTrailingSpacing(listOf(user, answer, nextUser, nextAnswer))
        assertEquals(8, messageListPageLeadingSpacing(user))
        assertEquals(22, spacing[user.id]) // User bottom8 + assistant top8 + original status6.
        assertEquals(32, spacing[answer.id]) // Assistant bottom16+8 + user top8.
        assertEquals(22, spacing[nextUser.id])
        assertEquals(24, spacing[nextAnswer.id])
        assertTrue(messageListPageTrailingSpacing(listOf(user.copy(displayPageId = null))).isEmpty())
    }

    @Test fun differentOrUnknownTurnsCannotLoseNormalSeparation() {
        val a = message("a", Participant.MODEL, "page")
        for (turn in listOf(null, "", "another-turn")) {
            val b = message("b", Participant.MODEL, "next-page", turn)
            assertEquals(38, messageListPageTrailingSpacing(listOf(a, b))[a.id])
        }
        val b = message("b", Participant.MODEL, "page")
        assertEquals(38, messageListPageTrailingSpacing(listOf(a, b))[a.id])
    }

    @Test fun repeatedPrependKeepsEveryExistingContentOriginHeightAndLazyKey() {
        val cache = MessageListTurnCache()
        val visible = message("visible", Participant.MODEL, "current")
        val next = message("next", Participant.USER, "current", "next-turn")
        var messages = listOf(visible, next)
        val originalSpacing = messageListPageTrailingSpacing(messages)
        val originalItems = cache.update(messages)
        repeat(200) { index ->
            val page = "older-$index"
            messages = listOf(message(page, Participant.MODEL, page)) + messages
            val spacing = messageListPageTrailingSpacing(messages)
            originalSpacing.forEach { (id, height) -> assertEquals(height, spacing[id]) }
            val items = cache.update(messages)
            originalItems.forEach { original -> assertSame(original, items.first { it.key == original.key }) }
            assertEquals(0, spacing[page])
        }
    }
}
