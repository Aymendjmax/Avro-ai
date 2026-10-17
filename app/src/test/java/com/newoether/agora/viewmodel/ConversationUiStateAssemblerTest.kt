package com.newoether.agora.viewmodel

import com.newoether.agora.automation.ConversationExecutionCoordinator
import com.newoether.agora.data.local.ChatEntity
import com.newoether.agora.data.local.MessageContextTopology
import com.newoether.agora.data.repository.ConversationRepository
import com.newoether.agora.model.ChatMessage
import com.newoether.agora.model.MessageSegment
import com.newoether.agora.model.MessageStatus
import com.newoether.agora.model.Participant
import com.newoether.agora.util.DebugLog
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.coVerifyOrder
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import java.util.ArrayDeque
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ConversationUiStateAssemblerTest {
    @Test
    fun `streaming deltas and terminal handoff bypass blocked structural projection`() = runTest {
        val pendingProjectionTasks = ArrayDeque<Runnable>()
        var projectionBlocked = false
        val projectionDelegate = StandardTestDispatcher(testScheduler)
        val projectionDispatcher = object : CoroutineDispatcher() {
            override fun dispatch(context: CoroutineContext, block: Runnable) {
                if (projectionBlocked) {
                    pendingProjectionTasks.addLast(block)
                } else {
                    projectionDelegate.dispatch(context, block)
                }
            }
        }
        val root = ChatMessage(
            id = "root",
            text = "request",
            participant = Participant.USER,
            timestamp = 1L,
        )
        val activeStub = ChatMessage(
            id = "active",
            parentId = root.id,
            text = "",
            participant = Participant.MODEL,
            status = MessageStatus.SENDING,
            timestamp = 2L,
            runId = "run",
        )
        var latestStreaming = activeStub.copy(text = "partial")
        val assembler = ConversationUiStateAssembler(
            conversations = mockk(),
            registry = mockk(),
            executionCoordinator = mockk(),
            currentConversationId = MutableStateFlow(CONVERSATION_ID),
            scope = backgroundScope,
            projectionDispatcher = projectionDispatcher,
        )
        assembler.renderStore.replaceConversation(
            allMessages = listOf(root, activeStub),
            streamingMessage = latestStreaming,
            selectedChildren = emptyMap(),
        )
        runCurrent()
        assertEquals(latestStreaming, assembler.messages.value.last())

        projectionBlocked = true
        assembler.renderStore.setSelectedChildren(
            mapOf<String?, String>(null to root.id, root.id to activeStub.id),
        )
        runCurrent()
        assertTrue(pendingProjectionTasks.isNotEmpty())

        repeat(6) { zeroBasedIndex ->
            val callCount = zeroBasedIndex + 1
            latestStreaming = activeStub.copy(
                text = "partial",
                status = MessageStatus.TOOL_CALLING,
                segments = (1..callCount).map { callIndex ->
                    MessageSegment(
                        type = "tool",
                        toolCallId = "call-$callIndex",
                        toolArgs = "",
                    )
                },
            )
            assembler.renderStore.setStreamingMessage(latestStreaming)
            runCurrent()

            assertEquals(
                (1..callCount).map { callIndex -> "call-$callIndex" },
                assembler.messages.value.last().segments.orEmpty().map(MessageSegment::toolCallId),
            )
        }

        val stopped = latestStreaming.copy(
            status = MessageStatus.STOPPED,
            segments = latestStreaming.segments.orEmpty() +
                MessageSegment(type = "answer", content = "terminal payload"),
        )
        assembler.commitTerminalStreamingMessage(CONVERSATION_ID, stopped)
        runCurrent()

        assertEquals(listOf(root.id, stopped.id), assembler.messages.value.map(ChatMessage::id))
        assertEquals(stopped, assembler.messages.value.last())
        assertEquals("terminal payload", assembler.messages.value.last().segments.orEmpty().last().content)

        projectionBlocked = false
        while (pendingProjectionTasks.isNotEmpty()) {
            pendingProjectionTasks.removeFirst().run()
        }
        runCurrent()
        assertEquals(stopped, assembler.messages.value.last())
    }

    @Test
    fun `room graph becomes ready atomically before runtime overlay is published`() = runTest {
        val conversations = mockk<ConversationRepository>()
        val registry = mockk<ConversationStateRegistry>()
        val executionCoordinator = ConversationExecutionCoordinator()
        val state = generationState(isLoading = true, generating = false)
        val roomMessages = MutableSharedFlow<List<MessageContextTopology>>(replay = 1)
        coEvery { conversations.recoverConversationRuntime(CONVERSATION_ID, any()) } returns 0
        coEvery { conversations.getConversation(CONVERSATION_ID) } returns ChatEntity(
            id = CONVERSATION_ID,
            title = "Conversation",
            selectedBranchesJson = "{\"null\":\"root\"}",
        )
        every { conversations.observeMessageTopology(CONVERSATION_ID) } returns
            roomMessages
        every { registry.getOrCreate(CONVERSATION_ID) } returns state
        val currentConversationId = MutableStateFlow<String?>(null)
        val assembler = ConversationUiStateAssembler(
            conversations = conversations,
            registry = registry,
            executionCoordinator = executionCoordinator,
            currentConversationId = currentConversationId,
            scope = backgroundScope,
            projectionDispatcher = StandardTestDispatcher(testScheduler),
        )
        assembler.start()

        currentConversationId.value = CONVERSATION_ID
        runCurrent()
        assertNull(assembler.loadedMessagesConversationId.value)
        assertFalse(assembler.isLoading.value)

        roomMessages.emit(emptyList())
        runCurrent()

        assertEquals(CONVERSATION_ID, assembler.loadedMessagesConversationId.value)
        assertEquals(mapOf<String?, String>(null to "root"), assembler.renderStore.selectedChildren)
        assertTrue(assembler.isLoading.value)
        assertNull(assembler.generatingInConversationId.value)
        coVerifyOrder {
            conversations.recoverConversationRuntime(CONVERSATION_ID, any())
            conversations.getConversation(CONVERSATION_ID)
        }

        currentConversationId.value = null
        runCurrent()
        assertNull(assembler.loadedMessagesConversationId.value)
        assertFalse(assembler.isLoading.value)
        assertNull(assembler.generatingInConversationId.value)
        assertEquals(emptyList<ChatMessage>(), assembler.renderStore.allMessages)
    }

    @Test
    fun `active automation prevents foreground recovery from stopping its live row`() = runTest {
        val conversations = mockk<ConversationRepository>()
        val registry = mockk<ConversationStateRegistry>()
        val executionCoordinator = ConversationExecutionCoordinator()
        val state = generationState(isLoading = false, generating = false)
        coEvery { conversations.getConversation(CONVERSATION_ID) } returns ChatEntity(
            id = CONVERSATION_ID,
            title = "Conversation",
        )
        every { conversations.observeMessageTopology(CONVERSATION_ID) } returns
            MutableStateFlow(emptyList())
        every { registry.getOrCreate(CONVERSATION_ID) } returns state
        val ownerEntered = CompletableDeferred<Unit>()
        val releaseOwner = CompletableDeferred<Unit>()
        val ownerJob = launch {
            executionCoordinator.withAutomationConversationLock(CONVERSATION_ID) {
                ownerEntered.complete(Unit)
                releaseOwner.await()
            }
        }
        ownerEntered.await()
        val assembler = ConversationUiStateAssembler(
            conversations = conversations,
            registry = registry,
            executionCoordinator = executionCoordinator,
            currentConversationId = MutableStateFlow(CONVERSATION_ID),
            scope = backgroundScope,
            projectionDispatcher = StandardTestDispatcher(testScheduler),
        )

        assembler.start()
        runCurrent()

        assertEquals(CONVERSATION_ID, assembler.loadedMessagesConversationId.value)
        coVerify(exactly = 0) { conversations.recoverConversationRuntime(any(), any()) }

        releaseOwner.complete(Unit)
        ownerJob.join()
    }

    @Test
    fun `late callbacks for another conversation cannot change open projection state`() = runTest {
        val currentConversationId = MutableStateFlow<String?>(CONVERSATION_ID)
        val assembler = ConversationUiStateAssembler(
            conversations = mockk(),
            registry = mockk(),
            executionCoordinator = mockk(),
            currentConversationId = currentConversationId,
            scope = backgroundScope,
            projectionDispatcher = StandardTestDispatcher(testScheduler),
        )

        assembler.markActive("other")
        assertFalse(assembler.isLoading.value)
        assertNull(assembler.generatingInConversationId.value)

        assembler.markActive(CONVERSATION_ID)
        assembler.markIdle("other")
        assertTrue(assembler.isLoading.value)
        assertEquals(CONVERSATION_ID, assembler.generatingInConversationId.value)
    }

    @Test
    fun `current projection failure is reported without replacing the selection`() = runTest {
        mockkObject(DebugLog)
        every { DebugLog.e(any(), any(), any()) } returns Unit
        try {
            val conversations = mockk<ConversationRepository>()
            val executionCoordinator = ConversationExecutionCoordinator()
            coEvery { conversations.recoverConversationRuntime(CONVERSATION_ID, any()) } throws
                IllegalStateException("failed")
            val failedIds = mutableListOf<String>()
            val assembler = ConversationUiStateAssembler(
                conversations = conversations,
                registry = mockk(),
                executionCoordinator = executionCoordinator,
                currentConversationId = MutableStateFlow(CONVERSATION_ID),
                scope = backgroundScope,
                projectionDispatcher = StandardTestDispatcher(testScheduler),
                onConversationLoadFailed = failedIds::add,
            )
            assembler.renderStore.replaceConversation(
                allMessages = listOf(
                    ChatMessage(
                        id = "old-message",
                        text = "old projection",
                        participant = Participant.USER,
                        status = MessageStatus.SUCCESS,
                        timestamp = 1L,
                    )
                ),
                selectedChildren = mapOf(null to "old-message"),
            )
            assembler.markActive(CONVERSATION_ID)

            assembler.start()
            runCurrent()

            assertEquals(listOf(CONVERSATION_ID), failedIds)
            assertNull(assembler.loadedMessagesConversationId.value)
            assertFalse(assembler.isLoading.value)
            assertNull(assembler.generatingInConversationId.value)
            assertEquals(emptyList<ChatMessage>(), assembler.renderStore.allMessages)
            assertEquals(emptyMap<String?, String>(), assembler.renderStore.selectedChildren)
        } finally {
            unmockkObject(DebugLog)
        }
    }

    private fun generationState(
        isLoading: Boolean,
        generating: Boolean,
    ): ConversationGenerationState = mockk<ConversationGenerationState>().also { state ->
        every { state.streamingMessage } returns MutableStateFlow(null)
        every { state.isLoading } returns MutableStateFlow(isLoading)
        every { state.generating } returns MutableStateFlow(generating)
        every { state.generationSnapshot } returns MutableStateFlow(
            ConversationGenerationSnapshot(
                conversationId = CONVERSATION_ID,
                isLoading = isLoading,
                isGenerating = generating,
            ),
        )
    }

    private companion object {
        const val CONVERSATION_ID = "conversation"
    }
}
