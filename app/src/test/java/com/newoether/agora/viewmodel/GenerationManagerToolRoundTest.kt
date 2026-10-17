package com.newoether.agora.viewmodel

import com.newoether.agora.model.MessageSegment
import com.newoether.agora.model.MessageStatus
import com.newoether.agora.model.RunEndReason
import com.newoether.agora.model.RunStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GenerationManagerToolRoundTest {
    @Test
    fun queuedGuidanceClosesTheOriginRunBeforeItsFreshRunStarts() {
        val disposition = generationTerminalDisposition(
            messageStatus = MessageStatus.SUCCESS,
            hasPendingGuidance = true,
        )

        assertEquals(RunStatus.COMPLETED, disposition.runStatus)
        assertEquals(RunEndReason.MODEL_COMPLETED, disposition.endReason)
        assertFalse(disposition.markConversationUnread)
    }

    @Test
    fun terminalUnreadPolicyRetainsHeadlessAndUsesConversationVisibility() {
        fun unread(status: MessageStatus, visible: Boolean?) = generationTerminalDisposition(
            messageStatus = status,
            hasPendingGuidance = false,
            conversationVisible = visible,
        ).markConversationUnread

        val headlessSuccess = generationTerminalDisposition(MessageStatus.SUCCESS, false)
        assertEquals(RunStatus.COMPLETED, headlessSuccess.runStatus)
        assertEquals(RunEndReason.MODEL_COMPLETED, headlessSuccess.endReason)
        assertTrue(unread(MessageStatus.SUCCESS, null))
        assertFalse(unread(MessageStatus.ERROR, null))
        assertFalse(unread(MessageStatus.SUCCESS, true))
        assertTrue(unread(MessageStatus.SUCCESS, false))
        assertFalse(unread(MessageStatus.ERROR, true))
        assertTrue(unread(MessageStatus.ERROR, false))
    }

    @Test
    fun stoppedAndFailedExitsRemainTerminalWhenGuidanceIsPending() {
        assertEquals(
            GenerationTerminalDisposition(
                RunStatus.STOPPED,
                RunEndReason.USER_STOPPED,
                markConversationUnread = false,
            ),
            generationTerminalDisposition(MessageStatus.STOPPED, hasPendingGuidance = true),
        )
        assertEquals(
            GenerationTerminalDisposition(
                RunStatus.FAILED,
                RunEndReason.PROVIDER_ERROR,
                markConversationUnread = false,
            ),
            generationTerminalDisposition(MessageStatus.ERROR, hasPendingGuidance = true),
        )
    }

    @Test
    fun automaticCompactStartsANoInputLoopOnlyAfterSuccess() {
        assertTrue(automaticCompactAllowsHandoff(MessageStatus.SUCCESS))
        assertFalse(automaticCompactAllowsHandoff(MessageStatus.ERROR))
        assertFalse(automaticCompactAllowsHandoff(MessageStatus.STOPPED))
        assertFalse(automaticCompactAllowsHandoff(MessageStatus.SENDING))
        assertFalse(automaticCompactAllowsHandoff(null))
    }

    @Test
    fun toolRoundThoughtSegments_neverRepeatsEarlierReasoningOrSignatures() {
        val firstThought = MessageSegment(
            type = "thought",
            content = "first",
            signature = "signature-1",
        )
        val firstTool = MessageSegment(
            type = "tool",
            toolName = "first_tool",
            toolCallId = "call-1",
        )
        val secondThought = MessageSegment(
            type = "thought",
            content = "second",
            signature = "signature-2",
        )
        val secondTool = MessageSegment(
            type = "tool",
            toolName = "second_tool",
            toolCallId = "call-2",
        )
        val timeline = listOf(firstThought, firstTool, secondThought, secondTool)

        assertEquals(
            listOf(firstThought),
            toolRoundThoughtSegments(timeline.take(2), fromIndex = 0),
        )
        assertEquals(
            listOf(secondThought),
            toolRoundThoughtSegments(timeline, fromIndex = 2),
        )
    }

    @Test
    fun toolRoundThoughtSegments_clampsRecoveryCursorToCurrentTimeline() {
        val thought = MessageSegment(type = "thought", content = "current")

        assertEquals(
            emptyList<MessageSegment>(),
            toolRoundThoughtSegments(listOf(thought), fromIndex = 99),
        )
    }

    @Test
    fun toolRoundHistoryCompactor_removesOnlyStrictLegacyPrefix() {
        val firstThought = MessageSegment(
            type = "thought",
            content = "first",
            signature = "signature-1",
        )
        val secondThought = MessageSegment(
            type = "thought",
            content = "second",
            signature = "signature-2",
        )
        val firstTool = MessageSegment(type = "tool", toolName = "first")
        val secondTool = MessageSegment(type = "tool", toolName = "second")
        val compactor = ToolRoundHistoryCompactor()

        assertEquals(
            listOf(firstThought, firstTool),
            compactor.compact("run", listOf(firstThought, firstTool)),
        )
        assertEquals(
            listOf(secondThought, secondTool),
            compactor.compact(
                "run",
                listOf(firstThought, secondThought, secondTool),
            ),
        )
    }

    @Test
    fun toolRoundHistoryCompactor_preservesCurrentRowsAndEqualReasoning() {
        val repeatedThought = MessageSegment(
            type = "thought",
            content = "same",
            signature = "same-signature",
        )
        val nextThought = MessageSegment(type = "thought", content = "next")
        val firstTool = MessageSegment(type = "tool", toolName = "first")
        val secondTool = MessageSegment(type = "tool", toolName = "second")
        val thirdTool = MessageSegment(type = "tool", toolName = "third")
        val compactor = ToolRoundHistoryCompactor()

        compactor.compact("run", listOf(repeatedThought, firstTool))
        assertEquals(
            listOf(repeatedThought, secondTool),
            compactor.compact("run", listOf(repeatedThought, secondTool)),
        )
        assertEquals(
            listOf(nextThought, thirdTool),
            compactor.compact(
                "run",
                listOf(repeatedThought, nextThought, thirdTool),
            ),
        )
    }

    @Test
    fun toolRoundHistoryCompactor_keepsIndependentRunsSeparate() {
        val thought = MessageSegment(type = "thought", content = "shared")
        val tool = MessageSegment(type = "tool", toolName = "tool")
        val compactor = ToolRoundHistoryCompactor()

        compactor.compact("first-run", listOf(thought, tool))
        assertEquals(
            listOf(thought, thought.copy(content = "new"), tool),
            compactor.compact(
                "second-run",
                listOf(thought, thought.copy(content = "new"), tool),
            ),
        )
    }
}
