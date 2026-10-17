package com.newoether.agora.viewmodel

import com.newoether.agora.data.repository.ConversationRepository
import com.newoether.agora.model.ChatConversation
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class UnreadGenerationAcknowledgerTest {
    @Test
    fun visibleReadCancelsOnlyAfterDurableUnreadWriteSucceeds() = runTest {
        val current = MutableStateFlow<ChatConversation?>(null)
        val foreground = MutableStateFlow(false)
        val chatPresented = MutableStateFlow(false)
        val conversations = mockk<ConversationRepository>()
        val cancelled = mutableListOf<String>()
        coEvery { conversations.setConversationUnreadGeneration("accepted", false) } returns true
        coEvery { conversations.setConversationUnreadGeneration("rejected", false) } returns false
        UnreadGenerationAcknowledger(
            currentConversation = current,
            appForeground = foreground,
            chatPresented = chatPresented,
            conversations = conversations,
            scope = backgroundScope,
            onConversationRead = cancelled::add,
            ioDispatcher = StandardTestDispatcher(testScheduler),
        ).start()
        runCurrent()

        current.value = ChatConversation("accepted", "Accepted", hasUnreadGeneration = true)
        foreground.value = true
        runCurrent()
        coVerify(exactly = 0) { conversations.setConversationUnreadGeneration(any(), any()) }

        chatPresented.value = true
        runCurrent()
        current.value = ChatConversation("rejected", "Rejected", hasUnreadGeneration = true)
        runCurrent()
        current.value = ChatConversation("read", "Read", hasUnreadGeneration = false)
        runCurrent()

        coVerify(exactly = 1) { conversations.setConversationUnreadGeneration("accepted", false) }
        coVerify(exactly = 1) { conversations.setConversationUnreadGeneration("rejected", false) }
        assertEquals(listOf("accepted"), cancelled)
    }
}
