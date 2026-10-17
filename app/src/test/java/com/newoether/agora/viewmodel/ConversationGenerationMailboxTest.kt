package com.newoether.agora.viewmodel

import com.newoether.agora.model.ProviderPassResult
import com.newoether.agora.model.RunEffect
import com.newoether.agora.model.RunEffectIdentity
import com.newoether.agora.model.RunEndReason
import com.newoether.agora.model.RunStatus
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ConversationGenerationMailboxTest {
    @Test
    fun toolBatchAndCommitResultsAreSerializedByConversationMailbox() = runBlocking {
        val state = ConversationGenerationState("conversation", reclaimQueuedAttachments = {})
        val token = state.acquireForSend()!!
        state.bindRun(token, "run", pass = 2)
        val providerIdentity = RunEffectIdentity(
            conversationId = "conversation",
            ownerToken = token,
            runId = "run",
            pass = 2,
            effectId = "provider-2-0",
        )

        val batch = state.commands.requestToolBatch(providerIdentity)!!
        val commit = state.commands.completeToolBatch(batch.identity)!!
        val continuation = state.commands.finishToolRoundCommit(commit.identity, success = true)

        assertEquals(RunEffect.ContinueProviderPass(commit.identity), continuation)
        assertEquals(
            listOf("ToolBatchRequested", "ToolBatchCompleted", "ToolRoundCommitted"),
            state.runtimeTraceSnapshot().takeLast(3).map { it.commandType },
        )
        assertEquals(
            listOf("ExecutingTools", "CommittingToolRound", "Active"),
            state.runtimeTraceSnapshot().takeLast(3).map { it.newState },
        )
        assertTrue(finalizeBoundRun(state, token, "run", pass = 2))
    }

    @Test
    fun providerPassCallbacksRejectStaleAndDuplicateResults() = runBlocking {
        val state = ConversationGenerationState("conversation", reclaimQueuedAttachments = {})
        val token = state.acquireForSend()!!
        state.bindRun(token, "run", pass = 2)
        val identity = RunEffectIdentity(
            conversationId = "conversation",
            ownerToken = token,
            runId = "run",
            pass = 2,
            effectId = "provider-2-0",
        )

        assertEquals(identity, state.commands.requestProviderPass(identity)?.identity)
        assertNull(
            state.commands.finishProviderPass(
                identity.copy(effectId = "provider-2-old"),
                ProviderPassResult.COMPLETED_TEXT,
            ),
        )
        assertEquals(
            RunEffect.ProviderPassAccepted(identity, ProviderPassResult.COMPLETED_TEXT),
            state.commands.finishProviderPass(identity, ProviderPassResult.COMPLETED_TEXT),
        )
        assertNull(state.commands.finishProviderPass(identity, ProviderPassResult.COMPLETED_TEXT))
        assertTrue(finalizeBoundRun(state, token, "run", pass = 2))
    }

    @Test
    fun normalFinalizationWaitsForBothBarriersBeforeReleasing() = runBlocking {
        val state = ConversationGenerationState("conversation", reclaimQueuedAttachments = {})
        val token = state.acquireForSend()!!
        state.bindRun(token, "run")
        val unwind = CompletableDeferred<Unit>()
        val released = CompletableDeferred<Unit>()
        state.onQueueDrainRequested = { released.complete(Unit) }
        val job = checkNotNull(state.launchGenerationJob(token) { unwind.await() })
        val identity = RunEffectIdentity(
            conversationId = "conversation",
            ownerToken = token,
            runId = "run",
            pass = 0,
            effectId = "finalize-run-0",
        )
        val effect = state.commands.requestRunFinalization(
            identity,
            RunStatus.COMPLETED,
            RunEndReason.MODEL_COMPLETED,
            markConversationUnread = true,
        )

        assertEquals(identity, effect?.identity)
        assertEquals(
            ConversationGenerationState.RunFinalizationOutcome.RECORDED,
            state.finishRunFinalization(identity, success = true),
        )
        assertTrue(state.generating.value)
        unwind.complete(Unit)
        job.join()
        released.await()
        assertFalse(state.generating.value)
    }

    @Test
    fun failedNormalFinalizationKeepsSlotUntilStopRecoverySettles() = runBlocking {
        val state = ConversationGenerationState("conversation", reclaimQueuedAttachments = {})
        val token = state.acquireForSend()!!
        state.bindRun(token, "run")
        val unwind = CompletableDeferred<Unit>()
        val job = checkNotNull(state.launchGenerationJob(token) { unwind.await() })
        val identity = RunEffectIdentity(
            conversationId = "conversation",
            ownerToken = token,
            runId = "run",
            pass = 0,
            effectId = "finalize-run-0",
        )
        state.commands.requestRunFinalization(
            identity,
            RunStatus.FAILED,
            RunEndReason.PROVIDER_ERROR,
            markConversationUnread = true,
        )

        assertEquals(
            ConversationGenerationState.RunFinalizationOutcome.FAILED,
            state.finishRunFinalization(identity, success = false),
        )
        unwind.complete(Unit)
        job.join()
        assertTrue(state.generating.value)

        val stopped = state.stop()
        assertTrue(stopped.finalizationEffect != null)
        assertEquals(
            ConversationGenerationState.StopFinalizationOutcome.SETTLED,
            state.finishStopFinalization(stopped.completion(success = true)),
        )
        assertFalse(state.generating.value)
    }
}
