package com.newoether.agora.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TokenUsageTest {
    @Test
    fun cumulativeSnapshotsReplaceWithinRequestAndRequestsAddAcrossToolRounds() {
        val accumulator = RequestTokenUsageAccumulator()

        accumulator.beginRequest()
        accumulator.observeRequestSnapshot(usage(total = 12, input = 8, output = 4))
        accumulator.observeRequestSnapshot(usage(total = 20, input = 13, output = 7))
        assertEquals(20, accumulator.snapshot()?.totalTokenCount)
        accumulator.finishRequest()

        accumulator.beginRequest()
        accumulator.observeRequestSnapshot(usage(total = 9, input = 6, output = 3))
        accumulator.finishRequest()

        val total = accumulator.snapshot()
        assertEquals(29, total?.totalTokenCount)
        assertEquals(19, total?.inputTokenCount)
        assertEquals(10, total?.outputTokenCount)
    }

    @Test
    fun missingBreakdownInAnyRequestKeepsAggregateBreakdownUnknown() {
        val accumulator = RequestTokenUsageAccumulator()

        accumulator.beginRequest()
        accumulator.observeRequestSnapshot(usage(total = 10, input = 7, output = 3))
        accumulator.finishRequest()
        accumulator.beginRequest()
        accumulator.observeRequestSnapshot(TokenUsage(totalTokenCount = 5))
        accumulator.finishRequest()

        val total = accumulator.snapshot()
        assertEquals(15, total?.totalTokenCount)
        assertNull(total?.inputTokenCount)
        assertNull(total?.outputTokenCount)
    }

    @Test
    fun everyReportedCategoryAddsAcrossToolCallProviderRequests() {
        val accumulator = RequestTokenUsageAccumulator()

        accumulator.beginRequest()
        accumulator.observeRequestSnapshot(
            TokenUsage(
                totalTokenCount = 30,
                inputTokenCount = 20,
                cachedInputTokenCount = 8,
                cacheWriteInputTokenCount = 3,
                uncachedInputTokenCount = 12,
                outputTokenCount = 10,
                reasoningTokenCount = 4,
            ),
        )
        accumulator.observeRequestSnapshot(
            TokenUsage(
                totalTokenCount = 36,
                inputTokenCount = 24,
                cachedInputTokenCount = 10,
                cacheWriteInputTokenCount = 4,
                uncachedInputTokenCount = 14,
                outputTokenCount = 12,
                reasoningTokenCount = 5,
            ),
        )
        accumulator.finishRequest()
        accumulator.beginRequest()
        accumulator.observeRequestSnapshot(
            TokenUsage(
                totalTokenCount = 18,
                inputTokenCount = 13,
                cachedInputTokenCount = 6,
                cacheWriteInputTokenCount = 2,
                uncachedInputTokenCount = 7,
                outputTokenCount = 5,
                reasoningTokenCount = 1,
            ),
        )
        accumulator.finishRequest()

        assertEquals(
            TokenUsage(
                totalTokenCount = 54,
                inputTokenCount = 37,
                cachedInputTokenCount = 16,
                cacheWriteInputTokenCount = 6,
                uncachedInputTokenCount = 21,
                outputTokenCount = 17,
                reasoningTokenCount = 6,
            ),
            accumulator.snapshot(),
        )
    }

    @Test
    fun cacheWriteTokensAddAcrossCompletedRequests() {
        val accumulator = RequestTokenUsageAccumulator()

        accumulator.beginRequest()
        accumulator.observeRequestSnapshot(
            usage(total = 20, input = 15, output = 5, cacheWrite = 7),
        )
        accumulator.finishRequest()
        accumulator.beginRequest()
        accumulator.observeRequestSnapshot(
            usage(total = 12, input = 9, output = 3, cacheWrite = 4),
        )
        accumulator.finishRequest()

        assertEquals(11, accumulator.snapshot()?.cacheWriteInputTokenCount)
    }

    private fun usage(total: Int, input: Int, output: Int, cacheWrite: Int? = null) = TokenUsage(
        totalTokenCount = total,
        inputTokenCount = input,
        cacheWriteInputTokenCount = cacheWrite,
        outputTokenCount = output,
    )
}
