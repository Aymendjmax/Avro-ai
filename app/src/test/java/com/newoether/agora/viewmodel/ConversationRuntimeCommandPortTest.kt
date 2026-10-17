package com.newoether.agora.viewmodel

import com.newoether.agora.model.ConversationRuntimeReducer
import com.newoether.agora.model.ProviderPassResult
import com.newoether.agora.model.RunEffect
import com.newoether.agora.model.RunEffectIdentity
import com.newoether.agora.model.RunState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ConversationRuntimeCommandPortTest {
    @Test
    fun sendAndProviderResultsRemainIdentifiedAndMailboxApplied() = runBlocking {
        val harness = Harness()
        try {
            val requested = harness.port.requestSend(
                proposedRunId = "run",
                effectId = "input",
                directOnly = true,
                hasPendingGuidance = false,
            )
            val input = requested.effects.filterIsInstance<RunEffect.PersistAcceptedInput>().single()
            assertEquals(effectIdentity("input"), input.identity)

            assertTrue(harness.port.finishInputPersistence(input.identity).newState is RunState.Active)

            val providerIdentity = effectIdentity("provider")
            assertEquals(
                providerIdentity,
                harness.port.requestProviderPass(providerIdentity)?.identity,
            )
            assertEquals(
                providerIdentity,
                harness.port.finishProviderPass(
                    providerIdentity,
                    ProviderPassResult.COMPLETED_TEXT,
                )?.identity,
            )
            assertNull(
                harness.port.finishProviderPass(
                    providerIdentity,
                    ProviderPassResult.COMPLETED_TEXT,
                ),
            )
        } finally {
            harness.close()
        }
    }

    private class Harness {
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        var state: RunState = RunState.Idle(CONVERSATION_ID)
            private set

        private val mailbox = ConversationCommandMailbox(scope) { factory ->
            ConversationRuntimeReducer.reduce(state, factory.create()).also { transition ->
                if (transition.accepted) state = transition.newState
            }
        }

        val port = ConversationRuntimeCommandPort(
            conversationId = CONVERSATION_ID,
            mailbox = mailbox,
            nextOwnerToken = { OWNER_TOKEN },
        )

        fun close() {
            scope.cancel()
        }

    }

    private fun effectIdentity(effectId: String) = RunEffectIdentity(
        conversationId = CONVERSATION_ID,
        ownerToken = OWNER_TOKEN,
        runId = "run",
        pass = 0,
        effectId = effectId,
    )

    private companion object {
        const val CONVERSATION_ID = "conversation"
        const val OWNER_TOKEN = 1L
    }
}
