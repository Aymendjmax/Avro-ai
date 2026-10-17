package com.newoether.agora.viewmodel

import com.newoether.agora.automation.ConversationExecutionCoordinator
import com.newoether.agora.data.local.MessageEntity
import com.newoether.agora.data.local.RunEntity
import com.newoether.agora.data.local.RunGraphCommit
import com.newoether.agora.data.repository.ConversationRepository
import com.newoether.agora.model.ChatMessage
import com.newoether.agora.model.MessageStatus
import com.newoether.agora.model.Participant
import com.newoether.agora.model.RunEndReason
import com.newoether.agora.model.RunStatus
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.just
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConversationRegenerationServiceTest {
    @Test
    fun rejectsNonBoundaryOutputBeforeTransitionOrRuntimeClaim() = runBlocking {
        val fixture = Fixture()
        val state = ConversationGenerationState("conversation", reclaimQueuedAttachments = {})
        val result = fixture.service.regenerate(
            fixture.request.copy(
                visiblePath = listOf(
                    SOURCE_USER,
                    TARGET_MODEL,
                    TARGET_MODEL.copy(id = "later-model", runSequence = 2),
                ),
            ),
            state,
        )

        assertFalse(result)
        assertEquals(null, fixture.transitions.request.value)
        assertFalse(state.generating.value)
        state.dispose()
        Unit
    }

    @Test
    fun transitionConflictDelegatesExactUnlaunchedSlotRelease() = runBlocking {
        val fixture = Fixture()
        val state = ConversationGenerationState("conversation", reclaimQueuedAttachments = {})
        val existing = checkNotNull(
            fixture.transitions.begin("conversation", "other-model", "source-input"),
        )
        coEvery {
            fixture.guidanceDrain.releaseUnlaunchedSlotAndDrain(state, any())
        } just Runs

        assertFalse(fixture.service.regenerate(fixture.request, state))

        coVerify(timeout = 5_000, exactly = 1) {
            fixture.guidanceDrain.releaseUnlaunchedSlotAndDrain(state, any())
        }
        fixture.transitions.abort(existing.id)
        state.dispose()
        Unit
    }

    @Test
    fun fadeAdmissionCommitsReplacementRunBeforeProjectionAndBoundLaunch() = runBlocking {
        val fixture = Fixture()
        val state = ConversationGenerationState("conversation", reclaimQueuedAttachments = {})
        coEvery {
            fixture.conversations.getMessagesByIds(
                listOf("target-model", "target-model", "source-input"),
            )
        } returns listOf(SOURCE_USER_ENTITY, TARGET_MODEL_ENTITY)
        coEvery { fixture.conversations.getRun("source-run") } returns SOURCE_RUN
        val createdRun = slot<RunEntity>()
        val createdMessages = slot<List<MessageEntity>>()
        coEvery {
            fixture.conversations.createRunWithMessages(
                run = capture(createdRun),
                messages = capture(createdMessages),
                messageSelectionUpdates = mapOf("source-input" to "new-model"),
                conversationModelId = "provider:model",
                at = any(),
                touchConversationOnAdmission = true,
            )
        } answers {
            RunGraphCommit(
                messages = secondArg(),
                messageSelections = thirdArg(),
                runSelections = emptyMap(),
            )
        }
        coEvery { fixture.boundLauncher.launch(any(), state) } just Runs

        assertTrue(fixture.service.regenerate(fixture.request, state))
        val transition = checkNotNull(fixture.transitions.request.value)
        fixture.transitions.acknowledgeFade(transition.id)

        coVerify(timeout = 5_000, exactly = 1) {
            fixture.boundLauncher.launch(
                match {
                        it.conversationId == "conversation" &&
                        it.modelMessageId == "new-model" &&
                        it.snapshot === fixture.snapshot &&
                        it.runId == "new-run" &&
                        it.pass == 0 &&
                        it.requestKind == "chat"
                },
                state,
            )
        }
        assertEquals(BranchReplacementTransitionStage.COMMITTED, fixture.transitions.request.value?.stage)
        assertEquals("source-run", createdRun.captured.parentRunId)
        assertEquals(50L, createdRun.captured.startedAt)
        assertEquals(listOf("new-model"), createdMessages.captured.map { it.id })
        assertEquals("source-input", createdMessages.captured.single().parentId)
        assertEquals(listOf("project:new-model"), fixture.events)
        assertEquals("new-model", state.streamingMessage.value?.id)
        fixture.transitions.complete(transition.id)
        state.dispose()
        Unit
    }

    @Test
    fun adjacentAssistantRunRegeneratesOnlyItsOwnRootFromItsDirectParent() = runBlocking {
        val fixture = Fixture()
        val state = ConversationGenerationState("conversation", reclaimQueuedAttachments = {})
        val upper = TARGET_MODEL.copy(
            id = "upper-model",
            parentId = "source-input",
            runId = "upper-run",
            runSequence = 0,
        )
        val lower = TARGET_MODEL.copy(
            id = "lower-model",
            parentId = upper.id,
            runId = "lower-run",
            runSequence = 0,
        )
        val upperEntity = TARGET_MODEL_ENTITY.copy(
            id = upper.id,
            parentId = upper.parentId,
            runId = requireNotNull(upper.runId),
            runSequence = requireNotNull(upper.runSequence),
        )
        val lowerEntity = TARGET_MODEL_ENTITY.copy(
            id = lower.id,
            parentId = lower.parentId,
            runId = requireNotNull(lower.runId),
            runSequence = requireNotNull(lower.runSequence),
        )
        val createdRun = slot<RunEntity>()
        val createdMessages = slot<List<MessageEntity>>()
        coEvery {
            fixture.conversations.getMessagesByIds(
                listOf(lower.id, lower.id, upper.id),
            )
        } returns listOf(upperEntity, lowerEntity)
        coEvery {
            fixture.conversations.createRunWithMessages(
                run = capture(createdRun),
                messages = capture(createdMessages),
                messageSelectionUpdates = mapOf(upper.id to "new-model"),
                conversationModelId = "provider:model",
                at = any(),
                touchConversationOnAdmission = true,
            )
        } answers {
            RunGraphCommit(
                messages = secondArg(),
                messageSelections = thirdArg(),
                runSelections = emptyMap(),
            )
        }
        coEvery { fixture.boundLauncher.launch(any(), state) } just Runs

        assertTrue(
            fixture.service.regenerate(
                fixture.request.copy(
                    messageId = lower.id,
                    visiblePath = listOf(SOURCE_USER, upper, lower),
                ),
                state,
            ),
        )
        val transition = checkNotNull(fixture.transitions.request.value)
        fixture.transitions.acknowledgeFade(transition.id)

        coVerify(timeout = 5_000, exactly = 1) {
            fixture.boundLauncher.launch(
                match {
                    it.modelMessageId == "new-model" &&
                        it.requestKind == "chat"
                },
                state,
            )
        }
        assertEquals(upper.id, createdMessages.captured.single().parentId)
        assertEquals(upper.runId, createdRun.captured.parentRunId)
        assertEquals(lowerEntity, listOf(SOURCE_USER_ENTITY, upperEntity, lowerEntity).last())
        fixture.transitions.complete(transition.id)
        state.dispose()
        Unit
    }

    private class Fixture {
        val conversations = mockk<ConversationRepository>()
        val requestBuilder = mockk<GenerationRequestBuilder>()
        val transitions = BranchReplacementTransitionCoordinator(fadeTimeoutMs = 5_000L)
        val terminalSettlement = mockk<GenerationTerminalSettlementController>()
        val boundLauncher = mockk<BoundRunGenerationLauncher>()
        val guidanceDrain = mockk<QueuedGuidanceDrainExecutor>()
        val events = mutableListOf<String>()
        val snapshot = testGenerationAdmissionSnapshot(
            conversationId = "conversation",
            runId = "new-run",
        )
        private val ids = ArrayDeque(listOf("new-run", "new-model"))
        val service = ConversationRegenerationService(
            conversations = conversations,
            requestBuilder = requestBuilder,
            executionCoordinator = ConversationExecutionCoordinator(),
            terminalSettlement = terminalSettlement,
            boundRunGenerationLauncher = boundLauncher,
            guidanceDrain = guidanceDrain,
            toUiMessage = ::toUiMessage,
            isConversationOpen = { true },
            projectGraph = { _, messages, _, _ ->
                events += "project:${messages.joinToString(",") { it.id }}"
            },
            idFactory = ids::removeFirst,
            clock = { 50L },
        )

        init {
            coEvery {
                requestBuilder.captureAdmissionSnapshot(
                    any(), any(), any(), any(), any(),
                )
            } returns snapshot
        }
        val request = ConversationRegenerationRequest(
            conversationId = "conversation",
            messageId = "target-model",
            modelId = "provider:model",
            visiblePath = listOf(SOURCE_USER, TARGET_MODEL),
            origin = FakeChatClient(open = "conversation", branchTransitions = transitions),
        )
    }

    private companion object {
        val SOURCE_USER = ChatMessage(
            id = "source-input",
            parentId = "previous-model",
            text = "input",
            participant = Participant.USER,
            status = MessageStatus.SUCCESS,
            timestamp = 1L,
            runId = "source-run",
            runSequence = 0,
        )
        val TARGET_MODEL = ChatMessage(
            id = "target-model",
            parentId = "source-input",
            text = "answer",
            participant = Participant.MODEL,
            status = MessageStatus.SUCCESS,
            timestamp = 2L,
            runId = "source-run",
            runSequence = 1,
        )
        val SOURCE_USER_ENTITY = MessageEntity(
            id = "source-input",
            conversationId = "conversation",
            parentId = "previous-model",
            text = "input",
            participant = Participant.USER,
            status = MessageStatus.SUCCESS,
            timestamp = 1L,
            runId = "source-run",
            runSequence = 0,
            consumedAtPass = 0,
        )
        val TARGET_MODEL_ENTITY = MessageEntity(
            id = "target-model",
            conversationId = "conversation",
            parentId = "source-input",
            text = "answer",
            participant = Participant.MODEL,
            status = MessageStatus.SUCCESS,
            timestamp = 2L,
            runId = "source-run",
            runSequence = 1,
        )
        val SOURCE_RUN = RunEntity(
            id = "source-run",
            conversationId = "conversation",
            parentRunId = "parent-run",
            status = RunStatus.COMPLETED,
            activeSlot = null,
            startedAt = 1L,
            lastCheckpointAt = 2L,
            endedAt = 2L,
            endReason = RunEndReason.MODEL_COMPLETED,
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
