package com.newoether.agora.ui.chat

import com.newoether.agora.model.ChatMessage
import com.newoether.agora.model.Participant
import com.newoether.agora.ui.chat.message.PendingMessageDeletion
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MessageDeletionPresentationTest {
    @Test
    fun confirmationCopiesTheExactTopologyAndDoesNotBroadenAfterGraphChanges() {
        val messages = mutableListOf(message("root", null), message("child", "root"))
        val confirmation = PendingMessageDeletion(messages, "root", compactOnly = false)
        messages += message("new-child", "root")
        assertEquals(setOf("root", "child"), confirmation.expectedConversationMessageIds)
        assertEquals("root", confirmation.targetMessageId)
        assertTrue(confirmation.deletesConversation)

        val reopened = PendingMessageDeletion(messages, "root", compactOnly = false)
        assertEquals(setOf("root", "child", "new-child"), reopened.expectedConversationMessageIds)
    }

    @Test
    fun deferredConfirmationUsesTheGraphAtActionTimeAndKeepsBranchAndCompactScope() {
        val messages = mutableListOf(message("root", null))
        messages += message("branch", "root")
        val branch = PendingMessageDeletion(messages, "branch", compactOnly = false)
        val compact = PendingMessageDeletion(messages, "root", compactOnly = true)
        assertFalse(branch.deletesConversation)
        assertFalse(compact.deletesConversation)
        assertEquals(setOf("root", "branch"), branch.expectedConversationMessageIds)
    }

    @Test
    fun rootDeletionThatCoversEveryBranchDeletesConversation() {
        val messages = listOf(
            message("root", null),
            message("left", "root"),
            message("right", "root"),
            message("leaf", "left"),
        )

        assertTrue(deletionRemovesEntireConversation(messages, "root"))
    }

    @Test
    fun compactDeletionDeletesConversationOnlyWhenItIsTheOnlyMessage() {
        val compact = message("compact", null)
        assertTrue(
            deletionRemovesEntireConversation(
                messages = listOf(compact),
                rootMessageId = compact.id,
                compactOnly = true,
            ),
        )
        assertFalse(
            deletionRemovesEntireConversation(
                messages = listOf(compact, message("survivor", "compact")),
                rootMessageId = compact.id,
                compactOnly = true,
            ),
        )
    }

    @Test
    fun branchDeletionThatLeavesAnyMessageDoesNotDeleteConversation() {
        val messages = listOf(
            message("root", null),
            message("left", "root"),
            message("right", "root"),
        )

        assertFalse(deletionRemovesEntireConversation(messages, "left"))
        assertFalse(deletionRemovesEntireConversation(messages, "missing"))
        assertFalse(deletionRemovesEntireConversation(emptyList(), "root"))
    }

    private fun message(id: String, parentId: String?) = ChatMessage(
        id = id,
        parentId = parentId,
        text = id,
        participant = Participant.USER,
        runId = "run:$id",
    )
}
