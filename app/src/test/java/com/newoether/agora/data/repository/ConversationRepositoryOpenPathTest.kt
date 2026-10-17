package com.newoether.agora.data.repository

import com.newoether.agora.data.local.ChatDao
import com.newoether.agora.data.local.ChatEntity
import com.newoether.agora.data.local.MaintenanceDebtDao
import com.newoether.agora.data.local.MaintenanceDebtEntity
import com.newoether.agora.model.SelectedAttachment
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ConversationRepositoryOpenPathTest {
    @Test
    fun draftReplacementSchedulesOnlyRemovedSourceAfterTheDurableWrite() = runTest {
        val dao = mockk<ChatDao>(relaxed = true)
        val debt = mockk<MaintenanceDebtDao>(relaxed = true)
        val stagedPath = File("staged-source.jpg").canonicalPath
        val readyPath = File("ready-image.jpg").canonicalPath
        val staged = SelectedAttachment(uri = "content://source", type = "image", localPath = stagedPath)
        val ready = staged.copy(localPath = readyPath)
        val readyJson = Json.encodeToString(listOf(ready))
        var conversation = ChatEntity(
            id = "conversation", title = "Title", lastUpdated = 123L,
            draftAttachments = Json.encodeToString(listOf(staged)),
        )
        val events = mutableListOf<String>()
        coEvery { dao.getConversation(conversation.id) } answers { conversation }
        coEvery { dao.updateDraft(conversation.id, any(), any(), any()) } answers {
            conversation = conversation.copy(draftAttachments = thirdArg())
            events += "persist"
        }
        coEvery { debt.enqueue(any(), any(), any()) } answers {
            assertEquals(readyJson, conversation.draftAttachments)
            assertEquals(stagedPath, secondArg<String>())
            events += "debt"
            mockk<MaintenanceDebtEntity>()
        }
        val repository = ConversationRepository(
            dao, database = null, maintenanceDebtDao = debt,
            scheduleMaintenance = { events += "schedule" },
        )
        repository.updateDraft(conversation.id, "", readyJson)
        assertEquals(listOf("persist", "debt", "schedule"), events)
        events.clear()
        repository.updateDraft(conversation.id, "typing", readyJson)
        assertEquals(listOf("persist"), events)
        events.clear()
        coEvery { dao.updateDraft(conversation.id, any(), any(), any()) } throws IllegalStateException("write failed")
        assertTrue(runCatching { repository.updateDraft(conversation.id, "", null) }.isFailure)
        assertTrue(events.isEmpty())
        coVerify(exactly = 1) { debt.enqueue(MaintenanceDebtEntity.KIND_ATTACHMENT_ORPHANS, stagedPath, any()) }
        coVerify(exactly = 0) { debt.enqueue(any(), readyPath, any()) }
    }

    @Test
    fun branchSelectionWritesPreserveRecencyAndAdvanceBackupWatermark() = runTest {
        val dao = mockk<ChatDao>(relaxed = true)
        val conversation = ChatEntity(
            id = "conversation",
            title = "Title",
            lastUpdated = 123L,
            selectedBranchesJson = "{}",
            selectedRunBranchesJson = "{}",
        )
        coEvery { dao.getConversation(conversation.id) } returns conversation
        coEvery { dao.updateMessageBranchSelections(any(), any(), any()) } returns 1
        coEvery { dao.updateRunBranchSelections(any(), any(), any()) } returns 1
        coEvery { dao.updateBranchSelections(any(), any(), any(), any()) } returns 1
        val repository = ConversationRepository(dao, database = null)

        repository.saveBranchSelections(conversation.id, mapOf(null to "message"))
        repository.saveRunBranchSelections(conversation.id, mapOf(null to "run"))
        repository.selectRunBranch(
            conversationId = conversation.id,
            parentRunId = null,
            runId = "run",
            messageSelections = mapOf(null to "message"),
        )

        coVerify(exactly = 1) {
            dao.updateMessageBranchSelections(
                conversation.id,
                "{\"null\":\"message\"}",
                match { it > 0L },
            )
        }
        coVerify(exactly = 1) {
            dao.updateRunBranchSelections(
                conversation.id,
                "{\"null\":\"run\"}",
                match { it > 0L },
            )
        }
        coVerify(exactly = 1) {
            dao.updateBranchSelections(
                conversation.id,
                "{\"null\":\"message\"}",
                "{\"null\":\"run\"}",
                match { it > 0L },
            )
        }
        coVerify(exactly = 0) { dao.updateSelectionsForRunDeletion(any(), any(), any(), any()) }
        coVerify(exactly = 0) { dao.upsertConversation(any()) }
    }

    @Test
    fun `exact runtime recovery stays inside the DAO transaction`() = runTest {
        val dao = mockk<ChatDao>(relaxed = true)
        coEvery { dao.recoverConversationRuntime("conversation", 99L) } returns 2
        val repository = ConversationRepository(dao, database = null)

        repository.recoverConversationRuntime("conversation", 99L)

        coVerify(exactly = 1) { dao.recoverConversationRuntime("conversation", 99L) }
        coVerify(exactly = 0) { dao.upsertMessage(any()) }
    }
}
