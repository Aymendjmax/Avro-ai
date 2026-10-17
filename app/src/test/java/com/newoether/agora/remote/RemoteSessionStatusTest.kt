package com.newoether.agora.remote

import io.mockk.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
class RemoteSessionStatusTest {
    private val dispatcher = StandardTestDispatcher()
    private val client = mockk<FiloClient>()
    private val store = mockk<RemoteConnectionStore>(relaxed = true)
    private val address = "http://computer/"
    private val session = RemoteSession("session", "Existing", "/workspace", 1)
    private var status = RemoteSessionStatus("session", "active", activeTurnId = "turn")
    private fun page() = RemoteSessionPage(listOf(session), null, listOf(status))
    @Before fun setup() {
        Dispatchers.setMain(dispatcher)
        coEvery { store.load() } returns listOf(RemoteConnection("Computer", address, "token"))
        every { client.address } returns address
        coEvery { client.connect() } returns "Computer"
        coEvery { client.sessions(any()) } answers { page() }
        coEvery { client.models() } returns emptyList()
        every { client.events(any()) } returns flow { awaitCancellation() }
    }
    @After fun tearDown() { Dispatchers.resetMain() }
    private fun TestScope.open(): RemoteViewModel {
        val vm = RemoteViewModel(store, projectionDispatcher = dispatcher) { _, _ -> client }; runCurrent()
        vm.setVisible(true); vm.selectDevice(address); runCurrent()
        return vm
    }
    @Test fun firstListContainsExactStatusWithoutAFollowupRequestAndHiddenPageStopsPolling() = runTest(dispatcher) {
        val vm = open()
        assertEquals(status, vm.state.value.sessionStatuses["$address/session"])
        vm.observeSessions(address, listOf("session", "session", "unknown")); runCurrent()
        coVerify(exactly = 1) { client.sessions(any()) }
        advanceTimeBy(2999); runCurrent()
        coVerify(exactly = 1) { client.sessions(any()) }
        advanceTimeBy(1); runCurrent()
        coVerify(exactly = 2) { client.sessions(null) }
        vm.setVisible(false); advanceTimeBy(9000); runCurrent()
        coVerify(exactly = 2) { client.sessions(any()) }
    }
    @Test fun completedUnviewedGenerationStaysUnreadUntilVisibleHistoryIsRead() = runTest(dispatcher) {
        val vm = open(); vm.observeSessions(address, listOf("session")); runCurrent()
        assertFalse(vm.state.value.hasUnreadGeneration("session"))
        status = RemoteSessionStatus("session", "idle", completedTurnId = "turn")
        advanceTimeBy(3000); runCurrent()
        assertTrue(vm.state.value.hasUnreadGeneration("session"))
        advanceTimeBy(3000); runCurrent()
        assertTrue(vm.state.value.hasUnreadGeneration("session"))
        vm.selectSession(session); runCurrent()
        coVerify(exactly = 0) { store.markViewed(any(), any(), any()) }
        every { client.events(any()) } returns flow {
            emit(bodyPage(emptyList(), null, emptyList(), RemoteRuntime("idle", completedTurnId = "turn")))
            awaitCancellation()
        }
        vm.refresh(); runCurrent()
        assertFalse(vm.state.value.hasUnreadGeneration("session"))
        coVerify(exactly = 1) { store.markViewed(address, "session", "turn") }
        vm.refresh(); runCurrent()
        coVerify(exactly = 1) { store.markViewed(address, "session", "turn") }
        vm.selectSession(null)
        assertEquals("idle", vm.state.value.sessionStatuses["$address/session"]?.status)
        vm.setVisible(false)
    }
    @Test fun restoredViewedTurnSuppressesOnlyThatCompletion() = runTest(dispatcher) {
        coEvery { store.load() } returns listOf(RemoteConnection("Computer", address, "token", mapOf("session" to "old")))
        status = RemoteSessionStatus("session", "idle", completedTurnId = "old", hasUnreadTurn = true)
        val vm = open(); vm.observeSessions(address, listOf("session")); runCurrent()
        assertFalse(vm.state.value.hasUnreadGeneration("session"))
        status = status.copy(completedTurnId = "new")
        advanceTimeBy(3000); runCurrent()
        assertTrue(vm.state.value.hasUnreadGeneration("session"))
        vm.setVisible(false)
    }
    @Test fun sameSessionIdOnTwoDevicesKeepsIndependentStatus() = runTest(dispatcher) {
        val otherAddress = "http://second/"
        val otherClient = mockk<FiloClient>()
        every { otherClient.address } returns otherAddress
        coEvery { otherClient.connect() } returns "Second"
        coEvery { otherClient.sessions(any()) } returns RemoteSessionPage(
            listOf(session), null, listOf(RemoteSessionStatus("session", "idle", completedTurnId = "second-turn")),
        )
        coEvery { otherClient.models() } returns emptyList()
        coEvery { store.load() } returns listOf(RemoteConnection("Computer", address, "token"),
            RemoteConnection("Second", otherAddress, "token"))
        val vm = RemoteViewModel(store, projectionDispatcher = dispatcher) { url, _ ->
            if (url == address) client else otherClient
        }
        runCurrent(); vm.setVisible(true)
        vm.selectDevice(address); runCurrent()
        vm.selectDevice(otherAddress); runCurrent()
        assertEquals("turn", vm.state.value.sessionStatuses["$address/session"]?.activeTurnId)
        assertEquals("second-turn", vm.state.value.sessionStatuses["$otherAddress/session"]?.completedTurnId)
        vm.setVisible(false)
    }

    @Test fun lateRefreshCannotRepopulateAnotherSelection() = runTest(dispatcher) {
        val vm = open(); vm.observeSessions(address, listOf("session")); runCurrent()
        val gate = CompletableDeferred<RemoteSessionPage>()
        coEvery { client.sessions(any()) } coAnswers { withContext(NonCancellable) { gate.await() } }
        advanceTimeBy(3000); runCurrent()
        vm.selectDevice(null); status = status.copy(status = "idle", activeTurnId = null)
        gate.complete(page()); runCurrent()
        assertNull(vm.state.value.deviceId)
        assertEquals("active", vm.state.value.sessionStatuses["$address/session"]?.status)
        vm.setVisible(false)
    }
    @Test fun listPaginationCarriesExactStateAndPollsOnlyTheVisiblePage() = runTest(dispatcher) {
        coEvery { client.sessions(null) } returns page().copy(nextCursor = "next")
        val other = session.copy(id = "other", listCursor = "next")
        val next = RemoteSessionPage(listOf(other), null, listOf(RemoteSessionStatus("other", "idle", completedTurnId = "done")))
        coEvery { client.sessions("next") } returns next
        val vm = open(); vm.loadMore(); runCurrent()
        assertEquals(listOf("session", "other"), vm.state.value.sessions.map { it.id })
        assertEquals("done", vm.state.value.sessionStatuses["$address/other"]?.completedTurnId)
        vm.observeSessions(address, listOf("other")); runCurrent()
        coVerify(exactly = 1) { client.sessions(null) }
        coVerify(exactly = 1) { client.sessions("next") }
        advanceTimeBy(3000); runCurrent()
        coVerify(exactly = 1) { client.sessions(null) }
        coVerify(exactly = 2) { client.sessions("next") }
        vm.loadMore(); runCurrent()
        coVerify(exactly = 2) { client.sessions("next") }
        vm.setVisible(false)
    }
    @Test fun repeatedCursorIsRejectedAndPendingPaginationIsNotDuplicated() = runTest(dispatcher) {
        coEvery { client.sessions(null) } returns page().copy(nextCursor = "next")
        val gate = CompletableDeferred<RemoteSessionPage>()
        coEvery { client.sessions("next") } coAnswers { gate.await() }
        val vm = open(); vm.loadMore(); vm.loadMore(); runCurrent()
        coVerify(exactly = 1) { client.sessions("next") }
        gate.complete(RemoteSessionPage(listOf(session.copy(id = "other")), "next")); runCurrent()
        assertTrue(vm.state.value.error); assertEquals(listOf(session), vm.state.value.sessions)
        assertFalse(vm.state.value.loadingMore); vm.setVisible(false)
    }
    @Test fun unknownAndFailedReadsKeepCachedStatusAndNavigationDoesNotEraseIt() = runTest(dispatcher) {
        val vm = open(); vm.observeSessions(address, listOf("session")); runCurrent()
        status = RemoteSessionStatus("session")
        advanceTimeBy(3000); runCurrent()
        assertEquals("active", vm.state.value.sessionStatuses["$address/session"]?.status)
        coEvery { client.sessions(any()) } throws IOException("offline")
        advanceTimeBy(3000); runCurrent()
        assertEquals("active", vm.state.value.sessionStatuses["$address/session"]?.status)
        assertFalse(vm.state.value.error); assertEquals(listOf(session), vm.state.value.sessions)
        vm.selectDevice(null)
        assertEquals("active", vm.state.value.sessionStatuses["$address/session"]?.status)
        vm.removeDevice(address); runCurrent()
        assertNull(vm.state.value.sessionStatuses["$address/session"])
        vm.setVisible(false)
    }
}
