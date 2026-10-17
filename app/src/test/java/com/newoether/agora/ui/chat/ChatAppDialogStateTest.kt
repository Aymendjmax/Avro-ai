package com.newoether.agora.ui.chat

import androidx.compose.runtime.mutableStateOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatAppDialogStateTest {
    @Test
    fun `rename and delete requests retain only their own dialog inputs`() {
        val state = ChatAppDialogState(mutableStateOf(false))

        state.requestRename("conversation", "Initial")
        state.requestDelete("other")

        assertEquals("conversation", state.renameConversationId)
        assertEquals("Initial", state.renameInitialName)
        assertEquals("other", state.deleteConversationId)

        state.dismissRename()
        state.dismissDelete()
        assertNull(state.renameConversationId)
        assertNull(state.deleteConversationId)
    }

    @Test
    fun `pending delete blocks duplicate confirm dismissal and replacement`() {
        val state = ChatAppDialogState(mutableStateOf(false))
        state.requestDelete("conversation")

        assertTrue(state.beginDelete("conversation"))
        assertFalse(state.beginDelete("conversation"))
        state.requestDelete("other")
        state.dismissDelete()

        assertEquals("conversation", state.deleteConversationId)
        assertEquals(ChatDeleteDialogPhase.PENDING, state.deleteConversationPhase)
        assertTrue(state.isDeletePending("conversation"))

        state.failDelete("conversation")
        assertEquals(ChatDeleteDialogPhase.FAILED, state.deleteConversationPhase)
        state.dismissDelete()
        assertNull(state.deleteConversationId)
    }

    @Test
    fun `late delete callback cannot clear or fail a newer request`() {
        val state = ChatAppDialogState(mutableStateOf(false))
        state.requestDelete("old")
        assertTrue(state.beginDelete("old"))
        state.completeDelete("old")
        state.requestDelete("new")

        state.completeDelete("old")
        state.failDelete("old")

        assertEquals("new", state.deleteConversationId)
        assertEquals(ChatDeleteDialogPhase.CONFIRM, state.deleteConversationPhase)
    }

    @Test
    fun `failed selected delete restores an explicit retry state`() {
        val state = ChatAppDialogState(mutableStateOf(false))
        state.requestDelete("conversation")
        assertTrue(state.beginDelete("conversation"))
        state.failDelete("conversation")

        assertEquals("conversation", state.deleteConversationId)
        assertEquals(ChatDeleteDialogPhase.FAILED, state.deleteConversationPhase)
        assertTrue(state.beginDelete("conversation"))
    }

    @Test
    fun `completed deletion closes once and late failure cannot reopen it`() {
        val state = ChatAppDialogState(mutableStateOf(false))
        state.requestDelete("conversation")
        assertTrue(state.beginDelete("conversation"))
        state.completeDelete("conversation")
        state.failDelete("conversation")

        assertNull(state.deleteConversationId)
        assertEquals(ChatDeleteDialogPhase.CONFIRM, state.deleteConversationPhase)
    }

    @Test
    fun `manual compact visibility remains backed by supplied saveable state`() {
        val visible = mutableStateOf(false)
        val state = ChatAppDialogState(visible)

        state.showManualCompact()
        assertTrue(visible.value)
        assertTrue(state.manualCompactVisible)

        state.dismissManualCompact()
        assertFalse(visible.value)
        assertFalse(state.manualCompactVisible)
    }
}
