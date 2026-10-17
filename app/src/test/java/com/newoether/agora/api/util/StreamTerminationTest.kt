package com.newoether.agora.api.util

import com.newoether.agora.api.GenerationError
import com.newoether.agora.api.StreamEvent
import com.newoether.agora.model.CitationRecord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StreamTerminationTest {
    @Test
    fun citationAndEmptyTextUpdates_doNotCountAsModelOutput() {
        val event = StreamEvent.CitationUpdate(
            CitationRecord(
                sourceId = "citation_source",
                provider = "test",
                kind = "url",
                title = "Source",
            ),
        )

        assertFalse(event.carriesModelOutput())
        assertFalse(StreamEvent.TextChunk("").carriesModelOutput())
    }

    private fun termination(
        sawTerminalMarker: Boolean = true,
        stopReason: String? = "end_turn",
        producedContent: Boolean = true,
        toolCallInFlight: Boolean = false,
        streamError: GenerationError? = null,
        alreadyReportedError: Boolean = false,
        timedOut: Boolean = false,
        retryableStreamError: Boolean = true,
    ) = StreamTermination(
        sawTerminalMarker = sawTerminalMarker,
        stopReason = stopReason,
        producedContent = producedContent,
        toolCallInFlight = toolCallInFlight,
        streamError = streamError,
        alreadyReportedError = alreadyReportedError,
        timedOut = timedOut,
        retryableStreamError = retryableStreamError,
    )

    @Test
    fun cleanCompletion_reportsNoErrorAndIsNotRetried() {
        val result = termination()

        assertNull(result.toError("Anthropic"))
        assertFalse(result.isRetryable)
    }

    @Test
    fun toolUseStop_isACleanCompletion() {
        val result = termination(stopReason = "tool_use")

        assertNull(result.toError("Anthropic"))
        assertFalse(result.isRetryable)
    }

    @Test
    fun terminalMarkerCannotCompleteAnOpenToolCall() {
        val result = termination(
            sawTerminalMarker = true,
            stopReason = "tool_use",
            producedContent = true,
            toolCallInFlight = true,
        )

        val error = result.toError("Anthropic") as GenerationError.IncompleteStream
        assertTrue(error.toolCallInFlight)
        assertFalse(result.isRetryable)
    }

    @Test
    fun missingTerminalMarker_isReportedAsIncompleteEvenWithContent() {
        // The exact shape of the bug: text streamed fine, then the relay closed the connection
        // where the tool_use block should have started. HTTP 200 alone must not mean success.
        val result = termination(
            sawTerminalMarker = false,
            stopReason = null,
            producedContent = true,
            toolCallInFlight = true,
        )

        val error = result.toError("Anthropic") as GenerationError.IncompleteStream
        assertEquals("Anthropic", error.provider)
        assertTrue(error.toolCallInFlight)
        // Content was already shown, so a replay would duplicate it.
        assertFalse(result.isRetryable)
    }

    @Test
    fun missingTerminalMarkerWithNoContent_isRetried() {
        val result = termination(
            sawTerminalMarker = false,
            stopReason = null,
            producedContent = false,
        )

        assertTrue(result.isRetryable)
    }

    @Test
    fun tokenCapStop_isReportedAsTruncationAndNeverRetried() {
        val maxTokens = termination(stopReason = "max_tokens", producedContent = false)
        val length = termination(stopReason = "length", producedContent = false)

        assertTrue(maxTokens.truncatedByTokenCap)
        assertTrue(length.truncatedByTokenCap)
        assertTrue(maxTokens.toError("Anthropic") is GenerationError.OutputTruncated)
        assertTrue(length.toError("OpenAI") is GenerationError.OutputTruncated)
        // Replaying the same request would truncate at the same place.
        assertFalse(maxTokens.isRetryable)
        assertFalse(length.isRetryable)
    }

    @Test
    fun inStreamError_isSurfacedAndRetriedWhenNothingWasShown() {
        val apiError = GenerationError.Api(code = null, type = "overloaded_error", message = "Overloaded")
        val result = termination(
            sawTerminalMarker = false,
            stopReason = null,
            producedContent = false,
            streamError = apiError,
        )

        assertEquals(apiError, result.toError("Anthropic"))
        assertTrue(result.isRetryable)
    }

    @Test
    fun inStreamErrorAfterContent_isSurfacedButNotRetried() {
        val apiError = GenerationError.Api(code = null, type = "overloaded_error", message = "Overloaded")
        val result = termination(producedContent = true, streamError = apiError)

        assertEquals(apiError, result.toError("Anthropic"))
        assertFalse(result.isRetryable)
    }

    @Test
    fun explicitUnreadableServerResponse_isRetriedBeforeOutput() {
        val apiError = GenerationError.Api(
            code = null,
            type = "server_error",
            message = "The server response could not be read.",
        )
        val result = termination(
            sawTerminalMarker = true,
            stopReason = "failed",
            producedContent = false,
            streamError = apiError,
            retryableStreamError = false,
        )

        assertEquals(apiError, result.toError("OpenAI"))
        assertTrue(result.isRetryable)
    }

    @Test
    fun explicitUnreadableServerResponseAfterContent_isNotRetried() {
        val result = termination(
            sawTerminalMarker = true,
            stopReason = "failed",
            producedContent = true,
            streamError = GenerationError.Api(
                code = null,
                type = "server_error",
                message = "The server response could not be read.",
            ),
            retryableStreamError = false,
        )

        assertFalse(result.isRetryable)
    }

    @Test
    fun otherExplicitTerminalStreamError_isNotRetried() {
        val result = termination(
            sawTerminalMarker = true,
            stopReason = "failed",
            producedContent = false,
            streamError = GenerationError.Api(
                code = null,
                type = "provider_error",
                message = "upstream rejected",
            ),
            retryableStreamError = false,
        )

        assertFalse(result.isRetryable)
    }

    @Test
    fun wrappedUnreadableResponseIOException_isRetryableTransportFailure() {
        val failure = java.io.IOException(
            "request failed",
            java.io.IOException("The server response could not be read."),
        )

        val error = failure.asRetryableTransportError() as GenerationError.Network

        assertEquals("The server response could not be read.", error.message)
        assertNull(java.io.IOException("invalid response").asRetryableTransportError())
        assertNull(
            java.io.IOException(
                "The server response could not be read.",
                java.net.UnknownHostException("host unavailable"),
            ).asRetryableTransportError()
        )
        assertNull(
            java.net.UnknownHostException("The server response could not be read.")
                .asRetryableTransportError()
        )
    }

    @Test
    fun timeout_isSurfacedAndRetriedWhenNothingWasShown() {
        val result = termination(
            sawTerminalMarker = false,
            stopReason = null,
            producedContent = false,
            timedOut = true,
        )

        assertEquals(GenerationError.Timeout, result.toError("OpenAI"))
        assertTrue(result.isRetryable)
    }

    @Test
    fun alreadyReportedError_isNotDuplicatedOrRetried() {
        val result = termination(
            sawTerminalMarker = false,
            stopReason = null,
            producedContent = false,
            alreadyReportedError = true,
        )

        assertNull(result.toError("OpenAI"))
        assertFalse(result.isRetryable)
    }

    @Test
    fun stopReasonWithoutMessageStop_countsAsSemanticCompletion() {
        // Some relays omit message_stop but still report stop_reason. Requiring both would
        // report false truncations on every one of those endpoints.
        val result = termination(sawTerminalMarker = true, stopReason = "end_turn")

        assertNull(result.toError("Anthropic"))
    }
}
