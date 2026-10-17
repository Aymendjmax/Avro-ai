package com.newoether.agora.viewmodel

import com.newoether.agora.api.LlmProvider
import com.newoether.agora.api.ProviderConfig
import com.newoether.agora.api.StreamEvent
import com.newoether.agora.model.ChatMessage
import com.newoether.agora.model.Participant
import com.newoether.agora.model.ProviderPassResult
import com.newoether.agora.model.RunEffect
import com.newoether.agora.model.RunEffectIdentity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test

class ProviderPassEffectExecutorTest {
    @Test
    fun `executes only the exact authorized identity and reports the first event once`() = runTest {
        val events = listOf<StreamEvent>(
            StreamEvent.TextChunk("one"),
            StreamEvent.TextChunk("two"),
        )
        val forwarded = mutableListOf<StreamEvent>()
        var firstEvents = 0

        val outcome = ProviderPassEffectExecutor().execute(
            request = request(fakeProvider(flowOf(*events.toTypedArray()))),
            callbacks = callbacks(
                requestEffect = { RunEffect.StartProviderPass(it) },
                onFirstEvent = { firstEvents++ },
                onEvent = forwarded::add,
            ),
        )

        assertEquals(ProviderPassOutcome.CompletedText(IDENTITY), outcome)
        assertEquals(events, forwarded)
        assertEquals(1, firstEvents)
    }

    @Test
    fun `rejects a stale authorization before opening the provider`() = runTest {
        var providerCalls = 0
        val provider = fakeProvider(flowOf(StreamEvent.TextChunk("must not run"))) {
            providerCalls++
        }

        try {
            ProviderPassEffectExecutor().execute(
                request = request(provider),
                callbacks = callbacks(
                    requestEffect = {
                        RunEffect.StartProviderPass(it.copy(effectId = "stale"))
                    },
                ),
            )
            fail("Expected stale authorization to cancel the pass")
        } catch (_: CancellationException) {
            assertEquals(0, providerCalls)
        }
    }

    @Test
    fun `consumer failure closes the authorized pass before propagating`() = runTest {
        val expected = IllegalStateException("projection failed")
        val returned = mutableListOf<Pair<RunEffectIdentity, ProviderPassResult>>()

        try {
            ProviderPassEffectExecutor().execute(
                request = request(fakeProvider(flowOf(StreamEvent.TextChunk("answer")))),
                callbacks = callbacks(
                    requestEffect = { RunEffect.StartProviderPass(it) },
                    returnConsumerFailure = { identity, result -> returned += identity to result },
                    onEvent = { throw expected },
                ),
            )
            fail("Expected consumer failure")
        } catch (actual: IllegalStateException) {
            assertEquals(expected::class, actual::class)
            assertEquals(expected.message, actual.message)
        }

        assertEquals(listOf(IDENTITY to ProviderPassResult.FAILED), returned)
    }

    private fun request(provider: LlmProvider) = ProviderPassExecutionRequest(
        proposedIdentity = IDENTITY,
        provider = provider,
        messages = listOf(ChatMessage(text = "input", participant = Participant.USER)),
        config = ProviderConfig(apiKey = "key", modelId = "model"),
    )

    private fun callbacks(
        requestEffect: suspend (RunEffectIdentity) -> RunEffect.StartProviderPass? = { null },
        returnConsumerFailure: suspend (RunEffectIdentity, ProviderPassResult) -> Unit = { _, _ -> },
        onFirstEvent: (() -> Unit)? = null,
        onEvent: suspend (StreamEvent) -> Unit = {},
    ) = ProviderPassExecutionCallbacks(
        requestEffect = requestEffect,
        returnConsumerFailure = returnConsumerFailure,
        onFirstEvent = onFirstEvent,
        onEvent = onEvent,
    )

    private fun fakeProvider(
        events: Flow<StreamEvent>,
        onGenerate: () -> Unit = {},
    ) = object : LlmProvider {
        override val name: String = "provider"
        override val defaultBaseUrl: String = "https://example.invalid"

        override fun generateResponse(
            messages: List<ChatMessage>,
            config: ProviderConfig,
        ): Flow<StreamEvent> {
            onGenerate()
            return events
        }

        override suspend fun fetchModels(apiKey: String, baseUrl: String?): List<String> = emptyList()
    }

    private companion object {
        val IDENTITY = RunEffectIdentity(
            conversationId = "conversation",
            ownerToken = 7,
            runId = "run",
            pass = 2,
            effectId = "provider-2-0",
        )
    }
}
