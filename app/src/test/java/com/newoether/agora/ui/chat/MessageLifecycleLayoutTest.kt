package com.newoether.agora.ui.chat

import com.newoether.agora.model.ChatMessage
import com.newoether.agora.model.MessageStatus
import com.newoether.agora.model.Participant
import com.newoether.agora.ui.chat.message.assistantActionAvailability
import com.newoether.agora.ui.chat.message.assistantActionsVisible
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MessageLifecycleLayoutTest {
    @Test
    fun lifecycleEntrancePlaysOnlyForNewActiveChainNodes() {
        val user = message("user", Participant.USER)
        val sending = message("assistant", Participant.MODEL).copy(
            parentId = user.id,
            status = MessageStatus.SENDING,
        )

        assertTrue(
            shouldAnimateMessageLifecycleEntrance(
                message = user,
                isKnown = false,
                isLoading = true,
                isStreaming = false,
                lastUserMessageId = user.id,
                requestedTargetMessageId = user.id,
            )
        )
        assertTrue(
            shouldAnimateMessageLifecycleEntrance(
                message = sending,
                isKnown = false,
                isLoading = true,
                isStreaming = true,
                lastUserMessageId = user.id,
                requestedTargetMessageId = user.id,
            )
        )
        assertFalse(
            shouldAnimateMessageLifecycleEntrance(
                message = sending,
                isKnown = true,
                isLoading = true,
                isStreaming = true,
                lastUserMessageId = user.id,
                requestedTargetMessageId = user.id,
            )
        )
        assertFalse(
            shouldAnimateMessageLifecycleEntrance(
                message = message("historical", Participant.MODEL),
                isKnown = false,
                isLoading = false,
                isStreaming = false,
                lastUserMessageId = user.id,
                requestedTargetMessageId = null,
            )
        )
    }

    @Test
    fun fastTerminalReplyCanStillUseTheSendTargetEntrance() {
        val completed = message("assistant", Participant.MODEL).copy(
            parentId = "user",
            status = MessageStatus.SUCCESS,
        )

        assertTrue(
            shouldAnimateMessageLifecycleEntrance(
                message = completed,
                isKnown = false,
                isLoading = false,
                isStreaming = false,
                lastUserMessageId = "user",
                requestedTargetMessageId = "user",
            )
        )
    }

    @Test
    fun acceptedSendTargetAnimatesBeforeLoadingStateIsObserved() {
        val user = message("user", Participant.USER)

        assertTrue(
            shouldAnimateMessageLifecycleEntrance(
                message = user,
                isKnown = false,
                isLoading = false,
                isStreaming = false,
                lastUserMessageId = user.id,
                requestedTargetMessageId = user.id,
            ),
        )
    }

    @Test
    fun protocolRowsNeverReceiveMessageEntranceAnimations() {
        val tool = message("tool_call", Participant.MODEL).copy(
            status = MessageStatus.TOOL_CALLING,
        )

        assertFalse(
            shouldAnimateMessageLifecycleEntrance(
                message = tool,
                isKnown = false,
                isLoading = true,
                isStreaming = true,
                lastUserMessageId = "user",
                requestedTargetMessageId = "user",
            )
        )
    }

    @Test
    fun assistantActionRowFadesForStreamingAndRegenerateOnly() {
        assertFalse(
            assistantActionsVisible(
                isStreaming = true,
                regenerateRequested = false,
            )
        )
        assertTrue(
            assistantActionsVisible(
                isStreaming = false,
                regenerateRequested = false,
            )
        )
        assertFalse(
            assistantActionsVisible(
                isStreaming = false,
                regenerateRequested = true,
            )
        )
    }

    @Test
    fun currentStreamingActionsHideWhileCompletedMessageInfoStaysEnabled() {
        val currentStreaming = assistantActionAvailability(
            isStreaming = true,
            isLoading = true,
        )
        assertFalse(currentStreaming.informationVisible)
        assertFalse(currentStreaming.informationEnabled)
        assertFalse(currentStreaming.terminalVisible)
        assertFalse(currentStreaming.terminalEnabled)

        val previousCompletedDuringGeneration = assistantActionAvailability(
            isStreaming = false,
            isLoading = true,
        )
        assertTrue(previousCompletedDuringGeneration.informationVisible)
        assertTrue(previousCompletedDuringGeneration.informationEnabled)
        assertTrue(previousCompletedDuringGeneration.terminalVisible)
        assertFalse(previousCompletedDuringGeneration.terminalEnabled)

        val completeAndIdle = assistantActionAvailability(
            isStreaming = false,
            isLoading = false,
        )
        assertTrue(completeAndIdle.informationVisible)
        assertTrue(completeAndIdle.informationEnabled)
        assertTrue(completeAndIdle.terminalVisible)
        assertTrue(completeAndIdle.terminalEnabled)

        val regenerating = assistantActionAvailability(
            isStreaming = false,
            isLoading = true,
            regenerateRequested = true,
        )
        assertFalse(regenerating.informationVisible)
        assertFalse(regenerating.informationEnabled)
        assertFalse(regenerating.terminalVisible)
        assertFalse(regenerating.terminalEnabled)
    }

    @Test
    fun editTargetReusesTheSourceVisualKeyWithoutTextMatching() {
        assertEquals(
            "source",
            branchReplacementVisualKey(
                messageId = "replacement",
                sourceUserMessageId = "source",
                targetUserMessageId = "replacement",
                aliases = emptyMap(),
            ),
        )
        assertEquals(
            "unrelated",
            branchReplacementVisualKey(
                messageId = "unrelated",
                sourceUserMessageId = "source",
                targetUserMessageId = "replacement",
                aliases = emptyMap(),
            ),
        )
    }

    @Test
    fun chainedEditKeepsTheOriginalVisualKey() {
        assertEquals(
            "source",
            branchReplacementVisualKey(
                messageId = "second-replacement",
                sourceUserMessageId = "first-replacement",
                targetUserMessageId = "second-replacement",
                aliases = mapOf("first-replacement" to "source"),
            ),
        )
    }

    @Test
    fun regenerationExitIncludesEveryVisibleElementAfterTheOldAnswer() {
        val messages = listOf(
            message("user-1", Participant.USER),
            message("answer-1", Participant.MODEL),
            message("user-2", Participant.USER),
            message("answer-2", Participant.MODEL),
        )

        assertEquals(
            linkedSetOf("answer-1", "user-2", "answer-2"),
            branchReplacementExitMessageIds(messages, oldMessageId = "answer-1"),
        )
    }

    @Test
    fun regenerationKeepsFadedComponentsAfterTheNewSendingMessage() {
        val user = message("user-1", Participant.USER)
        val oldAnswer = message("answer-old", Participant.MODEL)
        val downstreamUser = message("user-2", Participant.USER)
        val downstreamAnswer = message("answer-2", Participant.MODEL)
        val oldPath = listOf(user, oldAnswer, downstreamUser, downstreamAnswer)
        val retained = branchReplacementExitMessages(oldPath, oldAnswer.id)
        val sending = message("answer-new", Participant.MODEL).copy(
            status = MessageStatus.SENDING,
        )

        assertEquals(
            listOf("user-1", "answer-new", "answer-old", "user-2", "answer-2"),
            mergeBranchReplacementPresentationMessages(
                activeMessages = listOf(user, sending),
                retainedExitMessages = retained,
            ).map { message -> message.id },
        )
        assertEquals(
            oldPath,
            mergeBranchReplacementPresentationMessages(
                activeMessages = oldPath,
                retainedExitMessages = retained,
            ),
        )
    }

    private fun message(id: String, participant: Participant) = ChatMessage(
        id = id,
        text = id,
        participant = participant,
    )
}
