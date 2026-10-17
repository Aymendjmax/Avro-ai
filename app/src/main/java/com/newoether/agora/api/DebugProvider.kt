package com.newoether.agora.api

import com.newoether.agora.model.ChatMessage
import com.newoether.agora.model.TokenUsage
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.flow

class DebugProvider : LlmProvider {
    override val name: String = PROVIDER_NAME
    override val defaultBaseUrl: String = ""

    override fun generateResponse(
        messages: List<ChatMessage>,
        config: ProviderConfig,
    ): Flow<StreamEvent> = flow {
        require(config.modelId == MODEL_ID) {
            "DebugProvider only supports model ID '$MODEL_ID'"
        }

        repeat(CYCLE_COUNT) { index ->
            val cycle = index + 1
            emitStep(
                StreamEvent.ThoughtChunk(
                    thought = "Cycle $cycle: inspect the deterministic dry-run fixture. ",
                    title = "Debug cycle $cycle",
                ),
            )
            emitStep(
                StreamEvent.ThoughtChunk(
                    thought = "Plan two provider-hosted display calls before streaming the answer.",
                    title = "Debug cycle $cycle",
                ),
            )

            val inspectKey = "debug-cycle-$cycle-inspect"
            emitStep(
                StreamEvent.HostedToolCallUpdate(
                    streamKey = inspectKey,
                    name = "debug.inspect",
                    arguments = """{"cycle":$cycle,"mode":"dry-run"}""",
                ),
            )
            emitStep(
                StreamEvent.HostedToolCallUpdate(
                    streamKey = inspectKey,
                    name = "debug.inspect",
                    arguments = """{"cycle":$cycle,"mode":"dry-run"}""",
                    result = """{"status":"ok","items":2}""",
                ),
            )

            val calculateKey = "debug-cycle-$cycle-calculate"
            emitStep(
                StreamEvent.HostedToolCallUpdate(
                    streamKey = calculateKey,
                    name = "debug.calculate",
                    arguments = """{"expression":"$cycle * $cycle"}""",
                ),
            )
            emitStep(
                StreamEvent.HostedToolCallUpdate(
                    streamKey = calculateKey,
                    name = "debug.calculate",
                    arguments = """{"expression":"$cycle * $cycle"}""",
                    result = """{"value":${cycle * cycle}}""",
                ),
            )

            emitStep(StreamEvent.TextChunk("**Cycle $cycle complete.** "))
            emitStep(StreamEvent.TextChunk("`debug` stayed local and executed no tools.\n\n"))
            emitStep(StreamEvent.TextChunk("Inline math: \\(x_$cycle = $cycle^2\\).\n\n"))
            emitStep(StreamEvent.TextChunk("```kotlin\nval cycle = $cycle\n```\n"))
        }

        emit(
            StreamEvent.UsageUpdate(
                TokenUsage(
                    totalTokenCount = 144,
                    inputTokenCount = 24,
                    uncachedInputTokenCount = 24,
                    outputTokenCount = 120,
                    reasoningTokenCount = 36,
                ),
            ),
        )
    }

    override suspend fun fetchModels(apiKey: String, baseUrl: String?): List<String> =
        listOf(MODEL_ID)

    private suspend fun FlowCollector<StreamEvent>.emitStep(event: StreamEvent) {
        emit(event)
        delay(STEP_DELAY_MILLIS)
    }

    companion object {
        const val MODEL_ID = "debug"
        const val PROVIDER_NAME = "Debug"
        internal const val CYCLE_COUNT = 3
        private const val STEP_DELAY_MILLIS = 40L
    }
}
