package com.newoether.agora.ui.chat.message

import com.newoether.agora.model.TokenUsage

internal data class TokenUsagePresentation(
    val input: Int?,
    val cachedInput: Int?,
    val output: Int?,
    val generationTokensPerSecond: Double? = null,
)

internal fun tokenUsagePresentation(
    usage: TokenUsage?,
): TokenUsagePresentation {
    if (usage == null) return TokenUsagePresentation(null, null, null)
    val input = usage.inputTokenCount
        ?: if (
            usage.cachedInputTokenCount != null &&
            usage.uncachedInputTokenCount != null
        ) {
            TokenUsage.addCounts(
                usage.cachedInputTokenCount,
                usage.uncachedInputTokenCount,
            )
        } else {
            usage.outputTokenCount
                ?.let { output -> (usage.totalTokenCount - output).takeIf { it >= 0 } }
        }
    val output = usage.outputTokenCount
        ?: input?.let { inputCount ->
            (usage.totalTokenCount - inputCount).takeIf { it >= 0 }
        }
    return TokenUsagePresentation(
        input = input,
        cachedInput = usage.cachedInputTokenCount,
        output = output,
        generationTokensPerSecond = usage.generationDurationMs?.takeIf { it > 0 }
            ?.let { duration -> output?.takeIf { it > 0 }?.toDouble()?.times(1000.0)?.div(duration) },
    )
}
