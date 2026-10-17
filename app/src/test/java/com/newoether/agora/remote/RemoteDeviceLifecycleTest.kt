package com.newoether.agora.remote

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
internal class RemoteDeviceLifecycleTest : RemoteViewModelFixture() {
    @Test fun savedNamesRenderBeforeNetworkAndSurviveOfflineReentry() = runTest(dispatcher) {
        val network = CompletableDeferred<String>()
        coEvery { connections.load() } returns listOf(RemoteConnection("Quantum-Work", "http://computer/", "token"))
        coEvery { client.connect() } coAnswers { network.await() }
        val vm = RemoteViewModel(connections, projectionDispatcher = dispatcher) { _, _ -> client }
        vm.setVisible(true); runCurrent()
        assertEquals("Quantum-Work", vm.state.value.devices.single().name)
        assertFalse(vm.state.value.restoring)
        assertFalse(vm.state.value.loading)
        assertEquals(RemoteDeviceStatus.CONNECTING, vm.state.value.devices.single().status)
        coVerify(exactly = 0) { client.sessions(any()) }
        vm.setVisible(false)
        network.completeExceptionally(IOException("offline")); runCurrent()
        vm.setVisible(true); runCurrent()
        assertEquals("Quantum-Work", vm.state.value.devices.single().name)
        assertFalse(vm.state.value.loading)
        assertEquals(RemoteDeviceStatus.ERROR, vm.state.value.devices.single().status)
        vm.setVisible(false)
    }

    @Test fun configuredNameIsSavedAndHostnameNeverReplacesIt() = runTest(dispatcher) {
        coEvery { client.connect() } returns "HOSTNAME"
        val vm = RemoteViewModel(connections, projectionDispatcher = dispatcher) { _, _ -> client }; runCurrent()
        vm.saveDevice("http://computer/", "token", "  My work computer  "); runCurrent()
        assertEquals("My work computer", vm.state.value.devices.single().name)
        coVerify { connections.save(match { it.name == "My work computer" }, null) }
        vm.editDevice("http://computer/")
        assertEquals("My work computer", vm.editorConnection()?.name)
        vm.saveDevice("http://computer/", "token", "Renamed"); runCurrent()
        assertEquals("Renamed", vm.state.value.devices.single().name)
        coVerify { connections.save(match { it.name == "Renamed" }, "http://computer/") }
    }

    @Test fun legacyUrlAndUnnamedDeviceNeverDisplayNetworkHostname() = runTest(dispatcher) {
        coEvery { connections.load() } returns listOf(RemoteConnection("http://computer/", "http://computer/", "token"))
        val vm = RemoteViewModel(connections, projectionDispatcher = dispatcher) { _, _ -> client }
        vm.setVisible(true); runCurrent()
        assertEquals("", vm.state.value.devices.single().name)
        assertEquals(RemoteDeviceStatus.CONNECTED, vm.state.value.devices.single().status)
        vm.editDevice("http://computer/")
        vm.saveDevice("http://computer/", "token", ""); runCurrent()
        assertEquals("", vm.state.value.devices.single().name)
        vm.setVisible(false)
    }

    @Test fun loadFailureProducesRetryableNoticeWithoutRemovingDeviceAndStaleRetryIsIgnored() = runTest(dispatcher) {
        val vm = RemoteViewModel(connections, projectionDispatcher = dispatcher) { _, _ -> client }; runCurrent()
        saveAndSelect(vm)
        coEvery { client.sessions(any()) } throws IOException("offline")
        vm.setVisible(true); runCurrent()
        val notice = vm.notices.first()
        assertEquals("read_failed", notice.stage)
        assertTrue(notice.canRetryRead)
        assertEquals(1, vm.state.value.devices.size)
        coEvery { client.sessions(any()) } returns RemoteSessionPage(listOf(session), null)
        vm.retryNotice(notice); runCurrent()
        assertEquals(listOf(session), vm.state.value.sessions)
        assertNull(vm.state.value.failure)
        vm.selectDevice(null); runCurrent()
        vm.retryNotice(notice); runCurrent()
        coVerify(exactly = 2) { client.sessions(any()) }
        coVerify(exactly = 0) { client.send(any(), any(), any(), any()) }
        vm.setVisible(false)
    }

    @Test fun leavingAnUnsubmittedDeviceEditorDoesNotConnectOrSave() = runTest(dispatcher) {
        val vm = RemoteViewModel(connections, projectionDispatcher = dispatcher) { _, _ -> client }; runCurrent()
        vm.addDevice()
        assertTrue(vm.state.value.addingDevice)
        assertNull(vm.state.value.deviceId)
        vm.selectDevice(null); runCurrent()
        assertFalse(vm.state.value.addingDevice)
        coVerify(exactly = 0) { client.connect() }
        coVerify(exactly = 0) { connections.save(any(), any()) }
        coVerify(exactly = 0) { client.sessions(any()) }
    }

    @Test fun savedOfflineDeviceReturnsToListWithConnectionFailureOnItsRow() = runTest(dispatcher) {
        val saveGate = CompletableDeferred<Unit>()
        coEvery { connections.save(any(), any()) } coAnswers { saveGate.await() }
        coEvery { client.connect() } throws FiloHttpException(401)
        val vm = RemoteViewModel(connections, projectionDispatcher = dispatcher) { _, _ -> client }; runCurrent()
        vm.setVisible(true)
        vm.addDevice(); vm.saveDevice("http://computer/", "token"); runCurrent()
        assertTrue(vm.state.value.addingDevice)
        assertTrue(vm.state.value.devices.isEmpty())
        coVerify(exactly = 0) { client.connect() }
        saveGate.complete(Unit); runCurrent()
        assertFalse(vm.state.value.addingDevice)
        assertNull(vm.state.value.failure)
        assertNull(vm.state.value.deviceId)
        assertEquals(RemoteFailure.AUTHENTICATION, vm.state.value.devices.single().failure)
        assertEquals(RemoteDeviceStatus.ERROR, vm.state.value.devices.single().status)
        coVerify(exactly = 1) { connections.save(any(), null) }
        coVerify(exactly = 0) { client.sessions(any()) }
        coEvery { client.connect() } returns "Computer"
        vm.selectDevice("http://computer/"); runCurrent()
        assertEquals(listOf(session), vm.state.value.sessions)
        vm.setVisible(false)
    }

    @Test fun saveCompletedAfterBackDoesNotTakeNavigationAndDuplicateSaveIsIgnored() = runTest(dispatcher) {
        val gate = CompletableDeferred<Unit>()
        coEvery { connections.save(any(), any()) } coAnswers { gate.await() }
        val vm = RemoteViewModel(connections, projectionDispatcher = dispatcher) { _, _ -> client }; runCurrent()
        vm.setVisible(true)
        vm.addDevice(); vm.saveDevice("http://computer/", "token"); runCurrent()
        vm.selectDevice(null)
        vm.addDevice(); vm.saveDevice("http://computer/", "token"); runCurrent()
        assertFalse(vm.state.value.addingDevice)
        gate.complete(Unit); runCurrent()
        assertFalse(vm.state.value.saving)
        assertFalse(vm.state.value.addingDevice)
        assertNull(vm.state.value.deviceId)
        assertEquals(1, vm.state.value.devices.size)
        coVerify(exactly = 1) { client.connect() }
        coVerify(exactly = 1) { connections.save(any(), null) }
        coVerify(exactly = 0) { client.sessions(any()) }
        vm.setVisible(false)
    }

    @Test fun backgroundingDuringCheckKeepsSavedDeviceWithoutOpeningHistory() = runTest(dispatcher) {
        val gate = CompletableDeferred<String>()
        coEvery { client.connect() } coAnswers { gate.await() }
        val vm = RemoteViewModel(connections, projectionDispatcher = dispatcher) { _, _ -> client }; runCurrent()
        vm.setVisible(true)
        vm.addDevice(); vm.saveDevice("http://computer/", "token"); runCurrent()
        assertEquals(RemoteDeviceStatus.CONNECTING, vm.state.value.devices.single().status)
        vm.refresh(); runCurrent()
        coVerify(exactly = 1) { client.connect() }
        vm.setVisible(false)
        gate.complete("Computer"); runCurrent()
        assertFalse(vm.state.value.addingDevice)
        assertNull(vm.state.value.deviceId)
        assertEquals(RemoteDeviceStatus.CONNECTED, vm.state.value.devices.single().status)
        coVerify(exactly = 0) { client.sessions(any()) }
        vm.setVisible(true); runCurrent()
        coVerify(exactly = 0) { client.sessions(any()) }
        vm.setVisible(false)
    }

    @Test fun lateConnectionFailureDoesNotReplaceAnEditorOrDiscardTheDevice() = runTest(dispatcher) {
        val gate = CompletableDeferred<String>()
        coEvery { client.connect() } coAnswers { gate.await() }
        val vm = RemoteViewModel(connections, projectionDispatcher = dispatcher) { _, _ -> client }; runCurrent()
        vm.addDevice(); vm.saveDevice("http://computer/", "token"); runCurrent()
        vm.editDevice("http://computer/")
        gate.completeExceptionally(IOException("Offline")); runCurrent()
        assertTrue(vm.state.value.addingDevice)
        assertFalse(vm.state.value.saving)
        assertNull(vm.state.value.failure)
        assertEquals("token", vm.editorConnection()?.token)
        assertEquals(RemoteFailure.NETWORK, vm.state.value.devices.single().failure)
        coVerify(exactly = 1) { connections.save(any(), null) }
    }

    @Test fun editReplacesAddressOnlyAfterCommitAndRejectsLateOldCheck() = runTest(dispatcher) {
        val oldCheck = CompletableDeferred<String>()
        val saveGate = CompletableDeferred<Unit>()
        coEvery { client.connect() } coAnswers { withContext(NonCancellable) { oldCheck.await() } }
        val replacement = mockk<FiloClient>()
        every { replacement.address } returns "http://new/"
        coEvery { replacement.connect() } returns "New computer"
        val vm = RemoteViewModel(connections, projectionDispatcher = dispatcher) { address, _ -> if (address == "http://new/") replacement else client }
        runCurrent()
        vm.saveDevice("http://computer/", "original"); runCurrent()
        vm.editDevice("http://computer/")
        assertEquals("original", vm.editorConnection()?.token)
        vm.selectDevice(null)
        vm.editDevice("http://computer/")
        coVerify(exactly = 1) { connections.save(any(), any()) }
        coEvery { connections.save(any(), "http://computer/") } throws RemoteStorageException()
        vm.saveDevice("http://new/", "changed"); runCurrent()
        assertTrue(vm.state.value.storageError)
        assertEquals("original", vm.editorConnection()?.token)
        coEvery { connections.save(any(), "http://computer/") } coAnswers { saveGate.await() }
        vm.saveDevice("http://new/", "changed"); runCurrent()
        assertEquals("http://computer/", vm.state.value.devices.single().id)
        saveGate.complete(Unit); runCurrent()
        oldCheck.complete("Stale computer"); runCurrent()
        assertEquals("http://new/", vm.state.value.devices.single().id)
        assertEquals("", vm.state.value.devices.single().name)
        vm.editDevice("http://new/")
        assertEquals("changed", vm.editorConnection()?.token)
    }

    @Test fun removedDeviceCannotBeRevivedByLateConnectionCheck() = runTest(dispatcher) {
        val check = CompletableDeferred<String>()
        coEvery { client.connect() } coAnswers { withContext(NonCancellable) { check.await() } }
        val vm = RemoteViewModel(connections, projectionDispatcher = dispatcher) { _, _ -> client }; runCurrent()
        vm.saveDevice("http://computer/", "token"); runCurrent()
        vm.removeDevice("http://computer/"); runCurrent()
        check.complete("Deleted computer"); runCurrent()
        assertTrue(vm.state.value.devices.isEmpty())
        assertNull(vm.state.value.deviceId)
    }

    @Test fun selectingWhileCheckingWaitsForProtocolValidationAndCoalescesRequests() = runTest(dispatcher) {
        val gate = CompletableDeferred<String>()
        coEvery { client.connect() } coAnswers { gate.await() }
        val vm = RemoteViewModel(connections, projectionDispatcher = dispatcher) { _, _ -> client }; runCurrent()
        vm.setVisible(true)
        vm.saveDevice("http://computer/", "token"); runCurrent()
        vm.selectDevice("http://computer/"); vm.refresh(); runCurrent()
        assertTrue(vm.state.value.loading)
        coVerify(exactly = 1) { client.connect() }
        coVerify(exactly = 0) { client.sessions(any()) }
        gate.complete("Computer"); runCurrent()
        assertEquals(listOf(session), vm.state.value.sessions)
        coVerify(exactly = 1) { client.sessions(any()) }
        vm.setVisible(false)
    }

    @Test fun editingTokenAtSameAddressRejectsTheReplacedClientsLateResult() = runTest(dispatcher) {
        val gate = CompletableDeferred<String>()
        coEvery { client.connect() } coAnswers { withContext(NonCancellable) { gate.await() } }
        val replacement = mockk<FiloClient>()
        every { replacement.address } returns "http://computer/"
        coEvery { replacement.connect() } returns "Current computer"
        val vm = RemoteViewModel(connections, projectionDispatcher = dispatcher) { _, token -> if (token == "changed") replacement else client }
        runCurrent()
        vm.saveDevice("http://computer/", "original"); runCurrent()
        vm.editDevice("http://computer/")
        vm.saveDevice("http://computer/", "changed"); runCurrent()
        gate.completeExceptionally(IOException("Old token rejected")); runCurrent()
        assertEquals(RemoteDeviceStatus.CONNECTED, vm.state.value.devices.single().status)
        assertEquals("", vm.state.value.devices.single().name)
        assertNull(vm.state.value.devices.single().failure)
    }

    @Test fun restoredDevicesSurviveReadFailureWithoutSendingOrOpeningHistory() = runTest(dispatcher) {
        coEvery { connections.load() } returns listOf(RemoteConnection("Computer", "http://computer/", "token"))
        val vm = RemoteViewModel(connections, projectionDispatcher = dispatcher) { _, _ -> client }; runCurrent()
        assertEquals(1, vm.state.value.devices.size)
        assertNull(vm.state.value.deviceId)
        assertFalse(vm.state.value.restoring)
        coVerify(exactly = 0) { client.connect() }
        coVerify(exactly = 0) { client.sessions(any()) }
        coVerify(exactly = 0) { client.conversation(any(), any()) }
        coVerify(exactly = 0) { client.send(any(), any(), any(), any()) }
        coEvery { client.sessions(any()) } throws IOException("Offline")
        vm.setVisible(true); vm.selectDevice("http://computer/"); runCurrent()
        assertTrue(vm.state.value.error)
        assertEquals(1, vm.state.value.devices.size)
        coVerify(exactly = 0) { connections.remove(any()) }
        vm.setVisible(false)
    }

    @Test fun failingPersistenceDoesNotPublishAnUnsavedConnection() = runTest(dispatcher) {
        coEvery { connections.save(any(), any()) } throws RemoteStorageException()
        val vm = RemoteViewModel(connections, projectionDispatcher = dispatcher) { _, _ -> client }; runCurrent()
        vm.addDevice()
        vm.saveDevice("http://computer/", "token"); runCurrent()
        assertTrue(vm.state.value.storageError)
        assertTrue(vm.state.value.addingDevice)
        assertFalse(vm.state.value.saving)
        assertTrue(vm.state.value.devices.isEmpty())
        coVerify(exactly = 0) { client.connect() }
    }

    @Test fun removalPublishesOnlyAfterCommitAndFencesPendingSends() = runTest(dispatcher) {
        val removeGate = CompletableDeferred<Unit>()
        val sendGate = CompletableDeferred<String>()
        coEvery { connections.remove(any()) } coAnswers { removeGate.await() }
        coEvery { client.send(any(), any(), any(), any()) } coAnswers { sendGate.await() }
        val vm = RemoteViewModel(connections, projectionDispatcher = dispatcher) { _, _ -> client }; runCurrent()
        saveAndSelect(vm)
        vm.selectSession(session); vm.setVisible(true); runCurrent()
        vm.editDraft(vm.state.value.owner!!, "Once"); vm.send(); runCurrent()
        vm.removeDevice("http://computer/"); runCurrent()
        assertEquals(1, vm.state.value.devices.size)
        removeGate.complete(Unit); runCurrent()
        sendGate.complete("queue"); runCurrent()
        assertTrue(vm.state.value.devices.isEmpty())
        assertTrue(vm.state.value.attempts.isEmpty())
        assertTrue(vm.state.value.drafts.isEmpty())
        assertNull(vm.state.value.deviceId)
        coVerify(exactly = 1) { client.send(any(), any(), any(), any()) }
        vm.setVisible(false)
    }

    @Test fun failedRemovalKeepsTheSavedDeviceAndRuntimeClient() = runTest(dispatcher) {
        coEvery { connections.remove(any()) } throws RemoteStorageException()
        val vm = RemoteViewModel(connections, projectionDispatcher = dispatcher) { _, _ -> client }; runCurrent()
        saveAndSelect(vm)
        vm.removeDevice("http://computer/"); runCurrent()
        assertEquals(1, vm.state.value.devices.size)
        assertTrue(vm.state.value.storageError)
        assertFalse(vm.state.value.saving)
        vm.setVisible(true); vm.selectDevice("http://computer/"); runCurrent()
        assertEquals(listOf(session), vm.state.value.sessions)
        vm.setVisible(false)
    }
}
