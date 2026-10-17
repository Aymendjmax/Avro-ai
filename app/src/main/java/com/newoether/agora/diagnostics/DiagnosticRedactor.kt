package com.newoether.agora.diagnostics

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction

/**
 * Permanently removes credentials before diagnostic payloads enter memory or durable storage.
 *
 * Scope is credentials only. Message text, tool arguments and tool results are captured verbatim:
 * a keyword match inside prose (for example `token:` or `key` mentioned by a user) must never
 * rewrite captured conversation content. Two strengths are used:
 * - [redactCredentialValues] for anything that can carry message content (bodies, wire lines,
 *   parsed stream content). It only masks credential-shaped values.
 * - [redactRequestMetadata] for request metadata (URLs, header values, identifiers) where no
 *   conversation content is expected and keyword-shaped secrets are common.
 */
internal object DiagnosticRedactor {
    private const val REDACTED_SECRET = "[REDACTED_SECRET]"
    private const val INVALID_URL = "[UNAVAILABLE_INVALID_URL]"
    private const val MAX_IDENTIFIER_CHARS = 1_024
    private const val MAX_HEADERS = 128

    /** Conch authenticates with `X-Conch-*` headers, so every such value is a credential. */
    private const val CONCH_KEY_PREFIX = "xconch"

    private val json = Json { ignoreUnknownKeys = true }

    fun captureUrl(rawUrl: String): CapturedDiagnosticText {
        val parsed = rawUrl.toHttpUrlOrNull()
        val sanitized = if (parsed == null) {
            INVALID_URL
        } else {
            val builder = parsed.newBuilder()
            if (parsed.username.isNotEmpty()) builder.username(REDACTED_SECRET)
            if (parsed.password.isNotEmpty()) builder.password(REDACTED_SECRET)
            parsed.queryParameterNames
                .filter(::isSecretKey)
                .forEach { name -> builder.setQueryParameter(name, REDACTED_SECRET) }
            redactRequestMetadata(builder.build().toString())
        }
        return capture(rawUrl, sanitized)
    }

    fun captureHeaders(headers: Map<String, String>): Map<String, String> = buildMap {
        headers.entries.take(MAX_HEADERS).forEach { (name, value) ->
            put(
                name.take(MAX_IDENTIFIER_CHARS),
                if (isSecretKey(name)) REDACTED_SECRET else redactRequestMetadata(value),
            )
        }
        if (headers.size > MAX_HEADERS) {
            put("[TRUNCATED_HEADERS]", (headers.size - MAX_HEADERS).toString())
        }
    }

    fun captureJson(
        rawJson: String,
        credentialValues: Collection<String> = emptyList(),
    ): CapturedDiagnosticText {
        val (input, inputTruncated) = boundedInput(rawJson)
        return capture(
            original = rawJson,
            sanitized = credentialText(input, credentialValues),
            alreadyTruncated = inputTruncated,
        )
    }
    fun credentialValues(headers: Map<String, String>): Set<String> = buildSet {
        headers.forEach { (name, value) ->
            if (!isSecretKey(name) || value.isBlank()) return@forEach
            add(value)
            BEARER_VALUE.matchEntire(value.trim())?.groupValues?.getOrNull(1)
                ?.takeIf(String::isNotBlank)
                ?.let(::add)
        }
    }

    fun captureWireLine(rawLine: String): CapturedDiagnosticText {
        val (input, inputTruncated) = boundedInput(rawLine)
        val trimmed = input.trimStart()
        val leading = input.substring(0, input.length - trimmed.length)
        val sanitized = when {
            trimmed.startsWith("data:") -> {
                val data = trimmed.removePrefix("data:").trimStart()
                if (data == "[DONE]") {
                    leading + "data: [DONE]"
                } else {
                    leading + "data: " + credentialText(data)
                }
            }
            trimmed.startsWith("{") || trimmed.startsWith("[") -> leading + credentialText(trimmed)
            else -> redactCredentialValues(input)
        }
        return capture(rawLine, sanitized, alreadyTruncated = inputTruncated)
    }

    fun captureContent(content: String): CapturedDiagnosticText {
        val (input, inputTruncated) = boundedInput(content)
        return capture(
            original = content,
            sanitized = redactCredentialValues(input),
            alreadyTruncated = inputTruncated,
        )
    }

    /** Export projection for a JSON body; content is preserved, only credentials are masked. */
    fun redactJsonContent(captured: CapturedDiagnosticText): CapturedDiagnosticText =
        project(captured, credentialText(captured.value))

    /** Export projection for one wire line; content is preserved, only credentials are masked. */
    fun redactWireContent(captured: CapturedDiagnosticText): CapturedDiagnosticText {
        val rawLine = captured.value
        val trimmed = rawLine.trimStart()
        val leading = rawLine.substring(0, rawLine.length - trimmed.length)
        val redacted = when {
            trimmed.startsWith("data:") -> {
                val data = trimmed.removePrefix("data:").trimStart()
                if (data == "[DONE]") {
                    leading + "data: [DONE]"
                } else {
                    leading + "data: " + credentialText(data)
                }
            }
            trimmed.startsWith("{") || trimmed.startsWith("[") -> leading + credentialText(trimmed)
            isSseControl(trimmed) -> rawLine
            else -> redactCredentialValues(rawLine)
        }
        return project(captured, redacted)
    }

    /** Export projection for parsed stream content; the text itself is preserved. */
    fun redactContent(captured: CapturedDiagnosticText): CapturedDiagnosticText =
        project(captured, redactCredentialValues(captured.value))

    fun safeIdentifier(value: String): String =
        redactRequestMetadata(value).take(MAX_IDENTIFIER_CHARS)

    /** Re-applies credential rules to a JSON text and returns it unchanged when it parses. */
    private fun credentialText(
        rawJson: String,
        credentialValues: Collection<String> = emptyList(),
    ): String =
        runCatching { json.parseToJsonElement(rawJson) }.getOrNull()
            ?.let { sanitizeCredentialElement(it, credentialValues) }
            ?.toString()
            ?: redactKnownCredentials(redactCredentialValues(rawJson), credentialValues)

    private fun sanitizeCredentialElement(
        element: JsonElement,
        credentialValues: Collection<String>,
    ): JsonElement {
        return when (element) {
            is JsonObject -> JsonObject(
                element.mapValues { (_, childValue) ->
                    sanitizeCredentialElement(childValue, credentialValues)
                },
            )
            is JsonArray -> JsonArray(
                element.map { child -> sanitizeCredentialElement(child, credentialValues) },
            )
            is JsonPrimitive -> if (element.isString) {
                JsonPrimitive(
                    redactKnownCredentials(
                        redactCredentialValues(element.content),
                        credentialValues,
                    ),
                )
            } else {
                element
            }
        }
    }
    private fun redactKnownCredentials(
        value: String,
        credentialValues: Collection<String>,
    ): String = credentialValues
        .asSequence()
        .filter(String::isNotBlank)
        .sortedByDescending(String::length)
        .fold(value) { sanitized, credential ->
            sanitized.replace(credential, REDACTED_SECRET)
        }

    private fun boundedInput(value: String): Pair<String, Boolean> {
        val bytes = value.toByteArray(Charsets.UTF_8)
        val oversized = bytes.size > DiagnosticCaptureStore.DEFAULT_MAX_PAYLOAD_BYTES
        return if (oversized) {
            decodeUtf8Prefix(bytes, DiagnosticCaptureStore.DEFAULT_MAX_PAYLOAD_BYTES) to true
        } else {
            value to false
        }
    }

    private fun capture(
        original: String,
        sanitized: String,
        alreadyTruncated: Boolean = false,
    ): CapturedDiagnosticText {
        val bytes = sanitized.toByteArray(Charsets.UTF_8)
        val oversized = bytes.size > DiagnosticCaptureStore.DEFAULT_MAX_PAYLOAD_BYTES
        return CapturedDiagnosticText(
            value = if (oversized) {
                decodeUtf8Prefix(bytes, DiagnosticCaptureStore.DEFAULT_MAX_PAYLOAD_BYTES)
            } else {
                sanitized
            },
            originalLength = original.length,
            truncated = alreadyTruncated || oversized,
            redacted = true,
        )
    }

    private fun project(
        original: CapturedDiagnosticText,
        sanitized: String,
    ): CapturedDiagnosticText = capture(
        original = original.value,
        sanitized = sanitized,
        alreadyTruncated = original.truncated,
    ).copy(originalLength = original.originalLength)

    private fun isSseControl(trimmed: String): Boolean =
        trimmed.isBlank() || trimmed.startsWith(":") || trimmed.startsWith("event:") ||
            trimmed.startsWith("id:") || trimmed.startsWith("retry:")

    private fun isSecretKey(key: String): Boolean {
        val normalized = key.normalizeKey()
        return normalized in SECRET_KEYS ||
            normalized.startsWith(CONCH_KEY_PREFIX) ||
            SECRET_KEY_SUFFIXES.any(normalized::endsWith)
    }

    private fun String.normalizeKey(): String =
        lowercase().filter(Char::isLetterOrDigit)

    /**
     * Masks values that are credentials by shape: bearer tokens and provider API key formats.
     * Ordinary prose, including the words `token`, `key` or `password`, is left untouched.
     */
    private fun redactCredentialValues(value: String): String {
        var result = BEARER_SECRET.replace(value) { match ->
            match.groupValues[1] + REDACTED_SECRET
        }
        SECRET_TOKEN_PATTERNS.forEach { pattern ->
            result = pattern.replace(result, REDACTED_SECRET)
        }
        return result
    }

    /** Stronger masking for request metadata, where conversation content is never expected. */
    private fun redactRequestMetadata(value: String): String {
        var result = PRIVATE_KEY_BLOCK.replace(value, REDACTED_SECRET)
        result = PRIVATE_KEY_PREFIX.replace(result, REDACTED_SECRET)
        result = NAMED_SECRET.replace(result) { match ->
            match.groupValues[1] + match.groupValues[2] + REDACTED_SECRET
        }
        return redactCredentialValues(result)
    }

    private fun decodeUtf8Prefix(bytes: ByteArray, maxBytes: Int): String =
        Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.IGNORE)
            .onUnmappableCharacter(CodingErrorAction.IGNORE)
            .decode(ByteBuffer.wrap(bytes, 0, maxBytes))
            .toString()

    private val SECRET_KEYS = setOf(
        "authorization",
        "proxyauthorization",
        "apikey",
        "xapikey",
        "xgoogapikey",
        "cookie",
        "setcookie",
        "key",
        "password",
        "passwd",
        "secret",
        "clientsecret",
        "accesstoken",
        "refreshtoken",
        "securitytoken",
        "sessiontoken",
        "token",
        "signature",
        "sig",
        "credential",
        "xamzcredential",
        "xamzsignature",
        "xamzsecuritytoken",
        "xgoogcredential",
        "xgoogsignature",
        "googleaccessid",
        "awsaccesskeyid",
        "proxyusername",
        "proxypassword",
    )
    private val SECRET_KEY_SUFFIXES = setOf(
        "apikey",
        "accesstoken",
        "refreshtoken",
        "clientsecret",
        "securitytoken",
        "sessiontoken",
        "signature",
        "credential",
    )
    private val BEARER_SECRET = Regex(
        """(?i)\b(Bearer\s+)[A-Za-z0-9._~+/=-]+""",
    )
    private val BEARER_VALUE = Regex("""(?i)Bearer\s+(.+)""")
    private val NAMED_SECRET = Regex(
        """(?i)\b(api[_-]?key|access[_-]?token|refresh[_-]?token|security[_-]?token|session[_-]?token|authorization|proxy[_-]?authorization|cookie|password|secret|signature|credential|token)\s*([=:])\s*["']?[^\s"'&,}]+""",
    )
    private val PRIVATE_KEY_BLOCK = Regex(
        """-----BEGIN [A-Z0-9 ]*PRIVATE KEY-----.*?-----END [A-Z0-9 ]*PRIVATE KEY-----""",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
    )
    private val PRIVATE_KEY_PREFIX = Regex(
        """-----BEGIN [A-Z0-9 ]*PRIVATE KEY-----.*""",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
    )
    private val SECRET_TOKEN_PATTERNS = listOf(
        Regex("""\bsk-[A-Za-z0-9_-]{12,}\b"""),
        Regex("""\bAIza[A-Za-z0-9_-]{20,}\b"""),
        Regex("""\bgh[pousr]_[A-Za-z0-9]{20,}\b"""),
        Regex("""\bxox[baprs]-[A-Za-z0-9-]{12,}\b"""),
        Regex("""\beyJ[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+\b"""),
    )
}
