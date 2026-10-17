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
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ConversationEditServiceTest {
    @Test
    fun rejectsAssistantBeforeClaimingRuntimeOrReadingRoom() = runBlocking {
        val fixture = Fixture()
        val state = ConversationGenerationState("conversation", reclaimQueuedAttachments = {})
        val result = fixture.service.edit(
            fixture.request.copy(
                messageId = "assistant",
                visiblePath = listOf(
                    SOURCE_MESSAGE,
                    SOURCE_MESSAGE.copy(
                        id = "assistant",
                        parentId = SOURCE_MESSAGE.id,
                        participant = Participant.MODEL,
                        runSequence = 1,
                    ),
                ),
            ),
            state,
        )

        assertFalse(result)
        assertFalse(state.generating.value)
        coVerify(exactly = 0) { fixture.conversations.getMessage(any()) }
        coVerify(exactly = 0) {
            fixture.boundLauncher.launch(any(), any())
        }
        state.dispose()
        Unit
    }

    @Test
    fun commitsEditedGraphBeforeProjectionSettlementAndBoundLaunch() = runBlocking {
        val fixture = Fixture()
        val state = ConversationGenerationState("conversation", reclaimQueuedAttachments = {})
        coEvery { fixture.conversations.getMessage("source-input") } returns SOURCE_ENTITY
        coEvery { fixture.conversations.getMessage("previous") } returns null
        coEvery { fixture.conversations.getRun("source-run") } returns SOURCE_RUN
        coEvery {
            fixture.inputCloner.clone(
                sourceInputs = listOf(SOURCE_ENTITY),
                destinationRunId = "new-run",
                textOverrides = mapOf("source-input" to "edited text"),
            )
        } returns listOf(EDITED_ENTITY)
        val createdRun = slot<RunEntity>()
        val createdMessages = slot<List<MessageEntity>>()
        coEvery {
            fixture.conversations.createRunWithMessages(
                run = capture(createdRun),
                messages = capture(createdMessages),
                messageSelectionUpdates = EXPECTED_SELECTIONS,
                conversationModelId = "provider:model",
                at = any(),
                touchConversationOnAdmission = true,
            )
        } returns RunGraphCommit(
            messages = listOf(EDITED_ENTITY, MODEL_ENTITY),
            messageSelections = EXPECTED_SELECTIONS,
            runSelections = emptyMap(),
        )
        coEvery { fixture.boundLauncher.launch(any(), state) } just Runs

        val result = async(start = CoroutineStart.UNDISPATCHED) {
            fixture.service.edit(fixture.request, state)
        }
        val transition = checkNotNull(fixture.transitions.request.value)
        assertEquals("source-assistant", transition.oldMessageId)
        assertEquals("source-input", transition.sourceUserMessageId)
        coVerify(exactly = 0) { fixture.conversations.createRunWithMessages(any(), any(), any(), any(), any(), any()) }

        fixture.transitions.acknowledgeFade(transition.id)
        assertTrue(result.await())
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
        assertEquals("source-parent-run", createdRun.captured.parentRunId)
        assertEquals(RunStatus.ACTIVE, createdRun.captured.status)
        assertEquals(listOf("new-user", "new-model"), createdMessages.captured.map { it.id })
        assertEquals(
            listOf(
                "indexed:new-user:edited text",
                "project:new-user,new-model",
                "await:new-user",
            ),
            fixture.events,
        )
        assertEquals("new-model", state.streamingMessage.value?.id)
        assertEquals(
            BranchReplacementTransitionStage.COMMITTED,
            fixture.transitions.request.value?.stage,
        )
        assertEquals("new-user", fixture.transitions.request.value?.targetUserMessageId)
        fixture.transitions.complete(transition.id)
        state.dispose()
        Unit
    }

    @Test
    fun fadeTimeoutAbortsTransitionWithoutPersisting() = runBlocking {
        val fixture = Fixture(fadeTimeoutMs = 0L)
        val state = ConversationGenerationState("conversation", reclaimQueuedAttachments = {})

        val result = withTimeout(5_000L) {
            fixture.service.edit(fixture.request, state)
        }

        assertFalse(result)
        assertNull(fixture.transitions.request.value)
        coVerify(exactly = 0) { fixture.conversations.getMessage(any()) }
        state.dispose()
        Unit
    }

    @Test
    fun abortWhileWaitingForConversationLockDoesNotPersist() = runBlocking {
        val fixture = Fixture()
        val state = ConversationGenerationState("conversation", reclaimQueuedAttachments = {})
        val lockHeld = CompletableDeferred<Unit>()
        val releaseLock = CompletableDeferred<Unit>()
        val lockHolder = async(start = CoroutineStart.UNDISPATCHED) {
            fixture.executionCoordinator.withConversationLock("conversation") {
                lockHeld.complete(Unit)
                releaseLock.await()
            }
        }
        lockHeld.await()

        val result = async(start = CoroutineStart.UNDISPATCHED) {
            fixture.service.edit(fixture.request, state)
        }
        val transition = checkNotNull(fixture.transitions.request.value)
        fixture.transitions.acknowledgeFade(transition.id)
        withTimeout(5_000L) {
            while (!state.isLatestPersist(1L)) yield()
        }

        fixture.transitions.abort(transition.id)
        releaseLock.complete(Unit)

        assertFalse(result.await())
        lockHolder.await()
        assertNull(fixture.transitions.request.value)
        coVerify(exactly = 0) { fixture.conversations.getMessage(any()) }
        state.dispose()
        Unit
    }

    private class Fixture(
        fadeTimeoutMs: Long = 5_000L,
    ) {
        val conversations = mockk<ConversationRepository>()
        val requestBuilder = mockk<GenerationRequestBuilder>()
        val executionCoordinator = ConversationExecutionCoordinator()
        val inputCloner = mockk<EditedRunInputCloner>()
        val terminalSettlement = mockk<GenerationTerminalSettlementController>()
        val boundLauncher = mockk<BoundRunGenerationLauncher>()
        val transitions = BranchReplacementTransitionCoordinator(fadeTimeoutMs = fadeTimeoutMs)
        val guidanceDrain = mockk<QueuedGuidanceDrainExecutor>(relaxed = true)
        val events = mutableListOf<String>()
        val snapshot = testGenerationAdmissionSnapshot(
            conversationId = "conversation",
            runId = "new-run",
        )
        private val ids = ArrayDeque(listOf("new-run", "new-model"))
        val service = ConversationEditService(
            conversations = conversations,
            requestBuilder = requestBuilder,
            executionCoordinator = executionCoordinator,
            inputCloner = inputCloner,
            terminalSettlement = terminalSettlement,
            boundRunGenerationLauncher = boundLauncher,
            guidanceDrain = guidanceDrain,
            toUiMessage = ::toUiMessage,
            isConversationOpen = { true },
            projectGraph = { _, messages, _, _ ->
                events += "project:${messages.joinToString(",") { it.id }}"
            },
            onUserMessagePersisted = { messageId, text ->
                events += "indexed:$messageId:$text"
            },
            idFactory = ids::removeFirst,
        )

        init {
            coEvery {
                requestBuilder.captureAdmissionSnapshot(
                    any(), any(), any(), any(), any(),
                )
            } returns snapshot
        }
        val request = ConversationEditRequest(
            conversationId = "conversation",
            messageId = "source-input",
            newText = "edited text",
            modelId = "provider:model",
            visiblePath = listOf(SOURCE_MESSAGE, SOURCE_ASSISTANT),
            origin = FakeChatClient(open = "conversation", branchTransitions = transitions).apply {
                onAwaitProjectedPath = { _, messageId -> events += "await:$messageId" }
            },
        )
    }

    private companion object {
        val SOURCE_MESSAGE = ChatMessage(
            id = "source-input",
            parentId = "previous",
            text = "original",
            participant = Participant.USER,
            status = MessageStatus.SUCCESS,
            timestamp = 1L,
            runId = "source-run",
            runSequence = 0,
        )
        val SOURCE_ASSISTANT = ChatMessage(
            id = "source-assistant",
            parentId = SOURCE_MESSAGE.id,
            text = "answer",
            participant = Participant.MODEL,
            status = MessageStatus.SUCCESS,
            timestamp = 2L,
            runId = "source-run",
            runSequence = 1,
        )
        val SOURCE_ENTITY = MessageEntity(
            id = "source-input",
            conversationId = "conversation",
            parentId = "previous",
            text = "original",
            participant = Participant.USER,
            status = MessageStatus.SUCCESS,
            timestamp = 1L,
            runId = "source-run",
            runSequence = 0,
            consumedAtPass = 0,
        )
        val EDITED_ENTITY = SOURCE_ENTITY.copy(
            id = "new-user",
            text = "edited text",
            timestamp = 10L,
            runId = "new-run",
        )
        val MODEL_ENTITY = MessageEntity(
            id = "new-model",
            conversationId = "conversation",
            parentId = "new-user",
            text = "",
            participant = Participant.MODEL,
            status = MessageStatus.SENDING,
            timestamp = 11L,
            modelName = "provider:model",
            runId = "new-run",
            runSequence = 1,
        )
        val SOURCE_RUN = RunEntity(
            id = "source-run",
            conversationId = "conversation",
            parentRunId = "source-parent-run",
            status = RunStatus.COMPLETED,
            activeSlot = null,
            startedAt = 1L,
            lastCheckpointAt = 2L,
            endedAt = 2L,
            endReason = RunEndReason.MODEL_COMPLETED,
        )
        val EXPECTED_SELECTIONS: Map<String?, String> = mapOf(
            "previous" to "new-user",
            "new-user" to "new-model",
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
