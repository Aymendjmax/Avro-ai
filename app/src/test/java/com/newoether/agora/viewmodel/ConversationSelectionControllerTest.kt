package com.newoether.agora.viewmodel

import com.newoether.agora.api.DebugProvider
import com.newoether.agora.data.local.ChatEntity
import com.newoether.agora.data.repository.ConversationRepository
import com.newoether.agora.model.ChatMessage
import com.newoether.agora.model.MessageSegment
import com.newoether.agora.model.MessageStatus
import com.newoether.agora.model.Participant
import com.newoether.agora.util.DebugLog
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.verify
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.runCurrent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ConversationSelectionControllerTest {
    @Test
    fun cancelledMutationFadeReleasesItsOwnOverlay() = runTest {
        val fixture = Fixture(backgroundScope, fadeDelay = { CompletableDeferred<Unit>().await() })
        fixture.controller.publishAcceptedConversation("conversation", "provider:model")
        val mutation = backgroundScope.launch { fixture.controller.beginTreeMutation() }
        runCurrent()
        assertTrue(fixture.controller.isSwitching.value)

        mutation.cancel()
        runCurrent()

        assertFalse(fixture.controller.isSwitching.value)
        assertEquals("conversation", fixture.controller.currentConversationId.value)
    }

    @Test
    fun cancelledMutationFadeCannotReleaseANewerSelectionOverlay() = runTest {
        val fixture = Fixture(backgroundScope, fadeDelay = { CompletableDeferred<Unit>().await() })
        fixture.controller.publishAcceptedConversation("conversation", "provider:model")
        val mutation = backgroundScope.launch { fixture.controller.beginTreeMutation() }
        runCurrent()
        fixture.controller.createNewChat()

        mutation.cancel()
        runCurrent()

        assertTrue(fixture.controller.isSwitching.value)
        assertTrue(fixture.controller.isNewChatMode.value)
    }

    @Test
    fun selectPublishesConversationAndModelBeforeUiReadiness() = runTest {
        val fixture = Fixture(backgroundScope)
        coEvery { fixture.conversations.getConversation("conversation") } returns
            ChatEntity("conversation", "Title", modelId = "provider:model")

        fixture.controller.selectConversation("conversation", hapticOnCompletion = false)
        runCurrent()

        assertEquals("conversation", fixture.controller.currentConversationId.value)
        assertEquals("provider:model", fixture.controller.currentActiveModel.value)
        assertFalse(fixture.controller.isNewChatMode.value)
        val request = fixture.controller.switchingScrollRequest.value
        assertEquals(SwitchingRequestKind.CONVERSATION, request?.kind)
        assertTrue(request?.readyForUi == true)
        assertFalse(request?.hapticOnCompletion ?: true)
        assertTrue(fixture.controller.completeSwitchingScroll(checkNotNull(request).id))

        fixture.controller.selectConversation("conversation")
        assertNull(fixture.controller.switchingScrollRequest.value)
        coVerify(exactly = 1) { fixture.conversations.getConversation("conversation") }
    }

    @Test
    fun forcedOriginRestoreSupersedesPendingHistoryEvenWhenOriginIsStillPublished() = runTest {
        val historyGate = CompletableDeferred<Unit>()
        val restoreGate = CompletableDeferred<Unit>()
        var fadeInvocation = 0
        val fixture = Fixture(
            backgroundScope,
            fadeDelay = {
                if (fadeInvocation++ == 0) historyGate.await() else restoreGate.await()
            },
        )
        fixture.controller.publishAcceptedConversation("origin", "current-model")
        coEvery { fixture.conversations.getConversation("origin") } returns
            ChatEntity("origin", "Origin", modelId = "current-model")

        fixture.controller.selectConversation("history")
        runCurrent()
        fixture.controller.restoreConversationDestination("origin")
        runCurrent()
        historyGate.complete(Unit)
        runCurrent()

        assertEquals("origin", fixture.controller.currentConversationId.value)
        coVerify(exactly = 0) { fixture.conversations.getConversation("history") }

        restoreGate.complete(Unit)
        runCurrent()
        val request = checkNotNull(fixture.controller.switchingScrollRequest.value)
        assertEquals("origin", request.conversationId)
        assertTrue(request.readyForUi)
        assertFalse(request.hapticOnCompletion)
        assertTrue(fixture.controller.completeSwitchingScroll(request.id))
        coVerify(exactly = 1) { fixture.conversations.getConversation("origin") }
    }

    @Test
    fun forcedNewChatRestoreCancelsPendingHistoryWhileAlreadyInNewChat() = runTest {
        val historyGate = CompletableDeferred<Unit>()
        val restoreGate = CompletableDeferred<Unit>()
        var fadeInvocation = 0
        val fixture = Fixture(
            backgroundScope,
            fadeDelay = {
                if (fadeInvocation++ == 0) historyGate.await() else restoreGate.await()
            },
        )

        fixture.controller.selectConversation("history")
        runCurrent()
        fixture.controller.restoreNewChatDestination()
        runCurrent()
        historyGate.complete(Unit)
        runCurrent()

        assertTrue(fixture.controller.isNewChatMode.value)
        assertNull(fixture.controller.currentConversationId.value)
        coVerify(exactly = 0) { fixture.conversations.getConversation("history") }

        restoreGate.complete(Unit)
        runCurrent()
        assertFalse(fixture.controller.isSwitching.value)
        assertTrue(fixture.controller.isNewChatMode.value)
        assertNull(fixture.controller.currentConversationId.value)
    }

    @Test
    fun acceptedNewChatSelectsOnlyTheExactStillOccupiedEntry() = runTest {
        val fixture = Fixture(backgroundScope)
        val entryId = fixture.controller.newChatEntryId.value

        assertTrue(
            fixture.controller.publishAcceptedConversationIfOriginStillOpen(
                "accepted",
                "provider:model",
                entryId,
            ),
        )
        assertEquals("accepted", fixture.controller.currentConversationId.value)
        assertFalse(fixture.controller.isNewChatMode.value)
    }

    @Test
    fun pendingConversationSelectionRejectsBackgroundNewChatFocusSteal() = runTest {
        val fadeGate = CompletableDeferred<Unit>()
        val fixture = Fixture(backgroundScope, fadeDelay = { fadeGate.await() })
        coEvery { fixture.conversations.getConversation("other") } returns
            ChatEntity("other", "Other", modelId = "other-model")
        val entryId = fixture.controller.newChatEntryId.value

        fixture.controller.selectConversation("other")
        assertFalse(
            fixture.controller.publishAcceptedConversationIfOriginStillOpen(
                "background",
                "provider:model",
                entryId,
            ),
        )
        assertNull(fixture.controller.currentConversationId.value)
        assertTrue(fixture.controller.isNewChatMode.value)

        fadeGate.complete(Unit)
        runCurrent()
        assertEquals("other", fixture.controller.currentConversationId.value)
    }

    @Test
    fun activeModelWritesAlwaysUseTheWorkspaceBoundary() = runTest {
        val fixture = Fixture(backgroundScope)

        fixture.controller.setActiveModel("new-chat-model")
        verify(exactly = 1) {
            fixture.workspaces.setModel(NEW_CHAT_WORKSPACE_ID, "new-chat-model")
        }

        fixture.controller.publishAcceptedConversation("conversation", "provider:model")
        fixture.controller.setActiveModel("conversation-model")
        verify(exactly = 1) {
            fixture.workspaces.setModel("conversation", "conversation-model")
        }
        coVerify(exactly = 0) { fixture.conversations.upsertConversation(any()) }
    }

    @Test
    fun debugModelVisibilityRequiresBothDeveloperGates() {
        val ordinary = linkedSetOf("provider:model")
        val staleDebugSelection = ordinary + DebugProvider.MODEL_ID

        assertEquals(ordinary, validChatModels(ordinary, false, false))
        assertEquals(ordinary, validChatModels(ordinary, true, false))
        assertEquals(ordinary, validChatModels(ordinary, false, true))
        assertEquals(ordinary, validChatModels(staleDebugSelection, true, false))
        assertEquals(ordinary, validChatModels(staleDebugSelection, false, true))
        assertEquals(
            ordinary + DebugProvider.MODEL_ID,
            validChatModels(ordinary, true, true),
        )
    }

    @Test
    fun disablingDebugFallsBackAndPersistsTheNewChatModel() = runTest {
        val fixture = Fixture(backgroundScope)
        fixture.validModels.value = setOf("default-model", DebugProvider.MODEL_ID)
        fixture.newChatModelId.value = DebugProvider.MODEL_ID
        runCurrent()
        assertEquals(DebugProvider.MODEL_ID, fixture.controller.currentActiveModel.value)

        fixture.validModels.value = setOf("default-model")
        runCurrent()

        assertEquals("default-model", fixture.controller.currentActiveModel.value)
        verify(exactly = 1) {
            fixture.workspaces.setModel(NEW_CHAT_WORKSPACE_ID, "default-model")
        }
    }

    @Test
    fun removedConversationModelFallsBackAndPersistsThroughTheWorkspace() = runTest {
        val fixture = Fixture(backgroundScope)
        fixture.validModels.value = fixture.validModels.value.orEmpty() + "removed-model"
        fixture.controller.publishAcceptedConversation("conversation", "removed-model")
        runCurrent()
        assertEquals("removed-model", fixture.controller.currentActiveModel.value)

        fixture.validModels.value = fixture.validModels.value.orEmpty() - "removed-model"
        runCurrent()

        assertEquals("default-model", fixture.controller.currentActiveModel.value)
        verify(exactly = 1) {
            fixture.workspaces.setModel("conversation", "default-model")
        }
    }

    @Test
    fun missingValidDefaultClearsTheConversationModelAndBlocksGeneration() = runTest {
        val fixture = Fixture(backgroundScope)
        fixture.validModels.value = setOf("stale-model")
        fixture.controller.publishAcceptedConversation("conversation", "stale-model")
        runCurrent()
        assertEquals("stale-model", fixture.controller.currentActiveModel.value)

        fixture.defaultModel.value = "missing-default"
        fixture.validModels.value = emptySet()
        runCurrent()

        assertEquals("", fixture.controller.currentActiveModel.value)
        verify(exactly = 1) {
            fixture.workspaces.setModel("conversation", null)
        }
    }

    @Test
    fun explicitNewChatSelectionSupersedesAStaleWorkspaceFallback() = runTest {
        val fixture = Fixture(backgroundScope)
        fixture.validModels.value = setOf("default-model", "selected-model")
        fixture.newChatModelId.value = "removed-model"
        fixture.controller.setActiveModel("selected-model")
        runCurrent()

        assertEquals("selected-model", fixture.controller.currentActiveModel.value)
        verify(exactly = 1) {
            fixture.workspaces.setModel(NEW_CHAT_WORKSPACE_ID, "selected-model")
        }
        verify(exactly = 0) {
            fixture.workspaces.setModel(NEW_CHAT_WORKSPACE_ID, "default-model")
        }
    }

    @Test
    fun acceptedConversationKeepsTheCapturedModelAfterNewChatStateClears() = runTest {
        val fixture = Fixture(backgroundScope)
        fixture.newChatModelId.value = "restored-model"
        runCurrent()
        assertEquals("restored-model", fixture.controller.currentActiveModel.value)
        fixture.controller.publishAcceptedConversation("conversation", "restored-model")
        fixture.newChatModelId.value = null
        runCurrent()
        assertFalse(fixture.controller.isNewChatMode.value)
        assertEquals("conversation", fixture.controller.currentConversationId.value)
        assertEquals("restored-model", fixture.controller.currentActiveModel.value)
    }

    @Test
    fun selectedRuntimeFollowsGeneratingAToIdleB() = runTest {
        val fixture = Fixture(backgroundScope)
        fixture.activateAnswer("a", "answer-a")
        fixture.controller.publishAcceptedConversation("a", "old-model")
        coEvery { fixture.conversations.getConversation("b") } returns
            ChatEntity("b", "B", modelId = "new-model")

        fixture.controller.selectConversation("b")
        runCurrent()

        assertEquals("b", fixture.controller.currentConversationId.value)
        assertEquals(
            ConversationGenerationSnapshot(),
            fixture.controller.selectedConversationGenerationSnapshot.value,
        )
    }

    @Test
    fun selectedRuntimeFollowsIdleAToGeneratingB() = runTest {
        val fixture = Fixture(backgroundScope)
        fixture.activateAnswer("b", "answer-b")
        fixture.controller.publishAcceptedConversation("a", "old-model")
        coEvery { fixture.conversations.getConversation("b") } returns
            ChatEntity("b", "B", modelId = "new-model")

        fixture.controller.selectConversation("b")
        runCurrent()

        val snapshot = fixture.controller.selectedConversationGenerationSnapshot.value
        assertEquals("b", fixture.controller.currentConversationId.value)
        assertEquals("b", snapshot.conversationId)
        assertEquals("answer-b", snapshot.streamingMessage?.id)
        assertTrue(snapshot.isGenerating)
    }

    @Test
    fun selectedRuntimeUsesBWhenBothConversationsAreGenerating() = runTest {
        val fixture = Fixture(backgroundScope)
        fixture.activateAnswer("a", "answer-a")
        fixture.activateAnswer("b", "answer-b")
        fixture.controller.publishAcceptedConversation("a", "old-model")
        coEvery { fixture.conversations.getConversation("b") } returns
            ChatEntity("b", "B", modelId = "new-model")

        fixture.controller.selectConversation("b")
        runCurrent()

        assertEquals(
            "answer-b",
            fixture.controller.selectedConversationGenerationSnapshot.value.streamingMessage?.id,
        )
    }

    @Test
    fun rapidAToBToARestoresTheCurrentASnapshot() = runTest {
        val fixture = Fixture(backgroundScope)
        fixture.activateAnswer("a", "answer-a")
        fixture.activateAnswer("b", "answer-b")
        fixture.controller.publishAcceptedConversation("a", "old-model")
        coEvery { fixture.conversations.getConversation("a") } returns
            ChatEntity("a", "A", modelId = "old-model")
        coEvery { fixture.conversations.getConversation("b") } returns
            ChatEntity("b", "B", modelId = "new-model")

        fixture.controller.selectConversation("b")
        runCurrent()
        fixture.controller.selectConversation("a")
        runCurrent()

        val snapshot = fixture.controller.selectedConversationGenerationSnapshot.value
        assertEquals("a", fixture.controller.currentConversationId.value)
        assertEquals("a", snapshot.conversationId)
        assertEquals("answer-a", snapshot.streamingMessage?.id)
    }

    @Test
    fun delayedOldRuntimeEmissionCannotOverwriteTheCurrentBinding() = runTest {
        val fixture = Fixture(backgroundScope)
        val (stateA, tokenA) = fixture.activateAnswer("a", "answer-a")
        fixture.controller.publishAcceptedConversation("a", "old-model")
        runCurrent()
        stateA.streamUpdate(tokenA, fixture.answeringMessage("late-a"))
        fixture.activateAnswer("b", "answer-b")

        fixture.controller.publishAcceptedConversation("b", "new-model")
        runCurrent()

        val snapshot = fixture.controller.selectedConversationGenerationSnapshot.value
        assertEquals("b", fixture.controller.currentConversationId.value)
        assertEquals("b", snapshot.conversationId)
        assertEquals("answer-b", snapshot.streamingMessage?.id)
    }

    @Test
    fun publishAcceptedConversationSeedsTheRuntimeSnapshotSynchronously() = runTest {
        val fixture = Fixture(backgroundScope)
        val (state, _) = fixture.activateAnswer("accepted", "accepted-answer")

        fixture.controller.publishAcceptedConversation("accepted", "current-model")

        assertEquals("accepted", fixture.controller.currentConversationId.value)
        assertEquals(
            state.generationSnapshot.value,
            fixture.controller.selectedConversationGenerationSnapshot.value,
        )
    }

    @Test
    fun newChatClearsTheSelectedRuntimeWhenTheConversationIdClears() = runTest {
        val fadeGate = CompletableDeferred<Unit>()
        val fixture = Fixture(backgroundScope, fadeDelay = { fadeGate.await() })
        fixture.activateAnswer("conversation", "answer")
        fixture.controller.publishAcceptedConversation("conversation", "provider:model")

        fixture.controller.createNewChat()
        assertEquals(
            "answer",
            fixture.controller.selectedConversationGenerationSnapshot.value.streamingMessage?.id,
        )

        fadeGate.complete(Unit)
        runCurrent()

        assertNull(fixture.controller.currentConversationId.value)
        assertEquals(
            ConversationGenerationSnapshot(),
            fixture.controller.selectedConversationGenerationSnapshot.value,
        )
    }

    @Test
    fun staleConversationMutationCannotCoverTheCurrentConversation() = runTest {
        var fadeCount = 0
        val fixture = Fixture(backgroundScope, fadeDelay = { fadeCount += 1 })
        fixture.controller.publishAcceptedConversation("current", "provider:model")

        val requestId = fixture.controller.beginTreeMutation(
            conversationId = "stale",
            scrollToTarget = false,
        )

        assertNull(requestId)
        assertEquals(0, fadeCount)
        assertFalse(fixture.controller.isSwitching.value)
        assertEquals("current", fixture.controller.currentConversationId.value)
    }

    @Test
    fun deletedSelectedConversationEntersNewChatAfterSettlement() = runTest {
        val fixture = Fixture(backgroundScope)
        fixture.controller.publishAcceptedConversation("deleted", "old-model")

        fixture.controller.settleDeletedSelectedConversation("deleted")
        runCurrent()

        assertTrue(fixture.controller.isNewChatMode.value)
        assertNull(fixture.controller.currentConversationId.value)
        assertEquals(1, fixture.clearGraphCount)
    }

    @Test
    fun newerPendingConversationSelectionSupersedesDeletionSettlement() = runTest {
        val fadeGate = CompletableDeferred<Unit>()
        val fixture = Fixture(backgroundScope, fadeDelay = { fadeGate.await() })
        fixture.controller.publishAcceptedConversation("deleted", "old-model")
        coEvery { fixture.conversations.getConversation("new") } returns
            ChatEntity("new", "New", modelId = "new-model")

        fixture.controller.selectConversation("new")
        fixture.controller.settleDeletedSelectedConversation("deleted")
        fadeGate.complete(Unit)
        runCurrent()

        assertEquals("new", fixture.controller.currentConversationId.value)
        assertEquals("new-model", fixture.controller.currentActiveModel.value)
        assertFalse(fixture.controller.isNewChatMode.value)
        assertEquals(0, fixture.clearGraphCount)
    }

    @Test
    fun settlementDoesNotReplaceAnotherVisibleConversation() = runTest {
        val fixture = Fixture(backgroundScope)
        fixture.controller.publishAcceptedConversation("other", "current-model")

        fixture.controller.settleDeletedSelectedConversation("deleted")
        runCurrent()

        assertEquals("other", fixture.controller.currentConversationId.value)
        assertFalse(fixture.controller.isNewChatMode.value)
        assertEquals(0, fixture.clearGraphCount)
    }

    @Test
    fun newChatKeepsOldConversationUntilFadeThenClearsProjection() = runTest {
        val fadeGate = CompletableDeferred<Unit>()
        val fixture = Fixture(backgroundScope, fadeDelay = { fadeGate.await() })
        fixture.controller.publishAcceptedConversation("conversation", "provider:model")

        fixture.controller.createNewChat()

        assertTrue(fixture.controller.isNewChatMode.value)
        assertTrue(fixture.controller.isTransitioningToNewChat.value)
        assertEquals("conversation", fixture.controller.currentConversationId.value)
        assertEquals(2L, fixture.controller.newChatEntryId.value)

        fadeGate.complete(Unit)
        runCurrent()

        assertNull(fixture.controller.currentConversationId.value)
        assertFalse(fixture.controller.isTransitioningToNewChat.value)
        assertEquals(1, fixture.clearGraphCount)
        assertEquals(1, fixture.abortRegenerationCount)
    }

    @Test
    fun newerConversationSelectionSupersedesPendingNewChat() = runTest {
        val fadeGate = CompletableDeferred<Unit>()
        val fixture = Fixture(backgroundScope, fadeDelay = { fadeGate.await() })
        fixture.controller.publishAcceptedConversation("old", "old-model")
        coEvery { fixture.conversations.getConversation("new") } returns
            ChatEntity("new", "New", modelId = "new-model")

        fixture.controller.createNewChat()
        fixture.controller.selectConversation("new")
        fadeGate.complete(Unit)
        runCurrent()

        assertEquals("new", fixture.controller.currentConversationId.value)
        assertEquals("new-model", fixture.controller.currentActiveModel.value)
        assertFalse(fixture.controller.isNewChatMode.value)
        assertFalse(fixture.controller.isTransitioningToNewChat.value)
        assertEquals(0, fixture.clearGraphCount)
        assertEquals(SwitchingRequestKind.CONVERSATION, fixture.controller
            .switchingScrollRequest.value?.kind)
    }

    @Test
    fun missingTargetDoesNotReplaceTheCurrentConversation() = runTest {
        mockkObject(DebugLog)
        every { DebugLog.e(any(), any()) } returns Unit
        try {
            val fixture = Fixture(backgroundScope)
            fixture.controller.publishAcceptedConversation("current", "current-model")
            coEvery { fixture.conversations.getConversation("missing") } returns null

            var failures = 0
            fixture.controller.restoreConversationDestination("missing") { failures += 1 }
            runCurrent()

            assertEquals("current", fixture.controller.currentConversationId.value)
            assertFalse(fixture.controller.isNewChatMode.value)
            assertNull(fixture.controller.switchingScrollRequest.value)
            assertEquals(1, failures)
            coVerify(exactly = 1) { fixture.conversations.getConversation("missing") }
        } finally {
            unmockkObject(DebugLog)
        }
    }

    @Test
    fun projectionFailureOnlyReleasesItsMatchingSwitchRequest() = runTest {
        mockkObject(DebugLog)
        every { DebugLog.e(any(), any()) } returns Unit
        try {
            val fixture = Fixture(backgroundScope)
            coEvery { fixture.conversations.getConversation("conversation") } returns
                ChatEntity("conversation", "Title")

            var failures = 0
            fixture.controller.restoreConversationDestination("conversation") { failures += 1 }
            runCurrent()
            val request = checkNotNull(fixture.controller.switchingScrollRequest.value)

            fixture.controller.failConversationLoad("stale-conversation")
            assertEquals(request, fixture.controller.switchingScrollRequest.value)
            assertEquals(0, failures)

            fixture.controller.failConversationLoad("conversation")
            assertNull(fixture.controller.switchingScrollRequest.value)
            assertEquals("conversation", fixture.controller.currentConversationId.value)
            assertFalse(fixture.controller.isNewChatMode.value)
            assertEquals(1, failures)
        } finally {
            unmockkObject(DebugLog)
        }
    }

    @Test
    fun branchSelectionCommitsRoomBeforePublishingReadyTarget() = runTest {
        val fixture = Fixture(backgroundScope)
        fixture.controller.publishAcceptedConversation("conversation", "provider:model")
        fixture.renderStore.replaceGraph(
            allMessages = listOf(PARENT, FIRST_BRANCH, SECOND_BRANCH),
            selectedChildren = mapOf("parent" to "first"),
        )
        coEvery {
            fixture.conversations.selectRunBranch(
                conversationId = "conversation",
                parentRunId = "parent-run",
                runId = "second-run",
                messageSelections = mapOf("parent" to "second"),
            )
        } coAnswers {
            assertFalse(fixture.controller.switchingScrollRequest.value?.readyForUi ?: true)
        }

        fixture.controller.switchBranch("parent", "first", direction = 1)
        runCurrent()

        assertEquals("second", fixture.renderStore.selectedChildren["parent"])
        val request = fixture.controller.switchingScrollRequest.value
        assertEquals(SwitchingRequestKind.TREE_MUTATION, request?.kind)
        assertEquals("second", request?.targetMessageId)
        assertTrue(request?.readyForUi == true)
        assertEquals(listOf("conversation"), fixture.contextInvalidations)
        coVerify(exactly = 1) {
            fixture.conversations.selectRunBranch(
                "conversation",
                "parent-run",
                "second-run",
                mapOf("parent" to "second"),
            )
        }
        fixture.registry.remove("conversation")
    }

    @Test
    fun deleteMutationCompletionInvalidatesContextBeforePublishingReady() = runTest {
        val fixture = Fixture(backgroundScope)
        fixture.controller.publishAcceptedConversation("conversation", "provider:model")

        val requestId = fixture.controller.beginTreeMutation(scrollToTarget = false)

        assertTrue(fixture.contextInvalidations.isEmpty())
        fixture.controller.markTreeMutationReady(requestId, targetMessageId = null)

        assertEquals(listOf("conversation"), fixture.contextInvalidations)
        val request = fixture.controller.switchingScrollRequest.value
        assertTrue(request?.readyForUi == true)
        assertFalse(request?.scrollToTarget ?: true)
        assertTrue(fixture.controller.completeSwitchingScroll(checkNotNull(request).id))
    }

    @Test
    fun activeRunRejectsBranchMutationBeforeRoom() = runTest {
        val fixture = Fixture(backgroundScope)
        fixture.controller.publishAcceptedConversation("conversation", "provider:model")
        fixture.renderStore.replaceGraph(
            allMessages = listOf(PARENT, FIRST_BRANCH, SECOND_BRANCH),
            selectedChildren = mapOf("parent" to "first"),
        )
        val state = fixture.registry.getOrCreate("conversation")
        assertTrue(state.acquireForSend() != null)

        fixture.controller.switchBranch("parent", "first", direction = 1)
        runCurrent()

        assertEquals("first", fixture.renderStore.selectedChildren["parent"])
        assertNull(fixture.controller.switchingScrollRequest.value)
        coVerify(exactly = 0) {
            fixture.conversations.selectRunBranch(any(), any(), any(), any())
        }
        fixture.registry.remove("conversation")
    }

    @Test
    fun navigationAfterRestorePublicationRejectsLateCompletionAndReportsCancellationOnce() = runTest {
        val fixture = Fixture(backgroundScope)
        fixture.controller.publishAcceptedConversation("history", "current-model")
        coEvery { fixture.conversations.getConversation("origin") } returns ChatEntity("origin", "Origin")
        var failures = 0
        fixture.controller.restoreConversationDestination("origin") { failures += 1 }
        runCurrent()
        val oldRequest = requireNotNull(fixture.controller.switchingScrollRequest.value)
        assertEquals("origin", fixture.controller.currentConversationId.value)
        assertEquals(0, failures)

        fixture.controller.createNewChat()
        assertEquals(1, failures)
        assertFalse(fixture.controller.completeSwitchingScroll(oldRequest.id))
        runCurrent()
        assertTrue(fixture.controller.isNewChatMode.value)
        assertNull(fixture.controller.currentConversationId.value)
        assertEquals(1, failures)
    }

    @Test
    fun cancelledNewChatRestoreReportsFailureWithoutCancellingNewerSelection() = runTest {
        val fadeGate = CompletableDeferred<Unit>()
        val fixture = Fixture(backgroundScope, fadeDelay = { fadeGate.await() })
        var failures = 0
        fixture.controller.restoreNewChatDestination { failures += 1 }
        runCurrent()
        fixture.controller.selectConversation("newer")
        coEvery { fixture.conversations.getConversation("newer") } returns ChatEntity("newer", "Newer")
        fadeGate.complete(Unit)
        runCurrent()
        assertEquals("newer", fixture.controller.currentConversationId.value)
        assertTrue(fixture.controller.isSwitching.value)
        assertEquals(1, failures)
    }

    private class Fixture(
        scope: CoroutineScope,
        fadeDelay: suspend () -> Unit = {},
    ) {
        val conversations = mockk<ConversationRepository>()
        val registry = ConversationStateRegistry {}
        val renderStore = ConversationRenderStore()
        val defaultModel = MutableStateFlow("default-model")
        val validModels = MutableStateFlow<Set<String>?>(
            setOf(
                "default-model",
                "provider:model",
                "new-chat-model",
                "conversation-model",
                "restored-model",
                "old-model",
                "new-model",
                "current-model",
            ),
        )
        val newChatModelId = MutableStateFlow<String?>(null)
        val workspaces = mockk<ConversationWorkspaceStore>(relaxed = true).also {
            every { it.newChatModelId } returns newChatModelId
        }
        var clearGraphCount = 0
        var abortRegenerationCount = 0
        val contextInvalidations = mutableListOf<String>()
        fun answeringMessage(messageId: String) = ChatMessage(
            id = messageId,
            text = "answer",
            participant = Participant.MODEL,
            status = MessageStatus.SENDING,
            segments = listOf(MessageSegment(type = "answer", content = "answer")),
        )

        fun activateAnswer(
            conversationId: String,
            messageId: String,
        ): Pair<ConversationGenerationState, Long> {
            val state = registry.getOrCreate(conversationId)
            val token = requireNotNull(state.acquireForSend())
            state.loadingChange(token, true)
            state.streamUpdate(token, answeringMessage(messageId))
            return state to token
        }

        val controller = ConversationSelectionController(
            scope = scope,
            conversations = conversations,
            registry = registry,
            defaultModel = defaultModel,
            validModels = validModels,
            scrollRequests = ScrollRequestCoordinator(),
            renderStore = { renderStore },
            clearConversationGraph = { clearGraphCount += 1 },
            workspaces = workspaces,
            abortRegeneration = { abortRegenerationCount += 1 },
            onTreeMutationCommitted = contextInvalidations::add,
            fadeDelay = fadeDelay,
        )
    }

    private companion object {
        val PARENT = ChatMessage(
            id = "parent",
            text = "input",
            participant = Participant.USER,
            status = MessageStatus.SUCCESS,
            timestamp = 1L,
            runId = "parent-run",
        )
        val FIRST_BRANCH = ChatMessage(
            id = "first",
            parentId = "parent",
            text = "first",
            participant = Participant.MODEL,
            status = MessageStatus.SUCCESS,
            timestamp = 2L,
            runId = "first-run",
        )
        val SECOND_BRANCH = ChatMessage(
            id = "second",
            parentId = "parent",
            text = "second",
            participant = Participant.MODEL,
            status = MessageStatus.SUCCESS,
            timestamp = 3L,
            runId = "second-run",
        )
    }
}
