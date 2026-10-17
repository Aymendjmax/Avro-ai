package com.newoether.agora.viewmodel

import com.newoether.agora.data.local.MessageEntity
import com.newoether.agora.data.repository.ConversationRepository
import com.newoether.agora.model.ChatMessage
import com.newoether.agora.model.MessageStatus
import com.newoether.agora.model.Participant
import com.newoether.agora.model.RunEndReason
import com.newoether.agora.model.RunStatus
import com.newoether.agora.util.DebugLog
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.slot
import io.mockk.unmockkObject
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GenerationTerminalSettlementControllerTest {
    @Test
    fun boundFailureMarksInvisibleConversationUnreadAndNotifiesAfterCommit() = runBlocking {
        val conversations = mockk<ConversationRepository>()
        coEvery {
            conversations.finishGeneration(
                FAILED_MESSAGE,
                "conversation",
                "run",
                RunStatus.FAILED,
                RunEndReason.PROVIDER_ERROR,
                true,
                any(),
            )
        } returns true
        val state = ConversationGenerationState("conversation", reclaimQueuedAttachments = {})
        val token = requireNotNull(state.acquireForSend())
        state.bindRun(token, "run", pass = 2)
        val committed = mutableListOf<ChatMessage>()
        val notifications = mutableListOf<String>()
        state.onStreamCommit = { _, message -> committed += message }

        val success = controller(
            conversations = conversations,
            isConversationVisible = { false },
            onTerminalNotification = { text, _, _ -> notifications += text },
        ).finalizeBoundFailure(
            conversationId = "conversation",
            runId = "run",
            pass = 2,
            uiToken = token,
            state = state,
            failedMessage = FAILED_MESSAGE,
            effectId = "failure-effect",
            notificationText = "Error: bound detail",
        )

        assertTrue(success)
        assertEquals(listOf(FAILED_MESSAGE), committed)
        assertEquals(null, state.streamingMessage.value)
        assertEquals(listOf("Error: bound detail"), notifications)
        assertEquals(
            "failure-effect",
            state.runtimeTraceSnapshot().first { it.commandType == "FinalizationRequested" }
                .effectId,
        )
        assertTrue(state.endGeneration(token))
        state.dispose()
        Unit
    }

    @Test
    fun cancelledActiveRunSettlesThroughStopPersistence() = runBlocking {
        val conversations = mockk<ConversationRepository>()
        coEvery { conversations.requestRunStop("run", any()) } returns true
        coEvery { conversations.finishStoppedGeneration(any(), "run", any()) } returns true
        val state = ConversationGenerationState("conversation", reclaimQueuedAttachments = {})
        val token = requireNotNull(state.acquireForSend())
        state.bindRun(token, "run", pass = 1)
        state.streamUpdate(token, FAILED_MESSAGE.copy(status = MessageStatus.SENDING))

        val claimed = controller(conversations).settleCancelledDurableRun(
            state,
            ConversationGenerationState.RunBindingOutcome.Active,
        )

        assertTrue(claimed)
        coVerify(exactly = 1) { conversations.requestRunStop("run", any()) }
        coVerify(exactly = 1) { conversations.finishStoppedGeneration(any(), "run", any()) }
        assertFalse(state.generating.value)
        assertFalse(state.stopping.value)
        assertEquals(null, state.streamingMessage.value)
        state.dispose()
        Unit
    }

    @Test
    fun noWriterRepairNotifiesOnlyAfterAcceptedTerminalWrite() = runBlocking {
        val conversations = mockk<ConversationRepository>()
        coEvery { conversations.getMessage("model") } returns MESSAGE_ENTITY
        val persisted = slot<ChatMessage>()
        coEvery {
            conversations.finishGeneration(
                capture(persisted),
                "conversation",
                "run",
                RunStatus.FAILED,
                RunEndReason.PROVIDER_ERROR,
                true,
                any(),
            )
        } returns true
        val snackbars = mutableListOf<String>()
        val notifications = mutableListOf<String>()
        val state = ConversationGenerationState("conversation", reclaimQueuedAttachments = {})
        mockkObject(DebugLog)
        every { DebugLog.e(any(), any()) } returns Unit
        try {
            val controller = controller(
                conversations = conversations,
                onSnackbar = snackbars::add,
                isConversationVisible = { false },
                onTerminalNotification = { text, _, _ -> notifications += text },
            )
            controller.failGenerationSetup(
                conversationId = "conversation",
                runId = "run",
                modelMessageId = "model",
                uiToken = 1L,
                state = state,
                error = IllegalStateException("private detail"),
            )
            assertEquals(listOf("Error: private detail"), notifications)

            coEvery {
                conversations.finishGeneration(any(), any(), any(), any(), any(), any(), any())
            } returns false
            notifications.clear()
            val rejectedState = ConversationGenerationState("conversation", reclaimQueuedAttachments = {})
            controller.failGenerationSetup(
                conversationId = "conversation",
                runId = "run",
                modelMessageId = "model",
                uiToken = 2L,
                state = rejectedState,
                error = IllegalStateException("private detail"),
            )
            assertTrue(notifications.isEmpty())
            rejectedState.dispose()
        } finally {
            unmockkObject(DebugLog)
        }

        assertEquals(MessageStatus.ERROR, persisted.captured.status)
        assertEquals("Failed to generate", persisted.captured.text)
        assertEquals(listOf("Failed to generate", "Failed to generate"), snackbars)
        assertTrue(state.runtimeTraceSnapshot().isEmpty())
        state.dispose()
        Unit
    }

    private fun controller(
        conversations: ConversationRepository,
        onSnackbar: (String) -> Unit = {},
        isConversationVisible: ((String) -> Boolean)? = null,
        onTerminalNotification: (String, String, MessageStatus) -> Unit = { _, _, _ -> },
    ) = GenerationTerminalSettlementController(
        conversations = conversations,
        stopFinalizer = GenerationFinalizer(conversations) { _, _ -> },
        runFinalizationEffects = RunFinalizationEffectCoordinator(),
        failureText = { "Failed to generate" },
        toUiMessage = { entity ->
            ChatMessage(
                id = entity.id,
                parentId = entity.parentId,
                text = entity.text,
                participant = entity.participant,
                status = entity.status,
                runId = entity.runId,
                runSequence = entity.runSequence,
            )
        },
        onSnackbar = onSnackbar,
        isConversationVisible = isConversationVisible,
        onTerminalNotification = onTerminalNotification,
    )

    private companion object {
        val FAILED_MESSAGE = ChatMessage(
            id = "model",
            text = "failure",
            participant = Participant.MODEL,
            status = MessageStatus.ERROR,
            runId = "run",
            runSequence = 0,
        )
        val MESSAGE_ENTITY = MessageEntity(
            id = "model",
            conversationId = "conversation",
            text = "",
            participant = Participant.MODEL,
            status = MessageStatus.SENDING,
            timestamp = 1L,
            runId = "run",
            runSequence = 0,
        )
    }
}
