package com.newoether.agora.api.util

import com.newoether.agora.api.GenerationError

/** One initial provider request plus five retries. */
internal object ProviderRetryPolicy {
    const val MAX_RETRIES = 5
    const val MAX_ATTEMPTS = MAX_RETRIES + 1

    private val retryableUpstreamMessages = listOf(
        "failed to generate",
        "the server response could not be read",
    )

    /** Delay before retry number [retryNumber], where valid retries are 1 through 5. */
    fun delayMillis(retryNumber: Int): Long = when (retryNumber) {
        in 1..3 -> 5_000L
        in 4..5 -> 30_000L
        else -> error("Invalid retry number: $retryNumber")
    }

    /** Relay outcome used when the upstream accepted a request but failed to produce a generation. */
    fun isFailedToGenerateOutcome(raw: String?): Boolean =
        raw?.contains("failed to generate", ignoreCase = true) == true

    /** Exact response-body read failure eligible for pre-output Provider replay. */
    fun isUnreadableServerResponseFailure(raw: String?): Boolean =
        raw?.contains("the server response could not be read", ignoreCase = true) == true

    /** Provider text that identifies a transient upstream failure safe for the existing retry loop. */
    fun isRetryableUpstreamFailure(raw: String?): Boolean =
        raw?.let { text ->
            retryableUpstreamMessages.any { message -> text.contains(message, ignoreCase = true) }
        } == true

    /** A relay may put the same transient outcome in a non-200 HTTP body instead of SSE JSON. */
    fun shouldRetryHttp(statusCode: Int, body: String?, retryableStatusCodes: Set<Int>): Boolean =
        statusCode in retryableStatusCodes || isRetryableUpstreamFailure(body)

    /** Explicit stream failures retry only when their Provider detail is a known transient signal. */
    fun shouldRetryStreamError(error: GenerationError?): Boolean = when (error) {
        is GenerationError.Api -> isRetryableUpstreamFailure(error.message)
        is GenerationError.Network -> isRetryableUpstreamFailure(error.message)
        else -> false
    }
}
