package com.newoether.agora.viewmodel

import android.content.Context
import com.newoether.agora.data.local.ChatDao
import com.newoether.agora.data.local.MessageEntity
import com.newoether.agora.data.repository.ConversationRepository
import com.newoether.agora.data.repository.toStreamCheckpoint
import com.newoether.agora.model.ChatMessage
import com.newoether.agora.model.MessageSegment
import com.newoether.agora.model.MessageStatus
import com.newoether.agora.model.Participant
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class ToolRoundReferenceHydrationTest {
    private val body = MessageSegment("tool", toolName = "file_read", toolCallId = "call",
        toolArgs = "{}", toolResult = "result", toolState = "succeeded")
    private val root = MessageEntity(id = "root", conversationId = "conversation", text = "",
        participant = Participant.MODEL, status = MessageStatus.SUCCESS, timestamp = 1,
        runId = "run", toolCallJson = ChatMessage(id = "root", text = "",
            participant = Participant.MODEL, segments = listOf(body.copy(toolRoundId = "tool_round")))
            .toStreamCheckpoint().toolCallJson)
    private val round = root.copy(id = "tool_round", parentId = "root",
        toolCallJson = Json.encodeToString(listOf(body)))

    @Test
    fun `selected hydration restores all entry points without reading other owners`() = runTest {
        val dao = mockk<ChatDao>()
        coEvery { dao.getMessagesByIds(listOf("root")) } returns listOf(root)
        coEvery { dao.getMessagesByIds(listOf("tool_round")) } returns listOf(round)
        every { dao.observeMessage("root") } returns flowOf(root)
        every { dao.observeMessagesByIds("conversation", listOf("root")) } returns flowOf(listOf(root))
        val hydration = ConversationMessagePayloadHydration(ConversationRepository(dao, database = null),
            mockk<Context>(relaxed = true), StandardTestDispatcher(testScheduler))
        val expected = listOf(body.copy(toolRoundId = "tool_round"))
        assertEquals(expected, hydration.loadMessages("conversation", listOf("root")).single().segments)
        assertEquals(expected, hydration.observeMessage("root").first()!!.segments)
        assertEquals(expected, hydration.observeMessages("conversation", listOf("root")).first().single().segments)
        coVerify(exactly = 3) { dao.getMessagesByIds(listOf("tool_round")) }
        coVerify(exactly = 0) { dao.getMessageContextTopology(any()) }
        coVerify(exactly = 0) { dao.getMessagesForRuns(any()) }
        coVerify(exactly = 0) { dao.getAllConversationsList() }
    }

    @Test
    fun `cancelled body load propagates and never publishes partial tools`() = runTest {
        val dao = mockk<ChatDao>()
        coEvery { dao.getMessagesByIds(listOf("root")) } returns listOf(root)
        coEvery { dao.getMessagesByIds(listOf("tool_round")) } throws CancellationException("cancel")
        val hydration = ConversationMessagePayloadHydration(ConversationRepository(dao, database = null),
            mockk<Context>(relaxed = true), StandardTestDispatcher(testScheduler))
        var transforms = 0
        val failure = runCatching {
            hydration.loadMessages("conversation", listOf("root")) { transforms++; it }
        }.exceptionOrNull()
        assertTrue(failure is CancellationException)
        assertEquals(0, transforms)
    }
}
