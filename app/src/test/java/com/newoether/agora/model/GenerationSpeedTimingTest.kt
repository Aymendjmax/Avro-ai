package com.newoether.agora.model

import com.newoether.agora.ui.chat.message.tokenUsagePresentation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GenerationSpeedTimingTest {
    @Test fun firstContentWaitToolGapsAndRepeatedUsageAreExcluded() {
        var clock = 0L
        val accumulator = RequestTokenUsageAccumulator { clock * 1_000_000L }
        accumulator.beginRequest()
        clock = 9_000
        accumulator.observeGenerationContent()
        clock = 10_000
        accumulator.observeGenerationContent()
        accumulator.pauseGeneration()
        clock = 50_000
        accumulator.observeGenerationContent()
        clock = 51_000
        accumulator.observeGenerationContent()
        accumulator.observeRequestSnapshot(TokenUsage(120, inputTokenCount = 100, outputTokenCount = 20))
        accumulator.observeRequestSnapshot(TokenUsage(140, inputTokenCount = 100, outputTokenCount = 40))
        accumulator.finishRequest()
        assertEquals(2_000L, accumulator.snapshot()?.generationDurationMs)
        assertEquals(20.0, tokenUsagePresentation(accumulator.snapshot()).generationTokensPerSecond!!, 0.001)
        clock = 100_000
        accumulator.beginRequest()
        accumulator.observeGenerationContent()
        clock = 101_000
        accumulator.observeGenerationContent()
        accumulator.observeRequestSnapshot(TokenUsage(130, inputTokenCount = 100, outputTokenCount = 30))
        accumulator.finishRequest()
        assertEquals(3_000L, accumulator.snapshot()?.generationDurationMs)
        assertEquals(70.0 / 3.0, tokenUsagePresentation(accumulator.snapshot()).generationTokensPerSecond!!, 0.001)
    }

    @Test fun unmeasuredOrSingleChunkRequestsNeverInventSpeed() {
        val accumulator = RequestTokenUsageAccumulator { 42L }
        accumulator.beginRequest()
        accumulator.observeGenerationContent()
        accumulator.observeRequestSnapshot(TokenUsage(100, outputTokenCount = 20))
        accumulator.finishRequest()
        assertNull(tokenUsagePresentation(accumulator.snapshot()).generationTokensPerSecond)
        assertNull(tokenUsagePresentation(TokenUsage(100, outputTokenCount = 20)).generationTokensPerSecond)
        assertNull(tokenUsagePresentation(TokenUsage(100, generationDurationMs = 1000)).generationTokensPerSecond)
        assertNull(tokenUsagePresentation(TokenUsage(100, outputTokenCount = 20, generationDurationMs = 0)).generationTokensPerSecond)
    }

    @Test fun mixedKnownAndUnknownRequestDurationsStayUnknown() {
        val measured = TokenUsage(100, outputTokenCount = 20, generationDurationMs = 1000)
        val unmeasured = TokenUsage(100, outputTokenCount = 30)
        assertNull(measured.plusRequest(unmeasured).generationDurationMs)
        assertNull(unmeasured.plusRequest(measured).generationDurationMs)
    }

    @Test fun retryDiscardsTheOldTimingInterval() {
        var clock = 0L
        val accumulator = RequestTokenUsageAccumulator { clock }
        accumulator.beginRequest()
        accumulator.observeGenerationContent()
        clock = 8_000_000_000L
        accumulator.resetGenerationTiming()
        accumulator.observeGenerationContent()
        clock = 9_000_000_000L
        accumulator.observeGenerationContent()
        accumulator.observeRequestSnapshot(TokenUsage(10, outputTokenCount = 10))
        accumulator.finishRequest()
        assertEquals(1000L, accumulator.snapshot()?.generationDurationMs)
    }
}
