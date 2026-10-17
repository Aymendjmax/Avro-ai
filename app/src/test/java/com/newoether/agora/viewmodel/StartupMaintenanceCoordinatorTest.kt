package com.newoether.agora.viewmodel

import com.newoether.agora.data.repository.SettingsRepository
import com.newoether.agora.util.UpdateInfo
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class StartupMaintenanceCoordinatorTest {
    @Test
    fun updateCheckAtExactDailyBoundaryIsNotDue() = runTest {
        val fixture = Fixture(this, now = DAY_MS + 1L)
        coEvery { fixture.settings.getAutoUpdateCheck() } returns true
        coEvery { fixture.settings.getLastUpdateCheckTime() } returns 1L

        fixture.coordinator.start()
        runCurrent()

        coVerify(exactly = 0) { fixture.settings.saveLastUpdateCheckTime(any()) }
        assertTrue(fixture.checkedVersions.isEmpty())
        assertEquals(listOf("backup", "semantic"), fixture.events)
    }

    @Test
    fun dueUpdatePersistsTimestampBeforeNetworkAndPublishesResult() = runTest {
        val fixture = Fixture(this, now = DAY_MS + 2L)
        val update = UpdateInfo("3.0", "https://example.invalid", "notes")
        coEvery { fixture.settings.getAutoUpdateCheck() } returns true
        coEvery { fixture.settings.getLastUpdateCheckTime() } returns 1L
        coEvery { fixture.settings.saveLastUpdateCheckTime(DAY_MS + 2L) } answers {
            fixture.events += "timestamp"
        }
        fixture.updateResult = update

        fixture.coordinator.start()
        runCurrent()

        assertEquals(listOf("backup", "semantic", "timestamp", "check"), fixture.events)
        assertEquals(listOf("current"), fixture.checkedVersions)
        assertEquals(listOf(update), fixture.updates)
    }

    private class Fixture(
        testScope: kotlinx.coroutines.test.TestScope,
        now: Long = 0L,
    ) {
        val settings = mockk<SettingsRepository>()
        val events = mutableListOf<String>()
        val checkedVersions = mutableListOf<String>()
        val updates = mutableListOf<UpdateInfo>()
        var updateResult: UpdateInfo? = null
        private val dispatcher = StandardTestDispatcher(testScope.testScheduler)
        val coordinator = StartupMaintenanceCoordinator(
            settings = settings,
            scope = testScope,
            currentVersion = { "current" },
            checkUpdate = { version ->
                events += "check"
                checkedVersions += version
                updateResult
            },
            onUpdateFound = updates::add,
            startAutoBackup = { events += "backup" },
            startSemanticIndex = { events += "semantic" },
            now = { now },
            ioDispatcher = dispatcher,
        )

        init {
            coEvery { settings.getAutoUpdateCheck() } returns false
        }
    }

    private companion object {
        const val DAY_MS = 24 * 60 * 60 * 1000L
    }
}
