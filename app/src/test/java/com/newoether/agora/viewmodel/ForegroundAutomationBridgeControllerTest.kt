package com.newoether.agora.viewmodel

import com.newoether.agora.automation.TaskExecutionEngine.BridgeOutcome
import com.newoether.agora.data.local.MessageEntity
import com.newoether.agora.model.MessageStatus
import com.newoether.agora.model.Participant
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ForegroundAutomationBridgeControllerTest {
    @Test
    fun startAndCloseAreIdempotentAndUseTheSameOwner() {
        val fixture = Fixture()

        fixture.controller.start()
        fixture.controller.start()
        fixture.controller.close()
        fixture.controller.close()

        assertEquals(1, fixture.attachments.size)
        assertEquals(1, fixture.detachedOwners.size)
        assertSame(fixture.attachments.single().first, fixture.detachedOwners.single())
    }

    @Test
    fun hiddenConversationIsNotDelegated() = runTest {
        val fixture = Fixture(currentConversationId = "other")
        fixture.controller.start()

        val outcome = fixture.bridge()("conversation", "input", "model", "loop")

        assertEquals(BridgeOutcome.NotDelegated, outcome)
        assertTrue(fixture.sendInputs.isEmpty())
        assertTrue(fixture.loadedMessageIds.isEmpty())
    }

    @Test
    fun busySendReturnsBusyWithoutReadingRoom() = runTest {
        val fixture = Fixture(sendOutcome = AutomationSendOutcome.SlotBusy)
        fixture.controller.start()

        val outcome = fixture.bridge()("conversation", "input", "model", "loop")

        assertEquals(BridgeOutcome.Busy(), outcome)
        assertEquals(
            listOf(listOf("conversation", "input", "model", "loop")),
            fixture.sendInputs,
        )
        assertTrue(fixture.loadedMessageIds.isEmpty())
    }

    @Test
    fun deliveredSendReturnsTheExactSuccessfulRow() = runTest {
        val fixture = Fixture(
            sendOutcome = AutomationSendOutcome.Delivered("expected"),
            messages = listOf(
                modelMessage("tail", "unrelated"),
                modelMessage("expected", "answer"),
            ),
        )
        fixture.controller.start()

        val outcome = fixture.bridge()("conversation", "input", "model", "loop")

        assertEquals(BridgeOutcome.Completed("expected", "answer"), outcome)
        assertEquals(listOf("expected"), fixture.loadedMessageIds)
    }

    @Test
    fun missingDeliveredRowReturnsFailure() = runTest {
        val fixture = Fixture(
            sendOutcome = AutomationSendOutcome.Delivered("missing"),
            messages = listOf(modelMessage("other", "answer")),
        )
        fixture.controller.start()

        val outcome = fixture.bridge()("conversation", "input", "model", "loop")

        assertEquals(BridgeOutcome.Failed("Generation row disappeared"), outcome)
    }

    @Test
    fun nonSuccessfulDeliveredRowReturnsItsErrorOrFallback() = runTest {
        val fixture = Fixture(
            sendOutcome = AutomationSendOutcome.Delivered("failed"),
            messages = listOf(modelMessage("failed", "provider error", MessageStatus.ERROR)),
        )
        fixture.controller.start()

        val explicit = fixture.bridge()("conversation", "input", "model", "loop")
        fixture.messages = listOf(modelMessage("failed", "", MessageStatus.STOPPED))
        val fallback = fixture.bridge()("conversation", "input", "model", "loop")

        assertEquals(BridgeOutcome.Failed("provider error"), explicit)
        assertEquals(BridgeOutcome.Failed("Generation failed"), fallback)
    }

    @Test
    fun deliveredRowFromAnotherConversationIsRejected() = runTest {
        val fixture = Fixture(
            sendOutcome = AutomationSendOutcome.Delivered("foreign"),
            messages = listOf(
                modelMessage(
                    id = "foreign",
                    text = "answer",
                    conversationId = "other",
                ),
            ),
        )
        fixture.controller.start()

        val outcome = fixture.bridge()("conversation", "input", "model", "loop")

        assertEquals(BridgeOutcome.Failed("Generation row disappeared"), outcome)
        assertEquals(listOf("foreign"), fixture.loadedMessageIds)
    }

    private class Fixture(
        currentConversationId: String? = "conversation",
        var sendOutcome: AutomationSendOutcome = AutomationSendOutcome.SlotBusy,
        var messages: List<MessageEntity> = emptyList(),
    ) {
        val attachments = mutableListOf<Pair<Any, ForegroundSendBridge>>()
        val detachedOwners = mutableListOf<Any>()
        val sendInputs = mutableListOf<List<String>>()
        val loadedMessageIds = mutableListOf<String>()
        val controller = ForegroundAutomationBridgeController(
            isConversationOpen = { it == currentConversationId },
            send = { conversationId, text, modelId, requestKind ->
                sendInputs += listOf(conversationId, text, modelId, requestKind)
                sendOutcome
            },
            loadMessage = { messageId ->
                loadedMessageIds += messageId
                messages.find { it.id == messageId }
            },
            attach = { owner, bridge -> attachments += owner to bridge },
            detach = detachedOwners::add,
        )

        fun bridge(): ForegroundSendBridge = attachments.single().second
    }

    private companion object {
        fun modelMessage(
            id: String,
            text: String,
            status: MessageStatus = MessageStatus.SUCCESS,
            conversationId: String = "conversation",
        ) = MessageEntity(
            id = id,
            conversationId = conversationId,
            text = text,
            status = status,
            participant = Participant.MODEL,
            timestamp = 1L,
            runId = "run",
        )
    }
}
