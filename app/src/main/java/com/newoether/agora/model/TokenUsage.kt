package com.newoether.agora.model

import androidx.compose.runtime.Immutable
import kotlinx.serialization.Serializable

/**
 * Provider-reported token usage for one or more completed model requests.
 *
 * Optional categories stay null when a provider did not report enough information to derive them.
 * In particular, an unknown cache split must never be presented as a real zero.
 *
 * [outputTokenCount] includes reasoning tokens when the provider bills/reports them as output;
 * [reasoningTokenCount] is the reported subset available for a later detailed breakdown.
 */
@Immutable
@Serializable
data class TokenUsage(
    val totalTokenCount: Int,
    val inputTokenCount: Int? = null,
    val cachedInputTokenCount: Int? = null,
    val cacheWriteInputTokenCount: Int? = null,
    val uncachedInputTokenCount: Int? = null,
    val outputTokenCount: Int? = null,
    val reasoningTokenCount: Int? = null,
    /** Sum of observed generation intervals; excludes first-content wait and tool execution. */
    val generationDurationMs: Long? = null,
) {
    fun plusRequest(other: TokenUsage): TokenUsage = TokenUsage(
        totalTokenCount = addCounts(totalTokenCount, other.totalTokenCount),
        inputTokenCount = addReported(inputTokenCount, other.inputTokenCount),
        cachedInputTokenCount =
            addReported(cachedInputTokenCount, other.cachedInputTokenCount),
        cacheWriteInputTokenCount =
            addReported(cacheWriteInputTokenCount, other.cacheWriteInputTokenCount),
        uncachedInputTokenCount =
            addReported(uncachedInputTokenCount, other.uncachedInputTokenCount),
        outputTokenCount = addReported(outputTokenCount, other.outputTokenCount),
        reasoningTokenCount =
            addReported(reasoningTokenCount, other.reasoningTokenCount),
        generationDurationMs = if (generationDurationMs != null && other.generationDurationMs != null)
            generationDurationMs.takeIf { it <= Long.MAX_VALUE - other.generationDurationMs }
                ?.plus(other.generationDurationMs) else null,
    )

    companion object {
        fun fromPersisted(
            totalTokenCount: Int,
            inputTokenCount: Int?,
            cachedInputTokenCount: Int?,
            cacheWriteInputTokenCount: Int?,
            uncachedInputTokenCount: Int?,
            outputTokenCount: Int?,
            reasoningTokenCount: Int?,
            generationDurationMs: Long? = null,
        ): TokenUsage? {
            if (
                totalTokenCount <= 0 &&
                inputTokenCount == null &&
                cachedInputTokenCount == null &&
                cacheWriteInputTokenCount == null &&
                uncachedInputTokenCount == null &&
                outputTokenCount == null &&
                reasoningTokenCount == null
            ) {
                return null
            }
            return TokenUsage(
                totalTokenCount = totalTokenCount.coerceAtLeast(0),
                inputTokenCount = inputTokenCount.nonNegativeOrNull(),
                cachedInputTokenCount = cachedInputTokenCount.nonNegativeOrNull(),
                cacheWriteInputTokenCount = cacheWriteInputTokenCount.nonNegativeOrNull(),
                uncachedInputTokenCount = uncachedInputTokenCount.nonNegativeOrNull(),
                outputTokenCount = outputTokenCount.nonNegativeOrNull(),
                reasoningTokenCount = reasoningTokenCount.nonNegativeOrNull(),
                generationDurationMs = generationDurationMs?.takeIf { it > 0 },
            )
        }

        internal fun addCounts(first: Int, second: Int): Int =
            (first.toLong() + second.toLong())
                .coerceAtMost(Int.MAX_VALUE.toLong())
                .toInt()

        private fun addReported(first: Int?, second: Int?): Int? =
            if (first == null || second == null) null else addCounts(first, second)

        private fun Int?.nonNegativeOrNull(): Int? = this?.coerceAtLeast(0)
    }
}

/**
 * Aggregates provider usage without double-counting cumulative stream snapshots.
 *
 * A provider request may report usage multiple times; [observeRequestSnapshot] replaces the
 * current request snapshot. Only [finishRequest] adds that final snapshot to previous tool-loop
 * rounds.
 */
internal class RequestTokenUsageAccumulator(
    private val nowNanos: () -> Long = System::nanoTime,
) {
    private var firstContentNanos: Long? = null
    private var lastContentNanos: Long? = null
    private var completedGenerationNanos = 0L

    fun observeGenerationContent() {
        if (!requestActive) return
        val now = nowNanos()
        if (firstContentNanos == null) firstContentNanos = now
        lastContentNanos = now
    }

    fun pauseGeneration() {
        val first = firstContentNanos
        val last = lastContentNanos
        if (first != null && last != null && last > first) completedGenerationNanos += last - first
        firstContentNanos = null
        lastContentNanos = null
    }

    fun resetGenerationTiming() {
        firstContentNanos = null
        lastContentNanos = null
        completedGenerationNanos = 0L
    }

    private var completedUsage: TokenUsage? = null
    private var currentRequestUsage: TokenUsage? = null
    private var requestActive = false

    fun beginRequest() {
        check(!requestActive) { "A token-usage request is already active" }
        currentRequestUsage = null
        resetGenerationTiming()
        requestActive = true
    }

    fun observeRequestSnapshot(usage: TokenUsage) {
        check(requestActive) { "Token usage arrived outside a provider request" }
        currentRequestUsage = usage
    }

    fun finishRequest() {
        if (!requestActive) return
        pauseGeneration()
        currentRequestUsage?.let { usage ->
            val requestUsage = usage.copy(
                generationDurationMs = usage.generationDurationMs
                    ?: (completedGenerationNanos / 1_000_000L).takeIf { it > 0 },
            )
            completedUsage = completedUsage?.plusRequest(requestUsage) ?: requestUsage
        }
        currentRequestUsage = null
        requestActive = false
    }

    fun snapshot(): TokenUsage? = when {
        completedUsage == null -> currentRequestUsage
        currentRequestUsage == null -> completedUsage
        else -> completedUsage?.plusRequest(checkNotNull(currentRequestUsage))
    }
}
