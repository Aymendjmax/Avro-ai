package com.newoether.agora.viewmodel

import com.newoether.agora.model.ChatMessage
import com.newoether.agora.model.ConversationCommand
import com.newoether.agora.model.MessageStatus
import com.newoether.agora.model.Participant
import com.newoether.agora.model.RunEffect
import com.newoether.agora.model.RunEffectIdentity
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kotlinx.coroutines.CompletableJob
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GenerationStopAdapterTest {
    @Test
    fun noOpenConversationOrRuntimeIsANoOp() {
        val fixture = Fixture(currentConversationId = null)

        fixture.stop()
        fixture.client.open = "missing"
        every { fixture.registry.get("missing") } returns null
        fixture.stop()

        verify(exactly = 0) { fixture.state.requestStop(any()) }
    }

    @Test
    fun missingStreamingOverlaySnapshotsOnlyVisibleInflightModelRows() {
        val fixture = Fixture()
        fixture.renderStore.replaceGraph(
            allMessages = listOf(USER, SENDING_MODEL, SUCCESS_MODEL),
            selectedChildren = emptyMap(),
        )
        fixture.stubStop(stoppedMessage = null)
        val capturedMessages = slot<List<ChatMessage>>()
        fixture.stubFinalization(
            outcome = ConversationGenerationState.StopFinalizationOutcome.RECORDED,
            success = true,
            capturedMessages = capturedMessages,
        )

        fixture.stop()

        assertEquals(listOf("sending"), capturedMessages.captured.map { it.id })
        assertEquals(MessageStatus.STOPPED, capturedMessages.captured.single().status)
        assertEquals(
            MessageStatus.STOPPED,
            fixture.renderStore.allMessages.single { it.id == "sending" }.status,
        )
        assertEquals(MessageStatus.SUCCESS, fixture.renderStore.allMessages
            .single { it.id == "success" }.status)
        verify(exactly = 1) { fixture.state.clearStoppedOverlay() }
        assertTrue(fixture.failures.isEmpty())
    }

    @Test
    fun staleCompletionCannotClearOrCommitStoppedOverlay() {
        val fixture = Fixture()
        fixture.renderStore.commitGraph(
            committedMessages = listOf(USER, SENDING_MODEL),
            selectedChildren = emptyMap(),
            streamingMessage = SENDING_MODEL,
        )
        val stopped = SENDING_MODEL.copy(status = MessageStatus.STOPPED)
        fixture.stubStop(stoppedMessage = stopped)
        fixture.stubFinalization(
            outcome = ConversationGenerationState.StopFinalizationOutcome.REJECTED,
            success = true,
        )

        fixture.stop()

        assertEquals(MessageStatus.SENDING, fixture.renderStore.streamingMessage?.status)
        verify(exactly = 0) { fixture.state.clearStoppedOverlay() }
        assertTrue(fixture.failures.isEmpty())
    }

    @Test
    fun acceptedPersistenceFailureReportsErrorWithoutClearingOverlay() {
        val fixture = Fixture()
        fixture.stubStop(stoppedMessage = SENDING_MODEL.copy(status = MessageStatus.STOPPED))
        fixture.stubFinalization(
            outcome = ConversationGenerationState.StopFinalizationOutcome.FAILED,
            success = false,
        )

        fixture.stop()

        assertEquals(listOf("failed"), fixture.failures)
        verify(exactly = 0) { fixture.state.clearStoppedOverlay() }
    }

    @Test
    fun stoppedRowsReachEveryClientShowingTheConversationAndFailureOnlyTheOrigin() {
        val fixture = Fixture()
        val otherViewer = FakeChatClient(open = "conversation")
        val elsewhere = FakeChatClient(open = "another")
        fixture.clients.attach(otherViewer)
        fixture.clients.attach(elsewhere)
        listOf(fixture.renderStore, otherViewer.renderStore, elsewhere.renderStore).forEach {
            it.replaceGraph(allMessages = listOf(USER, SENDING_MODEL), selectedChildren = emptyMap())
        }
        fixture.stubStop(stoppedMessage = null)
        fixture.stubFinalization(
            outcome = ConversationGenerationState.StopFinalizationOutcome.FAILED,
            success = false,
        )

        fixture.stop()

        assertEquals(
            MessageStatus.STOPPED,
            otherViewer.renderStore.allMessages.single { it.id == "sending" }.status,
        )
        assertEquals(
            MessageStatus.SENDING,
            elsewhere.renderStore.allMessages.single { it.id == "sending" }.status,
        )
        assertEquals(listOf("failed"), fixture.failures)
        assertTrue(otherViewer.snackbars.isEmpty())
    }

    private class Fixture(currentConversationId: String? = "conversation") {
        val client = FakeChatClient(open = currentConversationId)
        val clients = ChatClients().also { it.attach(client) }
        val registry = mockk<ConversationStateRegistry>()
        val state = mockk<ConversationGenerationState>()
        val renderStore: ConversationRenderStore get() = client.renderStore
        val finalizer = mockk<GenerationFinalizer>()
        val failures: List<String> get() = client.snackbars
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val adapter = GenerationStopAdapter(
            registry = registry,
            clients = clients,
            finalizer = finalizer,
            failureText = { "failed" },
        )

        fun stop() = adapter.stop(client.open, client)

        init {
            every { registry.get("conversation") } returns state
            every { state.scope } returns scope
            every { state.clearStoppedOverlay() } just Runs
        }

        fun stubStop(stoppedMessage: ChatMessage?) {
            every { state.requestStop(any()) } answers {
                firstArg<(ConversationGenerationState.StopResult) -> Unit>().invoke(
                    ConversationGenerationState.StopResult(
                        stoppedMessage = stoppedMessage,
                        conversationId = "conversation",
                        runId = "run",
                        finalizationEffect = RunEffect.FinalizeStop(IDENTITY),
                    ),
                )
                completedJob()
            }
        }

        fun stubFinalization(
            outcome: ConversationGenerationState.StopFinalizationOutcome,
            success: Boolean,
            capturedMessages: io.mockk.CapturingSlot<List<ChatMessage>>? = null,
        ) {
            coEvery { state.finishStopFinalization(any()) } returns outcome
            every {
                finalizer.launchStopFinalization(
                    scope = scope,
                    identity = IDENTITY,
                    messages = if (capturedMessages != null) capture(capturedMessages) else any(),
                    onFinalized = any(),
                )
            } answers {
                val callback = arg<suspend (ConversationCommand.PersistenceSettled) -> Unit>(3)
                runBlocking {
                    callback(ConversationCommand.PersistenceSettled(IDENTITY, success))
                }
                completedJob()
            }
        }

        private fun completedJob(): CompletableJob = Job().also { it.complete() }
    }

    private companion object {
        val IDENTITY = RunEffectIdentity(
            conversationId = "conversation",
            ownerToken = 1L,
            runId = "run",
            pass = 0,
            effectId = "stop-1",
        )
        val USER = ChatMessage(
            id = "user",
            text = "input",
            participant = Participant.USER,
            status = MessageStatus.SUCCESS,
        )
        val SENDING_MODEL = ChatMessage(
            id = "sending",
            parentId = "user",
            text = "partial",
            participant = Participant.MODEL,
            status = MessageStatus.SENDING,
        )
        val SUCCESS_MODEL = ChatMessage(
            id = "success",
            parentId = "user",
            text = "done",
            participant = Participant.MODEL,
            status = MessageStatus.SUCCESS,
        )
    }
}
