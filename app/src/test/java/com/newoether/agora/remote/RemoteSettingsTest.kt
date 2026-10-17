package com.newoether.agora.remote

import io.mockk.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
class RemoteSettingsTest {
    private val dispatcher = StandardTestDispatcher()
    private val client = mockk<FiloClient>()
    private val store = mockk<RemoteConnectionStore>(relaxed = true)
    private val session = RemoteSession("session", "Existing", "/workspace", 1)
    private val model = RemoteModel("model", "Model", true, listOf("low", "high", "ultra"), "high",
        listOf(RemoteServiceTier("priority", "Fast")), null)
    private var runtime = RemoteRuntime("active", "turn", "model", effort = "high", serviceTier = "default")

    @Before fun setup() {
        Dispatchers.setMain(dispatcher)
        coEvery { store.load() } returns emptyList()
        every { client.address } returns "http://computer/"
        coEvery { client.connect() } returns "Computer"
        coEvery { client.sessions(any()) } returns RemoteSessionPage(listOf(session), null)
        coEvery { client.models() } returns listOf(model)
        coEvery { client.conversation(any(), any()) } answers { bodyPage(emptyList(), null, emptyList(), runtime) }
        every { client.events(any()) } answers {
            flow { emit(client.conversation(firstArg())); awaitCancellation() }
        }
        coEvery { client.updateSettings(any(), any()) } coAnswers {
            val settings = secondArg<RemoteSettings>()
            runtime = runtime.copy(model = settings.model ?: runtime.model, effort = settings.effort ?: runtime.effort,
                serviceTier = if (settings.updateServiceTier) settings.serviceTier ?: "default" else runtime.serviceTier)
        }
    }
    @After fun teardown() { Dispatchers.resetMain() }

    private fun TestScope.open(draft: Boolean = false): RemoteViewModel {
        val vm = RemoteViewModel(store) { _, _ -> client }; runCurrent()
        vm.saveDevice("http://computer/", "token"); runCurrent()
        vm.selectDevice("http://computer/"); vm.setVisible(true); runCurrent()
        if (draft) vm.newSession() else { vm.selectSession(session); runCurrent() }
        return vm
    }

    @Test fun settingsWirePreservesOmissionAndExplicitClear() {
        assertEquals("{\"effort\":\"ultra\"}", RemoteSettings(effort = "ultra").body())
        assertEquals("{\"serviceTier\":null}", RemoteSettings(updateServiceTier = true).body())
        assertEquals(RemoteSettings("model", "ultra", null, true),
            RemoteSettings("model", "high", "priority", true).merge(RemoteSettings(effort = "ultra", updateServiceTier = true)))
    }

    @Test fun draftSettingsStayLocalUntilOneCreateCarriesThemWithTheFirstMessage() = runTest(dispatcher) {
        coEvery { client.create(any(), any(), any(), any()) } returns session
        coEvery { client.send(any(), any(), any(), any()) } returns "turn"
        val vm = open(draft = true)
        vm.setThinkingLevel("ultra"); vm.setServiceTierEnabled(true); vm.refresh(); runCurrent()
        assertEquals("ultra", vm.state.value.selectedEffort)
        assertEquals("priority", vm.state.value.selectedServiceTier)
        coVerify(exactly = 0) { client.updateSettings(any(), any()) }
        coVerify(exactly = 0) { client.create(any(), any(), any(), any()) }
        val owner = vm.state.value.owner!!
        vm.editDraft(owner, "hello"); vm.send(); vm.send(); runCurrent()
        coVerify(exactly = 1) {
            client.create("hello", any(), emptyList(), RemoteSettings("model", "ultra", "priority", true))
        }
        coVerify(exactly = 0) { client.updateSettings(any(), any()) }
        coVerify(exactly = 0) { client.send(any(), any(), any(), any()) }
        assertEquals(owner, vm.state.value.owner)
        assertFalse(vm.state.value.isDraft)
        vm.setVisible(false)
    }

    @Test fun draftWithoutExplicitChoicesStillCarriesTheNativeDefaultsInOneCreate() = runTest(dispatcher) {
        coEvery { client.create(any(), any(), any(), any()) } returns session
        coEvery { client.send(any(), any(), any(), any()) } returns "turn"
        val vm = open(draft = true)
        val owner = vm.state.value.owner!!
        vm.editDraft(owner, "hello"); vm.send(); runCurrent()
        coVerify(exactly = 1) {
            client.create("hello", any(), emptyList(), RemoteSettings("model", "high", null, true))
        }
        coVerify(exactly = 0) { client.updateSettings(any(), any()) }
        coVerify(exactly = 0) { client.send(any(), any(), any(), any()) }
        vm.setVisible(false)
    }

    @Test fun refusedCreationKeepsTheDraftRetryableAndNothingIsEverSentWithoutAFirstMessage() = runTest(dispatcher) {
        coEvery { client.create(any(), any(), any(), any()) } throws FiloHttpException(400, detail = "drafted settings rejected")
        coEvery { client.send(any(), any(), any(), any()) } returns "turn"
        val vm = open(draft = true)
        val owner = vm.state.value.owner!!
        vm.setThinkingLevel("ultra"); runCurrent()
        vm.editDraft(owner, "hello"); vm.send(); runCurrent()
        assertTrue(vm.state.value.isDraft)
        assertEquals(RemoteDelivery.REJECTED, vm.state.value.attempts[owner]?.delivery)
        coVerify(exactly = 0) { client.send(any(), any(), any(), any()) }
        coEvery { client.create(any(), any(), any(), any()) } returns session
        vm.send(); runCurrent()
        coVerify(exactly = 2) { client.create(any(), any(), any(), any()) }
        coVerify(exactly = 0) { client.send(any(), any(), any(), any()) }
        assertFalse(vm.state.value.isDraft)
        assertEquals(RemoteDelivery.ACCEPTED, vm.state.value.attempts[owner]?.delivery)
        vm.setVisible(false)
    }

    @Test fun nativeSettingsReadBackWithoutChangingGenerationAndRejectUnsupportedChoices() = runTest(dispatcher) {
        val vm = open()
        vm.setThinkingEnabled(false); vm.setThinkingLevel("minimal"); vm.setServiceTier("flex"); runCurrent()
        coVerify(exactly = 0) { client.updateSettings(any(), any()) }
        vm.setThinkingLevel("ultra"); runCurrent()
        assertEquals("ultra", vm.state.value.selectedEffort)
        assertTrue(vm.state.value.runtime!!.isRunning)
        assertEquals("turn", vm.state.value.runtime?.activeTurnId)
        vm.setServiceTierEnabled(true); runCurrent()
        assertEquals("priority", vm.state.value.selectedServiceTier)
        vm.setServiceTierEnabled(false); runCurrent()
        assertNull(vm.state.value.selectedServiceTier)
        coVerify(exactly = 1) { client.updateSettings("session", RemoteSettings(updateServiceTier = true)) }
        coVerify(exactly = 0) { client.send(any(), any(), any(), any()) }
        vm.setVisible(false)
    }

    @Test fun lateControlCompletionCannotOverwriteOrUnlockNewSessionControl() = runTest(dispatcher) {
        val old = CompletableDeferred<Unit>(); val current = CompletableDeferred<Unit>()
        coEvery { client.updateSettings("session", any()) } coAnswers { old.await() }
        coEvery { client.updateSettings("second", any()) } coAnswers { current.await() }
        val vm = open()
        vm.setThinkingLevel("ultra"); runCurrent()
        vm.selectSession(session.copy(id = "second")); runCurrent()
        vm.setThinkingLevel("low"); runCurrent()
        assertTrue(vm.state.value.controlling)
        old.complete(Unit); runCurrent()
        assertTrue(vm.state.value.controlling)
        assertEquals("second", vm.state.value.session?.id)
        current.complete(Unit); runCurrent()
        assertFalse(vm.state.value.controlling)
        vm.setVisible(false)
    }

    @Test fun failedSettingSettlesPanelRevisionAndKeepsAuthoritativeValue() = runTest(dispatcher) {
        val vm = open()
        val revision = vm.state.value.settingsRevision
        coEvery { client.updateSettings(any(), any()) } throws IOException("unconfirmed settings")
        vm.setThinkingLevel("ultra"); runCurrent()
        assertEquals("high", vm.state.value.selectedEffort)
        assertEquals(revision + 1, vm.state.value.settingsRevision)
        assertFalse(vm.state.value.controlling)
        assertTrue(vm.state.value.error)
        vm.setVisible(false)
    }

    @Test fun ultraFastIsAppliedOnceOnlyWhenTheNativeModelOffersIt() = runTest(dispatcher) {
        coEvery { client.models() } returns listOf(model.copy(
            serviceTiers = model.serviceTiers.orEmpty() + RemoteServiceTier("ultrafast", "Ultrafast"),
        ))
        val vm = open()
        vm.setServiceTier("ultrafast"); runCurrent()
        assertEquals("ultrafast", vm.state.value.selectedServiceTier)
        coVerify(exactly = 1) {
            client.updateSettings("session", RemoteSettings(serviceTier = "ultrafast", updateServiceTier = true))
        }
        assertTrue(vm.state.value.runtime!!.isRunning)
        coVerify(exactly = 0) { client.send(any(), any(), any(), any()) }
        vm.setVisible(false)
    }

    @Test fun changingModelResetsOnlyIncompatibleOptions() {
        val state = RemoteState(session = session, runtime = runtime.copy(effort = "ultra", serviceTier = "priority"), models = listOf(model))
        assertEquals(RemoteSettings("small", "low", null, true),
            state.settingsForModel(RemoteModel("small", "Small", reasoningEfforts = listOf("low"),
                defaultReasoningEffort = "low", serviceTiers = emptyList())))
    }
}
