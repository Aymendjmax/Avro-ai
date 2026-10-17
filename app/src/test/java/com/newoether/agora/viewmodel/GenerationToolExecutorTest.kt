package com.newoether.agora.viewmodel

import com.newoether.agora.api.ToolDefinition
import com.newoether.agora.model.ConversationCommand
import com.newoether.agora.model.RunEffectIdentity
import com.newoether.agora.model.ToolExecutionStates
import com.newoether.agora.tool.ToolExecutionEvent
import com.newoether.agora.tool.ToolExecutionResult
import com.newoether.agora.tool.ToolProvider
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GenerationToolExecutorTest {
    @Test
    fun `durable wait timeout remains background running at the completion boundary`() {
        val result =
            """{"type":"wait_for_job","job_id":"same-job","state":"running","timed_out":true}"""

        assertEquals(ToolExecutionStates.BACKGROUND_RUNNING, finalToolState(ToolExecutionResult(result), "wait_for_job"))
    }

    @Test
    fun `completed result retains the authorized batch and call identities`() = runTest {
        val provider = FakeToolProvider()
        val executor = GenerationToolExecutor.forTest(listOf(provider))
        val events = mutableListOf<ToolExecutionEvent>()

        val executed = executor.execute(
            call = AuthorizedToolCall(
                batchIdentity = BATCH_IDENTITY,
                callId = "call-1",
                name = "known_tool",
                arguments = "{}",
                context = GenerationContext(),
                authorizedToolNames = setOf("known_tool"),
            ),
            onEvent = events::add,
        )

        assertEquals(BATCH_IDENTITY, executed.batchIdentity)
        assertEquals("call-1", executed.callId)
        assertEquals("done", executed.result.text)
        assertEquals(1, provider.executionCount)
        assertTrue(events.single() is ToolExecutionEvent.Completed)
    }

    @Test
    fun `incomplete arguments fail before provider execution and retain identity`() = runTest {
        val provider = FakeToolProvider()
        val executor = GenerationToolExecutor.forTest(listOf(provider))

        val executed = executor.execute(
            call = AuthorizedToolCall(
                batchIdentity = BATCH_IDENTITY,
                callId = "call-invalid",
                name = "known_tool",
                arguments = "{",
                context = GenerationContext(),
                authorizedToolNames = setOf("known_tool"),
            ),
            onEvent = {},
        )

        assertEquals(BATCH_IDENTITY, executed.batchIdentity)
        assertEquals("call-invalid", executed.callId)
        assertTrue(executed.result.isError)
        assertTrue(executed.result.text.contains("complete JSON object"))
        assertEquals(0, provider.executionCount)
    }

    @Test
    fun `call omitted from the frozen definition set never reaches a matching provider`() = runTest {
        val provider = FakeToolProvider()
        val executor = GenerationToolExecutor.forTest(listOf(provider))

        val executed = executor.execute(
            call = AuthorizedToolCall(
                batchIdentity = BATCH_IDENTITY,
                callId = "call-disabled",
                name = "known_tool",
                arguments = "{}",
                context = GenerationContext(),
                authorizedToolNames = emptySet(),
            ),
            onEvent = {},
        )

        assertTrue(executed.result.isError)
        assertTrue(executed.result.text.contains("not authorized"))
        assertEquals(0, provider.executionCount)
    }

    @Test
    fun `image generation alone receives the extended execution timeout`() {
        assertEquals(600_000L, toolExecutionTimeoutMs("generate_image", 25L))
        assertEquals(25L, toolExecutionTimeoutMs("blocking_tool", 25L))
    }

    @Test
    fun `active tool stop releases when durable write settles first`() = runBlocking {
        assertActiveToolStopRelease(persistenceFirst = true)
    }

    @Test
    fun `active tool stop releases when generation coroutine settles first`() = runBlocking {
        assertActiveToolStopRelease(persistenceFirst = false)
    }

    @Test
    fun `tool timeout is a recoverable identified result`() = runTest {
        val provider = object : ToolProvider {
            override fun definitions(ctx: GenerationContext): List<ToolDefinition> = emptyList()

            override suspend fun execute(
                name: String,
                arguments: String,
                ctx: GenerationContext,
            ): String = awaitCancellation()

            override fun handles(name: String): Boolean = true
        }
        val executor = GenerationToolExecutor.forTest(listOf(provider))

        val executed = executor.execute(
            call = AuthorizedToolCall(
                batchIdentity = BATCH_IDENTITY,
                callId = "call-timeout",
                name = "blocking_tool",
                arguments = "{}",
                context = GenerationContext(toolTimeoutMs = 25L),
                authorizedToolNames = setOf("blocking_tool"),
            ),
            onEvent = {},
        )

        assertEquals(BATCH_IDENTITY, executed.batchIdentity)
        assertEquals("call-timeout", executed.callId)
        assertTrue(executed.result.isError)
        assertTrue(executed.result.text.contains("timed out after 25ms"))
    }

    private suspend fun assertActiveToolStopRelease(persistenceFirst: Boolean) {
        val started = CompletableDeferred<Unit>()
        val provider = object : ToolProvider {
            override fun definitions(ctx: GenerationContext): List<ToolDefinition> = emptyList()

            override suspend fun execute(
                name: String,
                arguments: String,
                ctx: GenerationContext,
            ): String {
                started.complete(Unit)
                return awaitCancellation()
            }

            override fun handles(name: String): Boolean = name == "blocking_tool"
        }
        val executor = GenerationToolExecutor.forTest(listOf(provider))
        val state = ConversationGenerationState("conversation", reclaimQueuedAttachments = {})
        val token = state.acquireForSend()!!
        state.bindRun(token, "run")
        val unwind = CompletableDeferred<Unit>()
        val job = checkNotNull(
            state.launchGenerationJob(token) {
                try {
                    executor.execute(
                        call = AuthorizedToolCall(
                            batchIdentity = BATCH_IDENTITY,
                            callId = "active-call",
                            name = "blocking_tool",
                            arguments = "{}",
                            context = GenerationContext(toolTimeoutMs = 60_000L),
                            authorizedToolNames = setOf("blocking_tool"),
                        ),
                        onEvent = {},
                    )
                } finally {
                    withContext(NonCancellable) { unwind.await() }
                }
            },
        )
        started.await()

        val stopped = state.stop()
        val completion = ConversationCommand.PersistenceSettled(
            identity = requireNotNull(stopped.finalizationEffect).identity,
            success = true,
        )
        if (persistenceFirst) {
            assertEquals(
                ConversationGenerationState.StopFinalizationOutcome.RECORDED,
                state.finishStopFinalization(completion),
            )
            assertTrue(state.stopping.value)
            unwind.complete(Unit)
            job.join()
        } else {
            unwind.complete(Unit)
            job.join()
            withTimeout(5_000L) {
                while (state.runtimeTraceSnapshot().none { it.commandType == "CoroutineSettled" }) {
                    yield()
                }
            }
            assertTrue(state.stopping.value)
            assertEquals(
                ConversationGenerationState.StopFinalizationOutcome.SETTLED,
                state.finishStopFinalization(completion),
            )
        }

        withTimeout(5_000L) { state.stopping.first { stopping -> !stopping } }
        assertFalse(state.generating.value)
        assertFalse(state.stopping.value)
    }

    private class FakeToolProvider : ToolProvider {
        var executionCount = 0

        override fun definitions(ctx: GenerationContext): List<ToolDefinition> = emptyList()

        override suspend fun execute(
            name: String,
            arguments: String,
            ctx: GenerationContext,
        ): String {
            executionCount++
            return "done"
        }

        override fun handles(name: String): Boolean = name == "known_tool"
    }

    private companion object {
        val BATCH_IDENTITY = RunEffectIdentity(
            conversationId = "conversation",
            ownerToken = 1L,
            runId = "run",
            pass = 2,
            effectId = "tool-batch",
        )
    }
}
