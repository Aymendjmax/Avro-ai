package com.newoether.agora.webui

import com.newoether.agora.model.ChatMessage
import com.newoether.agora.model.Participant
import com.newoether.agora.viewmodel.BranchReplacementTransitionCoordinator
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class WebUiBranchReplacementTest {
    private val transitions = BranchReplacementTransitionCoordinator()
    private val target = MutableStateFlow(WebUiChatSession.OpenTarget("c", 7L, false))
    private val session = mockk<WebUiChatSession> {
        every { openTarget } returns target
        every { branchTransitions } returns transitions
    }
    private fun command(type: String, id: Long) = WebSyncCommand(
        type, conversationId = "c", seq = 7L, actionId = id, connectionId = "connection",
    )
    private fun acknowledge(command: WebSyncCommand, path: List<ChatMessage> = emptyList()) =
        acknowledgeBranchReplacement(session, command, "connection", path)

    @Test
    fun acknowledgementsRequireTheExactConnectionSelectionAndRequest() {
        val request = transitions.begin("c", "old")!!
        val valid = command("branch_fade", request.id)
        listOf(
            valid.copy(connectionId = "other"), valid.copy(conversationId = "other"),
            valid.copy(seq = 6L), valid.copy(actionId = request.id + 1),
        ).forEach(::acknowledge)
        assertFalse(transitions.request.value!!.fadeFinished)
        target.value = WebUiChatSession.OpenTarget("other", 7L, false)
        acknowledge(valid)
        assertFalse(transitions.request.value!!.fadeFinished)
        target.value = WebUiChatSession.OpenTarget("c", 7L, false)
        acknowledge(valid)
        assertTrue(transitions.request.value!!.fadeFinished)
    }

    @Test
    fun fadeAcknowledgementReleasesTheCanonicalGenerationBarrier() = runTest {
        val request = transitions.begin("c", "old")!!
        var result: Boolean? = null
        val waiter = launch { result = transitions.awaitFade(request.id) }
        runCurrent()
        assertNull(result)
        acknowledge(command("branch_fade", request.id))
        waiter.join()
        assertEquals(true, result)
    }

    @Test
    fun completeRequiresCommitScrollSettlementAndOldPathRemoval() {
        val request = transitions.begin("c", "old")!!
        val complete = command("branch_complete", request.id)
        acknowledge(complete)
        assertTrue(transitions.request.value != null)
        transitions.markCommitted(request.id)
        acknowledge(complete)
        assertTrue(transitions.request.value != null)
        acknowledge(command("branch_scroll", request.id).copy(enabled = false))
        assertEquals(false, transitions.request.value!!.scrollSucceeded)
        val old = ChatMessage(id = "old", text = "old", participant = Participant.MODEL)
        acknowledge(complete, listOf(old))
        assertTrue(transitions.request.value != null)
        acknowledge(complete)
        assertNull(transitions.request.value)
    }

    @Test
    fun staleAcknowledgementsCannotSettleANewerRequest() {
        val first = transitions.begin("c", "old")!!
        transitions.abortCurrent()
        val next = transitions.begin("c", "next")!!
        acknowledge(command("branch_fade", first.id))
        acknowledge(command("branch_scroll", first.id).copy(enabled = true))
        assertFalse(transitions.request.value!!.fadeFinished)
        assertFalse(transitions.request.value!!.scrollFinished)
        acknowledge(command("branch_scroll", next.id))
        assertFalse(transitions.request.value!!.scrollFinished)
    }

    @Test
    fun forwardingUsesTheSameCoordinatorAndSelectionIdentity() = runTest {
        val events = Channel<WebSyncEvent>(Channel.UNLIMITED)
        val worker = backgroundScope.launch { forwardBranchReplacement(session, target.value, "connection", events) }
        runCurrent()
        val initial = events.tryReceive().getOrThrow() as WebSyncEvent.BranchReplacement
        assertEquals("connection", initial.connectionId)
        assertEquals("c", initial.conversationId)
        assertEquals(7L, initial.seq)
        assertNull(initial.request)
        val request = transitions.begin("c", "old", targetUserMessageId = "u")!!
        runCurrent()
        val event = events.tryReceive().getOrThrow() as WebSyncEvent.BranchReplacement
        assertEquals(request.id.toString(), event.request!!["id"]!!.jsonPrimitive.content)
        assertEquals("u", event.request["targetUserMessageId"]!!.jsonPrimitive.content)
        acknowledge(command("branch_fade", request.id))
        runCurrent()
        val faded = events.tryReceive().getOrThrow() as WebSyncEvent.BranchReplacement
        assertEquals("true", faded.request!!["fadeFinished"]!!.jsonPrimitive.content)
        transitions.abortCurrent()
        runCurrent()
        assertNull((events.tryReceive().getOrThrow() as WebSyncEvent.BranchReplacement).request)
        worker.cancel()
    }
}
