package com.newoether.agora.viewmodel

import com.newoether.agora.data.AutoBackupManager
import com.newoether.agora.data.BackupResult
import com.newoether.agora.data.MemoryManager
import com.newoether.agora.data.SkillManager
import com.newoether.agora.data.local.ChatEntity
import com.newoether.agora.data.repository.ConversationRepository
import com.newoether.agora.data.repository.SettingsRepository
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.coVerifyOrder
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DataControlControllerTest {
    @Test
    fun `backup due checks propagate settings cancellation`() = runTest {
        val settings = mockk<com.newoether.agora.data.SettingsManager>()
        val cancelled = kotlinx.coroutines.CancellationException("settings cancelled")
        every { settings.autoBackupEnabled } returns kotlinx.coroutines.flow.flow { throw cancelled }
        val manager = AutoBackupManager(mockk(), settings, mockk(), mockk())
        try {
            assertEquals(cancelled, runCatching { manager.isBackupDue() }.exceptionOrNull())
            assertEquals(cancelled, runCatching { manager.checkAndBackup() }.exceptionOrNull())
        } finally {
            manager.destroy()
        }
    }

    @Test
    fun `refresh counts conversations files active memory and prompts on owned dispatcher`() =
        runTest {
            val conversations = mockk<ConversationRepository>()
            val memory = mockk<MemoryManager>()
            val skills = mockk<SkillManager>()
            val settings = settings()
            coEvery { conversations.getAllConversationsList() } returns listOf(
                ChatEntity("one", "One"),
                ChatEntity("two", "Two"),
            )
            every { memory.listFiles() } returns listOf(
                MemoryManager.MemoryFileInfo("memory.md"),
            )
            every { memory.getActiveMemory() } returns "active"
            every { skills.listFiles() } returns listOf(
                SkillManager.SkillFileInfo("review.md"),
            )
            coEvery { settings.getSystemPrompts() } returns emptyList()
            val controller = controller(conversations, memory, skills, settings)

            controller.refreshCounts()
            runCurrent()

            assertEquals(2, controller.conversationCount.value)
            assertEquals(3, controller.memoryCount.value)
            assertEquals(0, controller.systemPromptCount.value)
        }

    @Test
    fun `backup and delete period commands preserve strict tier relationship and write order`() =
        runTest {
            val settings = settings(
                backupPeriodHours = 24,
                deletePeriodHours = 168,
            )
            val controller = controller(settings = settings)

            controller.setAutoBackupPeriodHours(168)
            runCurrent()
            coVerifyOrder {
                settings.saveAutoBackupPeriodHours(168)
                settings.saveAutoDeletePeriodHours(720)
            }

            controller.setAutoDeletePeriodHours(1)
            runCurrent()
            coVerifyOrder {
                settings.saveAutoDeletePeriodHours(168)
            }
        }

    @Test
    fun `startup and enabled commands use scheduler while destroy releases backup manager`() =
        runTest {
            val settings = settings()
            val backupManager = mockk<AutoBackupManager>()
            val schedule = FakeAutoBackupSchedule()
            coEvery { backupManager.checkAndBackup() } returns BackupResult.NOT_DUE
            every { backupManager.destroy() } just Runs
            val controller = controller(
                settings = settings,
                backupManager = backupManager,
                schedule = schedule,
            )

            controller.startAutoBackup()
            runCurrent()
            controller.setAutoBackupEnabled(true)
            controller.setAutoBackupEnabled(false)
            runCurrent()
            controller.destroy()

            assertEquals(2, schedule.scheduleCount)
            assertEquals(1, schedule.cancelCount)
            coVerifyOrder {
                settings.saveAutoBackupEnabled(true)
                settings.saveAutoBackupEnabled(false)
            }
            verify(exactly = 1) { backupManager.destroy() }
            io.mockk.coVerify(exactly = 0) { backupManager.checkAndBackup() }
        }

    private fun TestScope.controller(
        conversations: ConversationRepository = mockk(),
        memory: MemoryManager = mockk(),
        skills: SkillManager = mockk {
            every { listFiles() } returns emptyList()
        },
        settings: SettingsRepository = settings(),
        backupManager: AutoBackupManager = mockk(relaxed = true),
        schedule: AutoBackupSchedulePort = FakeAutoBackupSchedule(),
    ) = DataControlController(
        conversations = conversations,
        memory = memory,
        skills = skills,
        settings = settings,
        backupManager = backupManager,
        backupSchedule = schedule,
        scope = this,
        ioDispatcher = StandardTestDispatcher(testScheduler),
    )

    private fun settings(
        backupPeriodHours: Int = 24,
        deletePeriodHours: Int = 168,
    ): SettingsRepository = mockk<SettingsRepository>().also { settings ->
        every { settings.autoBackupPeriodHours } returns MutableStateFlow(backupPeriodHours)
        every { settings.autoDeletePeriodHours } returns MutableStateFlow(deletePeriodHours)
        coEvery { settings.saveAutoBackupEnabled(any()) } returns Unit
        coEvery { settings.saveAutoBackupPeriodHours(any()) } returns Unit
        coEvery { settings.saveAutoBackupCategories(any()) } returns Unit
        coEvery { settings.saveAutoBackupDirectory(any()) } returns Unit
        coEvery { settings.saveAutoDeleteEnabled(any()) } returns Unit
        coEvery { settings.saveAutoDeletePeriodHours(any()) } returns Unit
    }

    private class FakeAutoBackupSchedule : AutoBackupSchedulePort {
        var scheduleCount = 0
        var cancelCount = 0

        override fun schedule() {
            scheduleCount++
        }

        override fun cancel() {
            cancelCount++
        }
    }
}
