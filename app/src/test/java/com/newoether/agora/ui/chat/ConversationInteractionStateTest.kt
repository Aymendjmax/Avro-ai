package com.newoether.agora.ui.chat

import com.newoether.agora.model.ChatMessage
import com.newoether.agora.model.Participant
import java.io.File
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConversationInteractionStateTest {
    @Test
    fun `search and share selection remain mutually exclusive`() {
        val projection = projection(selectableIds = setOf("one", "two"))

        projection.activateSearch()
        assertTrue(projection.searchActive)

        projection.activateShareSelection()
        assertFalse(projection.searchActive)
        assertTrue(projection.shareSelectionActive)

        projection.activateSearch()
        assertTrue(projection.searchActive)
        assertFalse(projection.shareSelectionActive)
        assertTrue(projection.selectedShareMessageIds.isEmpty())
    }

    @Test
    fun `search navigation respects current match bounds`() {
        val projection = projection(matchCount = 3, initialSearchMatchIndex = 0)

        assertFalse(projection.previousSearchMatch())
        assertTrue(projection.nextSearchMatch())
        assertEquals(1, projection.searchMatchIndex)
        assertTrue(projection.nextSearchMatch())
        assertEquals(2, projection.searchMatchIndex)
        assertFalse(projection.nextSearchMatch())
    }

    @Test
    fun `search navigation follows exact match identity without wrapping`() {
        val matches = listOf(
            ConversationSearchMatch("message-a", 2, 8, 0),
            ConversationSearchMatch("message-a", 12, 18, 1),
            ConversationSearchMatch("message-b", 1, 7, 0),
        )
        val projection = projection(
            searchMatches = matches,
            initialSearchMatchIndex = 1,
        )

        assertEquals(matches[1], projection.searchMatches[projection.searchMatchIndex])
        assertTrue(projection.previousSearchMatch())
        assertEquals(matches[0], projection.searchMatches[projection.searchMatchIndex])
        assertFalse(projection.previousSearchMatch())
        assertTrue(projection.nextSearchMatch())
        assertEquals(matches[1], projection.searchMatches[projection.searchMatchIndex])
        assertTrue(projection.nextSearchMatch())
        assertEquals(matches[2], projection.searchMatches[projection.searchMatchIndex])
        assertFalse(projection.nextSearchMatch())
    }

    @Test
    fun `async results retain glyph distances from the same query`() {
        val first = ConversationSearchMatch("message-a", 0, 6, 0)
        val second = ConversationSearchMatch("message-b", 8, 14, 0)
        val state = ConversationInteractionState(
            initialSearchActive = true,
            initialSearchQuery = "needle",
        )

        state.recordSearchMatchDistance(second.key, 4f)

        assertEquals(
            mapOf(second.key to 4f),
            state.visibleSearchMatchDistances(listOf(first, second)),
        )
        state.updateSearchQuery("other")
        assertTrue(state.visibleSearchMatchDistances(listOf(first, second)).isEmpty())
    }

    @Test
    fun `nonzero list root still centers the exact glyph in list coordinates`() {
        val listRootInRoot = 240f
        val turnOffsetInList = 120f
        val centerInTurn = 90f
        val glyphCenterInRoot = listRootInRoot + turnOffsetInList + centerInTurn
        val targetCenterInList = turnOffsetInList + centerInTurn

        val measuredCenterInTurn = searchMatchCenterInTurnPx(
            glyphCenterInRootPx = glyphCenterInRoot,
            listRootInRootPx = listRootInRoot,
            turnOffsetInListPx = turnOffsetInList,
        )

        assertEquals(centerInTurn, measuredCenterInTurn, 0f)
        assertEquals(
            0f,
            searchMatchScrollErrorPx(
                turnOffsetInListPx = turnOffsetInList,
                matchCenterInTurnPx = measuredCenterInTurn,
                viewportCenterInListPx = targetCenterInList,
            ),
            0f,
        )
    }

    @Test
    fun `reduced motion uses the same list local centering equation`() {
        assertEquals(
            -310,
            searchMatchScrollOffsetPx(
                matchCenterInTurnPx = 90f,
                viewportCenterInListPx = 400f,
            ),
        )
    }

    @Test
    fun `reduced motion settles on exact glyph after estimated positioning`() {
        val source = File(
            mainSourceRoot(),
            "com/newoether/agora/ui/chat/MessageList.kt",
        ).readText().replace("\r\n", "\n")
        val reducedMotionBlock = source
            .substringAfter("activeSearchMatch?.key,")
            .substringAfter("if (!motionPolicy.allowProgrammaticScrollMotion) {")
            .substringBefore("state.smoothSeekToItem(")

        assertEquals(2, reducedMotionBlock.split("state.scrollToItem(").size - 1)
        assertTrue(
            reducedMotionBlock.contains(
                "snapshotFlow {\n                searchMatchCentersInTurn[match.key]\n            }",
            ),
        )
        assertTrue(reducedMotionBlock.contains(".first { it != null }!!"))
        assertTrue(reducedMotionBlock.contains("matchCenterInTurnPx = exactCenterInTurn"))
    }

    @Test
    fun `message list turns provide the offscreen fallback order`() {
        val state = ConversationInteractionState()
        val match = ConversationSearchMatch("assistant", 0, 6, 0)
        state.recordSearchTurns(
            listOf(
                MessageListTurn(
                    key = "turn",
                    messages = listOf(
                        ChatMessage(id = "user", text = "", participant = Participant.USER),
                        ChatMessage(id = "assistant", text = "", participant = Participant.MODEL),
                    ),
                ),
            ),
        )

        assertEquals(mapOf("assistant" to 0), state.searchTurnIndexes(listOf(match)))
    }

    @Test
    fun `search geometry accepts only the current measurement epoch`() {
        assertTrue(acceptsSearchMatchMeasurement(null, "visible", null))
        assertFalse(acceptsSearchMatchMeasurement(null, "visible", "old"))
        assertTrue(acceptsSearchMatchMeasurement("active", "active", "active"))
        assertFalse(acceptsSearchMatchMeasurement("active", "old", "active"))
        assertFalse(acceptsSearchMatchMeasurement("active", "active", "old"))
    }

    @Test
    fun selectedMatchIsStableInTheFrameBeforePageReconciliation() {
        val first = ConversationSearchMatch("first", 0, 6, 0)
        val selected = ConversationSearchMatch("selected", 0, 6, 0)
        val older = ConversationSearchMatch("older", 0, 6, 0)
        val state = ConversationInteractionState(initialSearchMatchIndex = 0)
        val before = ConversationInteractionProjection(state, emptySet(), listOf(first, selected))
        assertTrue(before.nextSearchMatch())
        val request = before.searchScrollRequestKey
        val after = ConversationInteractionProjection(state, emptySet(), listOf(older, first, selected))
        assertEquals(2, after.searchMatchIndex)
        assertEquals(selected, after.searchMatches[after.searchMatchIndex])
        assertEquals(request, after.searchScrollRequestKey)
        assertTrue(after.previousSearchMatch())
        assertEquals(first, after.searchMatches[after.searchMatchIndex])
        assertNotEquals(request, after.searchScrollRequestKey)
    }

    @Test
    fun `taking share selection clears it exactly once`() {
        val projection = projection(selectableIds = linkedSetOf("one", "two"))
        projection.activateShareSelection()
        projection.toggleShareMessage("one")

        assertEquals(setOf("one"), projection.takeShareSelection())
        assertFalse(projection.shareSelectionActive)
        assertTrue(projection.selectedShareMessageIds.isEmpty())
        assertTrue(projection.takeShareSelection().isEmpty())
    }

    private fun mainSourceRoot(): File = locate("app/src/main/java")

    private fun locate(relative: String): File {
        var directory = File(requireNotNull(System.getProperty("user.dir"))).absoluteFile
        repeat(8) {
            File(directory, relative).takeIf(File::isDirectory)?.let { return it }
            directory = directory.parentFile ?: error("Reached filesystem root")
        }
        error("Unable to locate $relative")
    }

    private fun projection(
        selectableIds: Set<String> = emptySet(),
        matchCount: Int = 0,
        searchMatches: List<ConversationSearchMatch> = List(matchCount) { index ->
            ConversationSearchMatch(
                messageId = "message-$index",
                start = index,
                endExclusive = index + 1,
                occurrenceInMessage = index,
            )
        },
        initialSearchMatchIndex: Int = -1,
    ): ConversationInteractionProjection {
        val state = ConversationInteractionState(
            initialSearchMatchIndex = initialSearchMatchIndex,
        )
        return ConversationInteractionProjection(
            state = state,
            selectableShareMessageIds = selectableIds,
            searchMatches = searchMatches,
        )
    }

}
