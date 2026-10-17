package com.newoether.agora.data.repository

import com.newoether.agora.data.local.ChatDao
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ConversationRepositoryRecoveryTest {
    @Test
    fun recoveryTargetsOnlyTheRequestedOwner() = runTest {
        val dao = mockk<ChatDao>()
        coEvery { dao.recoverConversationRuntime("owner-a", 99L) } returns 3
        val repository = ConversationRepository(dao, database = null)

        assertEquals(3, repository.recoverConversationRuntime("owner-a", 99L))

        coVerify(exactly = 1) { dao.recoverConversationRuntime("owner-a", 99L) }
        coVerify(exactly = 0) { dao.recoverConversationRuntime("owner-b", any()) }
    }

    @Test
    fun ownerFailurePropagatesWithoutOpeningAGlobalBarrier() = runTest {
        val dao = mockk<ChatDao>()
        coEvery { dao.recoverConversationRuntime("owner-a", 99L) } throws
            IllegalStateException("owner-a failed")
        coEvery { dao.recoverConversationRuntime("owner-b", 100L) } returns 1
        val repository = ConversationRepository(dao, database = null)

        val failure = runCatching {
            repository.recoverConversationRuntime("owner-a", 99L)
        }.exceptionOrNull()
        val recovered = repository.recoverConversationRuntime("owner-b", 100L)

        assertTrue(failure is IllegalStateException)
        assertEquals(1, recovered)
        coVerify(exactly = 1) { dao.recoverConversationRuntime("owner-a", 99L) }
        coVerify(exactly = 1) { dao.recoverConversationRuntime("owner-b", 100L) }
    }
}
