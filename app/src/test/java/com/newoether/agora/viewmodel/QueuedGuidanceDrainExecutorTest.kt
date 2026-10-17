package com.newoether.agora.viewmodel

import com.newoether.agora.automation.ConversationExecutionCoordinator
import com.newoether.agora.data.local.MessageEntity
import com.newoether.agora.data.local.ProviderContextTopologySnapshot
import com.newoether.agora.data.local.RunEntity
import com.newoether.agora.data.local.RunGraphCommit
import com.newoether.agora.data.repository.ConversationRepository
import com.newoether.agora.data.repository.SettingsRepository
import com.newoether.agora.model.ChatMessage
import com.newoether.agora.model.MessageStatus
import com.newoether.agora.model.Participant
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class QueuedGuidanceDrainExecutorTest {
    @Test
    fun claimUsesFreshRunIdentityAndFailedLeaseReturnsToFifoFront() = runBlocking {
        val fixture = Fixture()
        val state = ConversationGenerationState("conversation", reclaimQueuedAttachments = {})
        QUEUED.forEach(state::enqueueSend)
        val lease = checkNotNull(state.claimQueuedSends())

        val claim = fixture.executor.claimUnderLock(state, lease)

        assertNotNull(claim)
        assertEquals("fresh-run", claim?.inputEffect?.identity?.runId)
        assertEquals("guidance-fresh-run", claim?.inputEffect?.identity?.effectId)
        assertTrue(state.queuedSends.value.isEmpty())
        assertTrue(state.settleGuidanceClaim(lease.id, durable = false))
        assertEquals(listOf("guidance-1", "guidance-2"), state.queuedSends.value.map { it.id })
        state.dispose()
        Unit
    }

    @Test
    fun launchCommitsWholeFifoAsOneBubbleBeforeCompactAndBoundGeneration() = runBlocking {
        val fixture = Fixture()
        val state = ConversationGenerationState("conversation", reclaimQueuedAttachments = {})
        val frozenSnapshot = fixture.snapshot.copy(runId = "old-run")
        val queued = QUEUED.mapIndexed { index, send ->
            if (index == QUEUED.lastIndex) send.copy(generationSnapshot = frozenSnapshot) else send
        }
        queued.forEach(state::enqueueSend)
        val lease = checkNotNull(state.claimQueuedSends())
        val claim = checkNotNull(fixture.executor.claimUnderLock(state, lease))
        every {
            fixture.requestBuilder.resolveProviderKey("provider:model-2", any())
        } returns GenerationRequestBuilder.ProviderKey("provider", "active-key")
        coEvery {
            fixture.conversations.getProviderContextTopologySnapshot("conversation")
        } returns ProviderContextTopologySnapshot(null, emptyList())
        val createdRun = slot<RunEntity>()
        val createdMessages = slot<List<MessageEntity>>()
        val commitAt = slot<Long>()
        val touchPolicy = slot<Boolean>()
        coEvery {
            fixture.conversations.createRunWithMessages(
                run = capture(createdRun),
                messages = capture(createdMessages),
                messageSelectionUpdates = any(),
                conversationModelId = "provider:model-2",
                at = capture(commitAt),
                touchConversationOnAdmission = capture(touchPolicy),
            )
        } answers {
            val messages = secondArg<List<MessageEntity>>()
            val selections = thirdArg<Map<String?, String>>()
            RunGraphCommit(messages, selections, emptyMap())
        }
        coEvery { fixture.settings.incrementMessagesSent() } just Runs
        coEvery { fixture.boundLauncher.launch(any(), state) } just Runs

        fixture.executor.launchClaim(state, claim)

        coVerify(exactly = 0) {
            fixture.requestBuilder.captureAdmissionSnapshot(any(), any(), any(), any(), any())
        }
        coVerify(timeout = 5_000, exactly = 1) {
            fixture.boundLauncher.launch(
                match {
                        it.conversationId == "conversation" &&
                        it.modelMessageId == "model-message" &&
                        it.snapshot == fixture.snapshot &&
                        it.snapshot.config === fixture.snapshot.config &&
                        it.runId == "fresh-run" &&
                        it.pass == 0 &&
                        it.requestKind == "queued_guidance"
                },
                state,
            )
        }
        assertEquals("fresh-run", createdRun.captured.id)
        assertEquals(null, createdRun.captured.parentRunId)
        assertEquals(2, createdMessages.captured.size)
        assertEquals("guidance-1", createdMessages.captured[0].id)
        assertEquals("one\n\ntwo", createdMessages.captured[0].text)
        assertEquals("model-message", createdMessages.captured[1].id)
        assertEquals(100L, commitAt.captured)
        assertTrue(touchPolicy.captured)
        assertTrue(queued.all { it.createdAt != commitAt.captured })
        assertEquals(listOf("indexed:guidance-1:one\n\ntwo", "scroll:guidance-1"), fixture.events)
        assertFalse(state.settleGuidanceClaim(lease.id, durable = false))
        state.dispose()
        Unit
    }

    private class Fixture {
        val conversations = mockk<ConversationRepository>()
        val settings = mockk<SettingsRepository>()
        val requestBuilder = mockk<GenerationRequestBuilder>()
        val terminalSettlement = mockk<GenerationTerminalSettlementController>()
        val boundLauncher = mockk<BoundRunGenerationLauncher>()
        val events = mutableListOf<String>()
        val snapshot = testGenerationAdmissionSnapshot(
            conversationId = "conversation",
            runId = "fresh-run",
            selectedModelId = "provider:model-2",
        )
        private val ids = ArrayDeque(listOf("fresh-run", "model-message"))
        val executor = QueuedGuidanceDrainExecutor(
            conversations = conversations,
            settings = settings,
            requestBuilder = requestBuilder,
            executionCoordinator = ConversationExecutionCoordinator(),
            terminalSettlement = terminalSettlement,
            boundRunGenerationLauncher = boundLauncher,
            toUiMessage = ::toUiMessage,
            isConversationOpen = { true },
            projectGraph = { _, _, _, _ -> Unit },
            onScrollToAbsoluteBottomAfter = { _, messageId ->
                events += "scroll:$messageId"
            },
            onUserMessagePersisted = { messageId, text ->
                events += "indexed:$messageId:$text"
            },
            idFactory = ids::removeFirst,
            clock = { 100L },
        )
    }

    private companion object {
        val QUEUED = listOf(
            QueuedSend(
                id = "guidance-1",
                text = "one",
                modelId = "provider:model-1",
                attachments = emptyList(),
                runId = "old-run",
                createdAt = 10L,
            ),
            QueuedSend(
                id = "guidance-2",
                text = "two",
                modelId = "provider:model-2",
                attachments = emptyList(),
                runId = "old-run",
                createdAt = 20L,
            ),
        )

        fun toUiMessage(entity: MessageEntity) = ChatMessage(
            id = entity.id,
            parentId = entity.parentId,
            text = entity.text,
            participant = entity.participant,
            status = entity.status,
            timestamp = entity.timestamp,
            modelName = entity.modelName,
            runId = entity.runId,
            runSequence = entity.runSequence,
        )
    }
}
