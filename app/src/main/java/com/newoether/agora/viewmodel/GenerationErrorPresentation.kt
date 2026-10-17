package com.newoether.agora.viewmodel

import android.content.Context
import androidx.annotation.StringRes
import com.newoether.agora.R
import com.newoether.agora.api.GenerationError
import com.newoether.agora.api.extractStructuredProviderHttpErrorMessage
import java.util.Locale

private val persistedNetworkErrorRegex =
    Regex("""^Network error \((-?\d+)\):\s*(.+)$""", RegexOption.IGNORE_CASE)
private val persistedNetworkDetailRegex =
    Regex("""^Network error:\s*(.+)$""", RegexOption.IGNORE_CASE)
private val opaqueGenerationIdentifierRegex =
    Regex("^[A-Za-z0-9.-]+(?:_[A-Za-z0-9.-]+)+$")

internal enum class KnownGenerationErrorDetail {
    CONNECTION_CLOSED,
    CONNECTION_REFUSED,
    CONNECTION_RESET,
    UNKNOWN_HOST,
    TLS_FAILURE,
}

internal fun knownGenerationErrorDetail(detail: String): KnownGenerationErrorDetail? {
    val normalized = detail.trim().trimEnd('.').lowercase(Locale.ROOT)
    return when (normalized) {
        "connection closed",
        "closed connection",
        "connection was closed" -> KnownGenerationErrorDetail.CONNECTION_CLOSED
        "connection refused",
        "failed to connect" -> KnownGenerationErrorDetail.CONNECTION_REFUSED
        "connection reset",
        "connection reset by peer" -> KnownGenerationErrorDetail.CONNECTION_RESET
        "unknown host",
        "unable to resolve host" -> KnownGenerationErrorDetail.UNKNOWN_HOST
        "tls failure",
        "tls handshake failed",
        "ssl handshake failed" -> KnownGenerationErrorDetail.TLS_FAILURE
        else -> null
    }
}

@StringRes
internal fun KnownGenerationErrorDetail.stringResourceId(): Int = when (this) {
    KnownGenerationErrorDetail.CONNECTION_CLOSED -> R.string.generation_error_connection_closed
    KnownGenerationErrorDetail.CONNECTION_REFUSED -> R.string.generation_error_connection_refused
    KnownGenerationErrorDetail.CONNECTION_RESET -> R.string.generation_error_connection_reset
    KnownGenerationErrorDetail.UNKNOWN_HOST -> R.string.generation_error_unknown_host
    KnownGenerationErrorDetail.TLS_FAILURE -> R.string.generation_error_tls_failure
}

@StringRes
internal fun GenerationError.ownedGenerationErrorStringResourceId(): Int? = when (this) {
    is GenerationError.Network -> when (statusCode) {
        401 -> R.string.generation_error_authentication
        429 -> R.string.generation_error_rate_limit
        in 500..599 -> R.string.generation_error_server
        else -> knownGenerationErrorDetail(message)?.stringResourceId()
            ?: if (statusCode <= 0) {
                R.string.generation_error_network
            } else {
                R.string.generation_error_network_http
            }
    }
    is GenerationError.SseParse -> R.string.generation_error_sse_parse
    is GenerationError.MalformedToolCall -> R.string.generation_error_malformed_tool_call
    is GenerationError.IncompleteStream -> when {
        toolCallInFlight && stopReason != null ->
            R.string.generation_error_incomplete_tool_stream_reason
        toolCallInFlight -> R.string.generation_error_incomplete_tool_stream
        stopReason != null -> R.string.generation_error_incomplete_stream_reason
        else -> R.string.generation_error_incomplete_stream
    }
    is GenerationError.OutputTruncated -> R.string.generation_error_output_truncated
    is GenerationError.ToolExecution -> R.string.generation_error_tool_execution
    is GenerationError.Transcription -> R.string.generation_error_transcription
    is GenerationError.Embedding -> R.string.generation_error_embedding
    is GenerationError.RequestFormat -> R.string.generation_error_request_format
    GenerationError.Cancelled -> R.string.generation_error_cancelled
    GenerationError.Timeout -> R.string.generation_error_timeout
    is GenerationError.Unknown -> knownGenerationErrorDetail(detail())?.stringResourceId()
    is GenerationError.Api,
    is GenerationError.LocalModel,
    is GenerationError.Configuration -> null
}

internal fun localizedGenerationError(
    context: Context,
    error: GenerationError,
): String = when (error) {
    is GenerationError.Network -> when (error.statusCode) {
        401 -> context.getString(R.string.generation_error_authentication)
        429 -> context.getString(R.string.generation_error_rate_limit)
        in 500..599 -> context.getString(R.string.generation_error_server, error.statusCode)
        else -> {
            val known = knownGenerationErrorDetail(error.message)
            when {
                known != null -> context.getString(known.stringResourceId())
                error.statusCode <= 0 -> context.getString(
                    R.string.generation_error_network,
                    normalizeGenerationErrorDetail(error.message),
                )
                else -> context.getString(
                    R.string.generation_error_network_http,
                    error.statusCode,
                    normalizeGenerationErrorDetail(error.message),
                )
            }
        }
    }
    is GenerationError.Api -> buildString {
        error.code?.takeIf(String::isNotBlank)?.let(::append)
        error.type?.takeIf(String::isNotBlank)?.let {
            if (isNotEmpty()) append(' ')
            append('[').append(it).append(']')
        }
        if (isNotEmpty()) append(": ")
        append(localizedKnownOrNormalizedDetail(context, error.message))
    }
    is GenerationError.SseParse ->
        context.getString(R.string.generation_error_sse_parse)
    is GenerationError.MalformedToolCall -> context.getString(
        R.string.generation_error_malformed_tool_call,
        error.cause,
    )
    is GenerationError.IncompleteStream -> when {
        error.toolCallInFlight && error.stopReason != null -> context.getString(
            R.string.generation_error_incomplete_tool_stream_reason,
            error.provider,
            error.stopReason,
        )
        error.toolCallInFlight -> context.getString(
            R.string.generation_error_incomplete_tool_stream,
            error.provider,
        )
        error.stopReason != null -> context.getString(
            R.string.generation_error_incomplete_stream_reason,
            error.provider,
            error.stopReason,
        )
        else -> context.getString(
            R.string.generation_error_incomplete_stream,
            error.provider,
        )
    }
    is GenerationError.OutputTruncated -> context.getString(
        R.string.generation_error_output_truncated,
        error.stopReason ?: "max_tokens",
    )
    is GenerationError.ToolExecution -> context.getString(
        R.string.generation_error_tool_execution,
        error.toolName,
        localizedKnownOrNormalizedDetail(context, error.message),
    )
    is GenerationError.Transcription -> context.getString(
        R.string.generation_error_transcription,
        localizedKnownOrNormalizedDetail(context, error.message),
    )
    is GenerationError.Embedding -> context.getString(
        R.string.generation_error_embedding,
        localizedKnownOrNormalizedDetail(context, error.message),
    )
    is GenerationError.LocalModel ->
        localizedKnownOrNormalizedDetail(context, error.message)
    is GenerationError.Configuration ->
        localizedKnownOrNormalizedDetail(context, error.message)
    is GenerationError.RequestFormat -> context.getString(
        R.string.generation_error_request_format,
        error.provider,
        normalizeGenerationErrorDetail(error.details),
    )
    is GenerationError.Unknown -> localizedKnownOrNormalizedDetail(context, error.detail())
    GenerationError.Cancelled -> context.getString(R.string.generation_error_cancelled)
    GenerationError.Timeout -> context.getString(R.string.generation_error_timeout)
}

internal fun normalizePersistedGenerationErrorText(
    context: Context,
    rawText: String,
): String {
    val trimmed = rawText.trim()
    knownGenerationErrorDetail(trimmed)?.let {
        return context.getString(it.stringResourceId())
    }
    persistedNetworkErrorRegex.matchEntire(trimmed)?.let { match ->
        val statusCode = match.groupValues[1].toIntOrNull() ?: 0
        val detail = match.groupValues[2].trim()
        knownGenerationErrorDetail(detail)?.let {
            return context.getString(it.stringResourceId())
        }
        return if (statusCode <= 0) {
            context.getString(
                R.string.generation_error_network,
                normalizeGenerationErrorDetailForDisplay(detail),
            )
        } else {
            context.getString(
                R.string.generation_error_network_http,
                statusCode,
                normalizeGenerationErrorDetailForDisplay(detail),
            )
        }
    }
    persistedNetworkDetailRegex.matchEntire(trimmed)?.let { match ->
        val detail = match.groupValues[1].trim()
        knownGenerationErrorDetail(detail)?.let {
            return context.getString(it.stringResourceId())
        }
        return context.getString(
            R.string.generation_error_network,
            normalizeGenerationErrorDetailForDisplay(detail),
        )
    }
    return when {
        trimmed.equals(
            "Authentication failed. Please check your API key.",
            ignoreCase = true,
        ) -> context.getString(R.string.generation_error_authentication)
        trimmed.equals(
            "Rate limit exceeded. Please wait and try again.",
            ignoreCase = true,
        ) -> context.getString(R.string.generation_error_rate_limit)
        trimmed.equals("Generation cancelled.", ignoreCase = true) ->
            context.getString(R.string.generation_error_cancelled)
        trimmed.equals("Request timed out.", ignoreCase = true) ->
            context.getString(R.string.generation_error_timeout)
        trimmed.equals("An unexpected error occurred.", ignoreCase = true) ->
            context.getString(R.string.generation_error_unexpected)
        trimmed.startsWith("error:", ignoreCase = true) ->
            normalizeGenerationErrorDetailForDisplay(trimmed.substringAfter(':').trim())
        else -> normalizeGenerationErrorDetailForDisplay(trimmed)
    }
}

internal fun extractStructuredGenerationErrorDetail(detail: String): String? =
    extractStructuredProviderHttpErrorMessage(detail)

private fun normalizeGenerationErrorDetailForDisplay(detail: String): String =
    normalizeGenerationErrorDetail(
        extractStructuredGenerationErrorDetail(detail) ?: detail,
    )

internal fun normalizeGenerationErrorDetail(detail: String): String {
    val trimmed = detail.trim()
    if (trimmed.isEmpty() || isOpaqueGenerationErrorDetail(trimmed)) return trimmed
    val firstLetter = trimmed.indexOfFirst(Char::isLetter)
    if (firstLetter < 0 || !trimmed[firstLetter].isLowerCase()) return trimmed
    return buildString(trimmed.length) {
        append(trimmed, 0, firstLetter)
        append(trimmed[firstLetter].titlecase())
        append(trimmed, firstLetter + 1, trimmed.length)
    }
}

private fun localizedKnownOrNormalizedDetail(
    context: Context,
    detail: String,
): String = knownGenerationErrorDetail(detail)
    ?.let { context.getString(it.stringResourceId()) }
    ?: normalizeGenerationErrorDetail(detail)

private fun isOpaqueGenerationErrorDetail(detail: String): Boolean {
    val lower = detail.lowercase(Locale.ROOT)
    return detail.startsWith('{') ||
        detail.startsWith('[') ||
        lower.startsWith("http://") ||
        lower.startsWith("https://") ||
        lower.startsWith("java.") ||
        lower.startsWith("kotlin.") ||
        detail.contains('\n') ||
        opaqueGenerationIdentifierRegex.matches(detail) ||
        opaqueGenerationIdentifierRegex.matches(detail.substringBefore(':', ""))
}
