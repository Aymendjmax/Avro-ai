package com.newoether.agora.viewmodel

import com.newoether.agora.model.ChatMessage
import com.newoether.agora.model.MessageSegment
import com.newoether.agora.model.MessageStatus
import com.newoether.agora.model.Participant
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class ConversationGenerationMirrorTest {

    @Test
    fun staleConversationSendingUpdate_cannotOverwriteCurrentThinkingSnapshot() = runTest {
        val currentConversationId = MutableStateFlow<String?>("conversation-b")
        var visibleConversationId: String? = null
        var visibleSnapshot: ConversationGenerationSnapshot? = null
        val mirror = ConversationGenerationMirror(currentConversationId) { conversationId, snapshot ->
            visibleConversationId = conversationId
            visibleSnapshot = snapshot
        }
        val staleState = ConversationGenerationState("conversation-a", reclaimQueuedAttachments = {})
        val currentState = ConversationGenerationState("conversation-b", reclaimQueuedAttachments = {})
        val staleToken = staleState.acquireForSend()!!
        val currentToken = currentState.acquireForSend()!!
        val staleCollector = launch {
            mirror.collect("conversation-a", staleState)
        }
        val currentCollector = launch {
            mirror.collect("conversation-b", currentState)
        }
        runCurrent()

        val thoughtSegment = MessageSegment(type = "thought", content = "reasoning")
        currentState.streamUpdate(
            currentToken,
            ChatMessage(
                id = "model-b",
                text = "",
                participant = Participant.MODEL,
                status = MessageStatus.THINKING,
                segments = listOf(thoughtSegment),
            ),
        )
        runCurrent()

        assertEquals("conversation-b", visibleConversationId)
        assertEquals(MessageStatus.THINKING, visibleSnapshot?.streamingMessage?.status)
        assertEquals(listOf(thoughtSegment), visibleSnapshot?.streamingMessage?.segments)

        staleState.streamUpdate(
            staleToken,
            ChatMessage(
                id = "model-a",
                text = "",
                participant = Participant.MODEL,
                status = MessageStatus.SENDING,
            ),
        )
        runCurrent()

        assertEquals("conversation-b", visibleConversationId)
        assertEquals(MessageStatus.THINKING, visibleSnapshot?.streamingMessage?.status)
        assertEquals(listOf(thoughtSegment), visibleSnapshot?.streamingMessage?.segments)

        staleCollector.cancelAndJoin()
        currentCollector.cancelAndJoin()
    }
}
