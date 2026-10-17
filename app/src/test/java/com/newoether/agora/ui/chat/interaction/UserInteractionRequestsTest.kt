package com.newoether.agora.ui.chat.interaction

import com.newoether.agora.viewmodel.AskUserController
import com.newoether.agora.viewmodel.ShellConfirmationController
import kotlinx.coroutines.CompletableDeferred
import org.junit.Assert.assertEquals
import org.junit.Test

class UserInteractionRequestsTest {

    @Test
    fun `a conversation shows its own questions and the ones with no conversation`() {
        val mine = question(id = 1, conversationId = "c1")
        val other = question(id = 2, conversationId = "c2")
        val unowned = question(id = 3, conversationId = null)

        val interactions = userInteractions("c1", listOf(mine, other, unowned), null)

        assertEquals(
            listOf("question:1", "question:3"),
            interactions.map { it.key },
        )
    }

    @Test
    fun `the shell confirmation comes first because its command is held`() {
        val first = question(id = 7, conversationId = "c1")
        val pending = ShellConfirmationController.PendingShellCommand(
            id = 7,
            server = "tinybox",
            summary = "ls",
            deferred = CompletableDeferred(),
            conversationId = "c1",
        )

        val interactions = userInteractions("c1", listOf(first), pending)

        // Both controllers number requests independently, so identical ids must stay distinct.
        assertEquals(listOf("shell:7", "question:7"), interactions.map { it.key })
    }

    @Test
    fun `a shell confirmation of another conversation is not shown here`() {
        val pending = ShellConfirmationController.PendingShellCommand(
            id = 1,
            server = "tinybox",
            summary = "ls",
            deferred = CompletableDeferred(),
            conversationId = "c2",
        )
        assertEquals(emptyList<UserInteraction>(), userInteractions("c1", emptyList(), pending))
        assertEquals(listOf("shell:1"), userInteractions("c2", emptyList(), pending).map { it.key })
    }

    @Test
    fun `a request is on screen only in its own conversation`() {
        val tracker = com.newoether.agora.service.AppForegroundTracker
        assertEquals(true, tracker.isShownInChat("c1", true, true, "c1"))
        assertEquals(false, tracker.isShownInChat("c1", true, true, "c2"))
        assertEquals(true, tracker.isShownInChat(null, true, true, "c2"))
        assertEquals(false, tracker.isShownInChat("c1", false, true, "c1"))
        assertEquals(false, tracker.isShownInChat("c1", true, false, "c1"))
    }

    @Test
    fun `questions asked together form one card in the place of the first`() {
        val setFirst = question(id = 2, conversationId = "c1", setId = 1)
        val single = question(id = 4, conversationId = "c1")
        val setSecond = question(id = 3, conversationId = "c1", setId = 1)
        val interactions = userInteractions("c1", listOf(setFirst, single, setSecond), null)
        assertEquals(listOf("question:1", "question:4"), interactions.map { it.key })
        assertEquals(
            listOf(2L, 3L),
            (interactions.first() as UserInteraction.Question).requests.map { it.id },
        )
    }

    @Test
    fun `nothing pending produces no cards`() {
        assertEquals(emptyList<UserInteraction>(), userInteractions("c1", emptyList(), null))
    }

    private fun question(
        id: Long,
        conversationId: String?,
        setId: Long? = null,
    ) = AskUserController.Request(
        id = id,
        conversationId = conversationId,
        question = "Which one?",
        options = listOf("A", "B"),
        allowMultiple = false,
        blocking = true,
        setId = setId,
    )
}
