package com.newoether.agora.api

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DebugProviderTest {
    private val provider = DebugProvider()

    @Test
    fun `three cycles stream thoughts hosted calls markdown latex and usage in order`() = runTest {
        val events = provider.generateResponse(emptyList(), config()).toList()

        assertEquals(DebugProvider.CYCLE_COUNT * 2, events.filterIsInstance<StreamEvent.ThoughtChunk>().size)
        assertEquals(DebugProvider.CYCLE_COUNT * 4, events.filterIsInstance<StreamEvent.HostedToolCallUpdate>().size)
        assertEquals(DebugProvider.CYCLE_COUNT * 4, events.filterIsInstance<StreamEvent.TextChunk>().size)
        assertTrue(events.last() is StreamEvent.UsageUpdate)
        assertTrue(events.none { it is StreamEvent.ToolCallUpdate })
        assertTrue(events.none { it is StreamEvent.ToolCallRequest })
        assertTrue(events.none { it is StreamEvent.ToolCallsRequest })

        (1..DebugProvider.CYCLE_COUNT).forEach { cycle ->
            val thoughtIndex = events.indexOfFirst {
                it is StreamEvent.ThoughtChunk && it.title == "Debug cycle $cycle"
            }
            val inspectStartIndex = events.indexOfFirst {
                it is StreamEvent.HostedToolCallUpdate &&
                    it.streamKey == "debug-cycle-$cycle-inspect" &&
                    it.result == null
            }
            val inspectResultIndex = events.indexOfFirst {
                it is StreamEvent.HostedToolCallUpdate &&
                    it.streamKey == "debug-cycle-$cycle-inspect" &&
                    it.result != null
            }
            val calculateStartIndex = events.indexOfFirst {
                it is StreamEvent.HostedToolCallUpdate &&
                    it.streamKey == "debug-cycle-$cycle-calculate" &&
                    it.result == null
            }
            val calculateResultIndex = events.indexOfFirst {
                it is StreamEvent.HostedToolCallUpdate &&
                    it.streamKey == "debug-cycle-$cycle-calculate" &&
                    it.result != null
            }
            val answerIndex = events.indexOfFirst {
                it is StreamEvent.TextChunk && it.text == "**Cycle $cycle complete.** "
            }

            assertTrue(thoughtIndex >= 0)
            assertTrue(inspectStartIndex > thoughtIndex)
            assertTrue(inspectResultIndex > inspectStartIndex)
            assertTrue(calculateStartIndex > inspectResultIndex)
            assertTrue(calculateResultIndex > calculateStartIndex)
            assertTrue(answerIndex > calculateResultIndex)
        }

        val answer = events.filterIsInstance<StreamEvent.TextChunk>().joinToString("") { it.text }
        assertTrue(answer.contains("**Cycle 1 complete.**"))
        assertTrue(answer.contains("\\(x_3 = 3^2\\)"))
        assertTrue(answer.contains("```kotlin\nval cycle = 2\n```"))
        assertTrue(answer.contains("executed no tools"))

        val terminalHosted = events.filterIsInstance<StreamEvent.HostedToolCallUpdate>()
            .filter { it.result != null }
        assertEquals(DebugProvider.CYCLE_COUNT * 2, terminalHosted.size)
        assertTrue(terminalHosted.all { !it.isError })
        assertEquals(
            setOf("debug.inspect", "debug.calculate"),
            terminalHosted.map { it.name }.toSet(),
        )
        assertEquals(
            StreamEvent.UsageUpdate(
                com.newoether.agora.model.TokenUsage(
                    totalTokenCount = 144,
                    inputTokenCount = 24,
                    uncachedInputTokenCount = 24,
                    outputTokenCount = 120,
                    reasoningTokenCount = 36,
                ),
            ),
            events.last(),
        )
    }

    @Test
    fun `provider ignores credentials endpoints request resolution and tool definitions`() = runTest {
        val config = config().copy(
            apiKey = "must-not-be-read",
            baseUrl = "https://invalid.example",
            tools = emptyList(),
            requestResolver = ProviderRequestResolver { _, _ ->
                error("DebugProvider must not resolve or dispatch a provider request")
            },
        )

        val events = provider.generateResponse(emptyList(), config).toList()

        assertTrue(events.isNotEmpty())
        assertEquals(listOf(DebugProvider.MODEL_ID), provider.fetchModels("secret", "https://invalid.example"))
    }

    @Test
    fun `provider rejects every model except exact debug`() = runTest {
        var rejected = false
        try {
            provider.generateResponse(emptyList(), config().copy(modelId = "Debug")).toList()
        } catch (_: IllegalArgumentException) {
            rejected = true
        }

        assertTrue(rejected)
    }

    @Test
    fun `cancellation stops delayed streaming without terminal usage`() = runTest {
        val firstEvent = CompletableDeferred<Unit>()
        val events = mutableListOf<StreamEvent>()
        val job = launch {
            provider.generateResponse(emptyList(), config()).collect { event ->
                events += event
                firstEvent.complete(Unit)
            }
        }

        firstEvent.await()
        job.cancelAndJoin()
        val countAfterCancellation = events.size
        advanceTimeBy(10_000)
        runCurrent()

        assertTrue(job.isCancelled)
        assertEquals(countAfterCancellation, events.size)
        assertFalse(events.any { it is StreamEvent.UsageUpdate })
    }

    private fun config() = ProviderConfig(
        apiKey = "",
        modelId = DebugProvider.MODEL_ID,
    )
}
