package com.newoether.agora.viewmodel

import com.newoether.agora.model.ChatMessage
import com.newoether.agora.model.Participant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatClientsTest {

    private fun message(id: String) = ChatMessage(id = id, text = id, participant = Participant.USER)

    @Test
    fun `open and visible mean any attached client`() {
        val clients = ChatClients()
        val phone = FakeChatClient(open = "a", visible = false)
        val web = FakeChatClient(open = "b", visible = true)
        clients.attach(phone)
        clients.attach(web)

        assertTrue(clients.isConversationOpen("a"))
        assertTrue(clients.isConversationOpen("b"))
        assertFalse(clients.isConversationOpen("c"))
        assertFalse(clients.isConversationVisible("a"))
        assertTrue(clients.isConversationVisible("b"))

        clients.detach(web)
        assertFalse(clients.isConversationOpen("b"))
        assertFalse(clients.isConversationVisible("b"))
    }

    @Test
    fun `graph commits reach only clients showing that conversation`() {
        val clients = ChatClients()
        val first = FakeChatClient(open = "a")
        val second = FakeChatClient(open = "a")
        val other = FakeChatClient(open = "b")
        listOf(first, second, other).forEach(clients::attach)

        clients.commitGraph(
            conversationId = "a",
            committedMessages = listOf(message("m1")),
            selectedChildren = mapOf(null to "m1"),
            streamingMessage = null,
        )

        assertEquals(listOf("m1"), first.renderStore.allMessages.map { it.id })
        assertEquals(listOf("m1"), second.renderStore.allMessages.map { it.id })
        assertTrue(other.renderStore.allMessages.isEmpty())

        clients.replaceGraph("b", listOf(message("m2")), emptyMap())
        assertEquals(listOf("m2"), other.renderStore.allMessages.map { it.id })
        assertEquals(listOf("m1"), first.renderStore.allMessages.map { it.id })
    }

    @Test
    fun `fences open per showing client and a client that left is still released`() {
        val clients = ChatClients()
        val stays = FakeChatClient(open = "a")
        val leaves = FakeChatClient(open = "a")
        clients.attach(stays)
        clients.attach(leaves)

        assertNull(clients.beginRoomProjectionFences("none"))
        val fences = requireNotNull(clients.beginRoomProjectionFences("a"))
        assertEquals(2, fences.byStore.size)

        leaves.open = "b"
        clients.commitGraph(
            conversationId = "a",
            committedMessages = listOf(message("m1")),
            selectedChildren = emptyMap(),
            streamingMessage = null,
            fences = fences,
        )

        assertEquals(listOf("m1"), stays.renderStore.allMessages.map { it.id })
        assertTrue(leaves.renderStore.allMessages.isEmpty())
        // Released fence: a later Room projection on the store that left is no longer deferred.
        leaves.renderStore.setAllMessages(listOf(message("m3")))
        assertEquals(listOf("m3"), leaves.renderStore.allMessages.map { it.id })
    }

    @Test
    fun `effects go to the origin, or to every client showing the conversation without one`() {
        val clients = ChatClients()
        val origin = FakeChatClient(open = "b")
        val showingA = FakeChatClient(open = "a")
        val alsoA = FakeChatClient(open = "a")
        listOf(origin, showingA, alsoA).forEach(clients::attach)

        assertEquals(listOf(origin), clients.effectTargets("a", origin))
        assertEquals(listOf(showingA, alsoA), clients.effectTargets("a", origin = null))
        assertTrue(clients.effectTargets("c", origin = null).isEmpty())

        val accepted = mutableListOf<String>()
        showingA.onAccepted = { conversationId, messageId -> accepted += "$conversationId:$messageId" }
        SendAcceptanceNotifier(clients).publish(SendAcceptance.Queued("m1", "a"), origin = null)
        assertEquals(listOf("a:m1"), accepted)
    }
    @Test
    fun `activity reaches every attached client and a terminal commit only clients showing it`() {
        val clients = ChatClients()
        val showing = FakeChatClient(open = "a")
        val elsewhere = FakeChatClient(open = "b")
        clients.attach(showing)
        clients.attach(elsewhere)
        clients.generationActivityChanged("a", active = true)
        clients.generationActivityChanged("a", active = false)
        assertEquals(listOf("a" to true, "a" to false), showing.activityChanges)
        assertEquals(listOf("a" to true, "a" to false), elsewhere.activityChanges)
        clients.commitTerminalStreamingMessage("a", message("m1"))
        assertEquals(listOf("m1"), showing.renderStore.allMessages.map { it.id })
        assertTrue(elsewhere.renderStore.allMessages.isEmpty())
    }
}