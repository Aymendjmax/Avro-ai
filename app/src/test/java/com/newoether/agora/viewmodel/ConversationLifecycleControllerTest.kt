package com.newoether.agora.viewmodel

import com.newoether.agora.data.repository.ConversationRepository
import com.newoether.agora.automation.ConversationExecutionCoordinator
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class ConversationLifecycleControllerTest {
    @Test
    fun admittedDeletionHoldsTheCoordinatorUntilStorageCompletes() = runTest {
        val fixture = Fixture(this, controllerScope = backgroundScope)
        val releaseDeletion = CompletableDeferred<Unit>()
        coEvery { fixture.conversations.deleteConversation("conversation") } coAnswers {
            fixture.events += "delete-start"
            releaseDeletion.await()
            fixture.events += "delete-end"
        }
        fixture.controller.delete(fixture.origin, "conversation")
        runCurrent()
        backgroundScope.launch {
            fixture.executionCoordinator.withAutomationConversationLock("conversation") {
                fixture.events += "generation"
            }
        }
        runCurrent()
        assertTrue("delete-start" in fixture.events)
        assertTrue("generation" !in fixture.events)

        releaseDeletion.complete(Unit)
        runCurrent()

        assertTrue(fixture.events.indexOf("generation") > fixture.events.indexOf("delete-end"))
        coVerify(exactly = 1) { fixture.conversations.deleteConversation("conversation") }
    }

    @Test
    fun staleConfirmationIsRejectedBeforeStoppingGenerationOrDeletingStorage() = runTest {
        val fixture = Fixture(this)
        coEvery { fixture.conversations.getMessageTopologySnapshot("conversation") } returns emptyList()
        val results = mutableListOf<Boolean>()

        fixture.controller.delete(fixture.origin, "conversation", setOf("confirmed-message"), results::add)
        runCurrent()

        assertEquals(listOf(false), results)
        assertTrue("abort:7" in fixture.events)
        assertTrue("stop" !in fixture.events && "stop-loop" !in fixture.events)
        coVerify(exactly = 0) { fixture.conversations.deleteConversation(any()) }
    }

    @Test
    fun generationStartedBeforeDeleteAdmissionIsRejectedWithoutWaitingForItsCompletion() = runTest {
        val fixture = Fixture(this, controllerScope = backgroundScope)
        val releaseGeneration = CompletableDeferred<Unit>()
        val generation = backgroundScope.launch {
            fixture.executionCoordinator.withAutomationConversationLock("conversation") {
                releaseGeneration.await()
            }
        }
        runCurrent()
        val results = mutableListOf<Boolean>()

        assertTrue(fixture.controller.delete(fixture.origin, "conversation", onResult = results::add))
        runCurrent()

        assertEquals(listOf(false), results)
        assertTrue(generation.isActive)
        assertTrue("abort:7" in fixture.events)
        assertTrue("stop" !in fixture.events && "stop-loop" !in fixture.events)
        coVerify(exactly = 0) { fixture.conversations.deleteConversation(any()) }
        releaseGeneration.complete(Unit)
    }

    @Test
    fun cancelledDeletionCompletesItsFailureCallbackEvenWhileTheScopeIsCancelled() = runTest {
        val controllerJob = SupervisorJob()
        val controllerScope = CoroutineScope(StandardTestDispatcher(testScheduler) + controllerJob)
        val fixture = Fixture(this, controllerScope = controllerScope)
        fixture.onOverlay = { CompletableDeferred<Unit>().await() }
        val results = mutableListOf<Boolean>()
        fixture.controller.delete(fixture.origin, "conversation", onResult = results::add)
        runCurrent()

        controllerJob.cancel()
        runCurrent()

        assertEquals(listOf(false), results)
        assertTrue("abort:null" in fixture.events)
        coVerify(exactly = 0) { fixture.conversations.deleteConversation(any()) }
    }

    @Test
    fun renamePersistsTheExactTitle() = runTest {
        val fixture = Fixture(this)
        coEvery { fixture.conversations.updateConversationTitle(any(), any()) } returns true

        fixture.controller.rename("conversation", "New title")
        runCurrent()

        coVerify(exactly = 1) {
            fixture.conversations.updateConversationTitle("conversation", "New title")
        }
    }

    @Test
    fun visibleDeletionPreservesStopLockCleanupAndSelectionSettlementOrder() = runTest {
        val fixture = Fixture(this)
        coEvery { fixture.conversations.deleteConversation("conversation") } answers {
            fixture.events += "delete"
        }

        fixture.controller.delete(fixture.origin, "conversation")
        assertTrue(fixture.events.isEmpty())
        runCurrent()

        assertEquals(
            listOf(
                "overlay:conversation",
                "lock-start",
                "stop",
                "stop-loop",
                "delete",
                "lock-end",
                "remove",
                "settle:conversation",
            ),
            fixture.events,
        )
    }

    @Test
    fun deletionDoesNotStopOrReplaceAnotherVisibleConversation() = runTest {
        val fixture = Fixture(this, currentConversationId = "other")
        coEvery { fixture.conversations.deleteConversation("conversation") } answers {
            fixture.events += "delete"
        }

        fixture.controller.delete(fixture.origin, "conversation")
        runCurrent()

        assertEquals(listOf("lock-start", "stop-loop", "delete", "lock-end", "remove"), fixture.events)
        assertTrue("stop" !in fixture.events)
        assertTrue(fixture.events.none { it.startsWith("settle:") })
    }

    @Test
    fun selectionChangeBeforeFinalLockDoesNotStopTheNewVisibleConversation() = runTest {
        val fixture = Fixture(this)
        coEvery { fixture.conversations.deleteConversation("conversation") } answers {
            fixture.events += "delete"
        }
        fixture.onLockStart = { fixture.origin.open = "other" }

        fixture.controller.delete(fixture.origin, "conversation")
        runCurrent()

        assertTrue("stop" !in fixture.events)
        assertTrue(fixture.events.none { it.startsWith("settle:") })
        assertTrue("delete" in fixture.events)
    }

    @Test
    fun lifecycleSettlesTheOriginallySelectedDeletionAfterCleanup() = runTest {
        val fixture = Fixture(this)
        coEvery { fixture.conversations.deleteConversation("conversation") } answers {
            fixture.events += "delete"
        }
        fixture.onRemove = { fixture.origin.open = "other" }

        fixture.controller.delete(fixture.origin, "conversation")
        runCurrent()

        assertTrue("stop" in fixture.events)
        assertEquals("settle:conversation", fixture.events.last())
    }

    @Test
    fun frozenSubmissionRejectsDeletionBeforeAnySideEffect() = runTest {
        val fixture = Fixture(this, deleteLocked = true)

        assertFalse(fixture.controller.delete(fixture.origin, "conversation"))
        runCurrent()

        assertTrue(fixture.events.isEmpty())
        coVerify(exactly = 0) { fixture.conversations.deleteConversation(any()) }
    }

    @Test
    fun failedDurableDeletionDoesNotCleanupOrSettleSelection() = runTest {
        val failures = mutableListOf<Throwable>()
        val controllerScope = CoroutineScope(
            SupervisorJob() +
                StandardTestDispatcher(testScheduler) +
                CoroutineExceptionHandler { _, error -> failures += error },
        )
        val fixture = Fixture(this, controllerScope = controllerScope)
        coEvery { fixture.conversations.deleteConversation("conversation") } throws
            IllegalStateException("delete failed")

        fixture.controller.delete(fixture.origin, "conversation")
        runCurrent()

        assertTrue(failures.isEmpty())
        assertTrue("remove" !in fixture.events)
        assertTrue(fixture.events.none { it.startsWith("settle:") })
        assertTrue("abort:7" in fixture.events)
    }

    @Test
    fun otherClientsShowingTheDeletedConversationAreToldAndLeaveIt() = runTest {
        val fixture = Fixture(this)
        val viewer = FakeChatClient(open = "conversation").apply {
            onSettleDeleted = { conversationId -> fixture.events += "viewer-settle:$conversationId" }
        }
        val elsewhere = FakeChatClient(open = "other")
        fixture.clients.attach(viewer)
        fixture.clients.attach(elsewhere)
        coEvery { fixture.conversations.deleteConversation("conversation") } answers {
            fixture.events += "delete"
        }

        fixture.controller.delete(fixture.origin, "conversation")
        runCurrent()

        assertEquals(listOf("deleted elsewhere"), viewer.snackbars)
        assertTrue("viewer-settle:conversation" in fixture.events)
        assertTrue("settle:conversation" in fixture.events)
        assertTrue(fixture.origin.snackbars.isEmpty())
        assertTrue(elsewhere.snackbars.isEmpty())
    }

    @Test
    fun anotherClientSubmittingIntoTheConversationLocksDeletion() = runTest {
        val fixture = Fixture(this)
        fixture.clients.attach(FakeChatClient(open = "conversation").apply { frozen = true })

        assertFalse(fixture.controller.delete(fixture.origin, "conversation"))
        runCurrent()

        assertTrue(fixture.events.isEmpty())
        coVerify(exactly = 0) { fixture.conversations.deleteConversation(any()) }
    }

    private class Fixture(
        testScope: kotlinx.coroutines.test.TestScope,
        currentConversationId: String? = "conversation",
        controllerScope: CoroutineScope = testScope,
        deleteLocked: Boolean = false,
    ) {
        val conversations = mockk<ConversationRepository>()
        val executionCoordinator = ConversationExecutionCoordinator()
        val events = mutableListOf<String>()
        var onRemove: () -> Unit = {}
        var onLockStart: () -> Unit = {}
        var onOverlay: suspend () -> Unit = {}
        val origin = FakeChatClient(open = currentConversationId).apply {
            frozen = deleteLocked
            onBeginTreeMutation = { conversationId, _ ->
                events += "overlay:$conversationId"
                onOverlay()
                7L
            }
            onFailTreeMutation = { requestId -> events += "abort:$requestId" }
            onSettleDeleted = { conversationId -> events += "settle:$conversationId" }
        }
        val clients = ChatClients().also { it.attach(origin) }
        private val dispatcher = StandardTestDispatcher(testScope.testScheduler)
        val controller = ConversationLifecycleController(
            conversations = conversations,
            scope = controllerScope,
            clients = clients,
            stopLoop = { events += "stop-loop" },
            tryWithConversationLock = { id, block ->
                executionCoordinator.tryWithConversationLock(id) {
                    events += "lock-start"
                    onLockStart()
                    block()
                    events += "lock-end"
                }
            },
            removeRuntime = {
                events += "remove"
                onRemove()
            },
            stopGeneration = { _, _ -> events += "stop" },
            deletedElsewhereText = { "deleted elsewhere" },
            ioDispatcher = dispatcher,
            mainDispatcher = dispatcher,
        )
    }
}