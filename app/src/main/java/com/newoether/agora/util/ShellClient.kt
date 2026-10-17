package com.newoether.agora.util

import java.io.ByteArrayOutputStream
import java.io.InputStream
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

internal const val SHELL_FILE_READ_MAX_BYTES = 1_048_576L
internal const val SHELL_FILE_WRITE_MAX_BYTES = 1_048_576
internal const val SHELL_FILE_EDIT_MAX_BYTES = 16_777_216L
internal const val SHELL_FILE_GLOB_MAX_MATCHES = 1_000
internal const val SHELL_FILE_GREP_MAX_MATCHES = 500
internal const val SHELL_FILE_GREP_MAX_FILE_BYTES = 500L * 1024L
internal const val SHELL_FILE_GREP_MAX_CONTENT_CHARS = 500
internal const val SHELL_COMMAND_MAX_BYTES = 64 * 1024
internal const val SHELL_WORKDIR_MAX_BYTES = 32 * 1024
internal const val SHELL_COMMAND_OUTPUT_MAX_BYTES = 1 shl 20

data class ShellFileReadResult(
    val content: String,
    val lines: Int,
    val totalLines: Int,
    val totalBytes: Long,
    val returnedBytes: Long,
    val offset: Long,
    val limit: Long,
    val truncated: Boolean,
    val error: String? = null,
)

data class ShellFileEditResult(
    val replacements: Int,
    val sha256: String = "",
    val error: String? = null,
)

internal fun shellFileLineCount(content: String): Int =
    if (content.isEmpty()) 0 else content.count { it == '\n' } + 1

internal fun String.shellUtf8Prefix(maxBytes: Int): String {
    if (maxBytes <= 0) return ""
    var index = 0
    var bytes = 0
    while (index < length) {
        val codePoint = Character.codePointAt(this, index)
        val nextBytes = when {
            codePoint <= 0x7f -> 1
            codePoint <= 0x7ff -> 2
            codePoint <= 0xffff -> 3
            else -> 4
        }
        if (bytes + nextBytes > maxBytes) break
        bytes += nextBytes
        index += Character.charCount(codePoint)
    }
    return substring(0, index)
}

internal fun InputStream.readBoundedShellOutput(
    maxBytes: Int = SHELL_COMMAND_OUTPUT_MAX_BYTES,
): Pair<String, Boolean> {
    require(maxBytes >= 0)
    val retained = ByteArrayOutputStream(maxBytes.coerceAtMost(DEFAULT_BUFFER_SIZE))
    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
    var truncated = false
    while (true) {
        val count = read(buffer)
        if (count < 0) break
        val retainedCount = minOf(count, (maxBytes - retained.size()).coerceAtLeast(0))
        if (retainedCount > 0) retained.write(buffer, 0, retainedCount)
        if (retainedCount < count) truncated = true
    }

    val bytes = retained.toByteArray()
    val completeBytes = if (truncated) completeUtf8PrefixLength(bytes) else bytes.size
    return String(bytes, 0, completeBytes, Charsets.UTF_8) to truncated
}

private fun completeUtf8PrefixLength(bytes: ByteArray): Int {
    if (bytes.isEmpty()) return 0
    var leadIndex = bytes.lastIndex
    while (leadIndex >= 0 && bytes[leadIndex].toInt() and 0xc0 == 0x80) leadIndex -= 1
    if (leadIndex < 0) return 0
    val lead = bytes[leadIndex].toInt() and 0xff
    val expectedBytes = when {
        lead and 0x80 == 0 -> 1
        lead and 0xe0 == 0xc0 -> 2
        lead and 0xf0 == 0xe0 -> 3
        lead and 0xf8 == 0xf0 -> 4
        else -> 1
    }
    return if (bytes.size - leadIndex < expectedBytes) leadIndex else bytes.size
}

internal fun fileEditMatchRanges(content: String, oldString: String): List<IntRange> {
    require(oldString.isNotEmpty())

    val normalizedContent = StringBuilder(content.length)
    val originalBoundaries = IntArray(content.length + 1)
    var originalIndex = 0
    var normalizedLength = 0
    originalBoundaries[0] = 0
    while (originalIndex < content.length) {
        if (
            content[originalIndex] == '\r' &&
            originalIndex + 1 < content.length &&
            content[originalIndex + 1] == '\n'
        ) {
            normalizedContent.append('\n')
            originalIndex += 2
        } else {
            normalizedContent.append(content[originalIndex])
            originalIndex += 1
        }
        normalizedLength += 1
        originalBoundaries[normalizedLength] = originalIndex
    }

    val normalizedText = normalizedContent.toString()
    val normalizedOldString = oldString.replace("\r\n", "\n")
    val matches = mutableListOf<IntRange>()
    var searchIndex = 0
    while (searchIndex <= normalizedText.length - normalizedOldString.length) {
        val normalizedStart = normalizedText.indexOf(normalizedOldString, searchIndex)
        if (normalizedStart < 0) break
        val normalizedEnd = normalizedStart + normalizedOldString.length
        matches += originalBoundaries[normalizedStart] until originalBoundaries[normalizedEnd]
        searchIndex = normalizedEnd
    }
    return matches
}

internal fun replaceFileEditMatches(
    content: String,
    matches: List<IntRange>,
    newString: String,
): String {
    if (matches.isEmpty()) return content

    val fileLineEnding = preferredFileEditLineEnding(content)
    return buildString {
        var sourceIndex = 0
        matches.forEach { match ->
            require(match.first >= sourceIndex && match.last < content.length)
            append(content, sourceIndex, match.first)
            val matchedText = content.substring(match)
            val lineEnding = preferredFileEditLineEnding(matchedText)
                ?: fileLineEnding
                ?: preferredFileEditLineEnding(newString)
                ?: "\n"
            append(newString.withFileEditLineEnding(lineEnding))
            sourceIndex = match.last + 1
        }
        append(content, sourceIndex, content.length)
    }
}

internal fun editShellFileContent(
    content: String,
    oldString: String,
    newString: String,
    replaceAll: Boolean,
): Pair<String?, ShellFileEditResult> {
    val matches = fileEditMatchRanges(content, oldString)
    if (matches.isEmpty()) {
        return null to ShellFileEditResult(0, error = "old_string not found in file")
    }
    if (matches.size > 1 && !replaceAll) {
        return null to ShellFileEditResult(
            0,
            error = "found ${matches.size} matches of old_string; set replace_all=true or provide a unique match",
        )
    }
    val selectedMatches = if (replaceAll) matches else matches.take(1)
    return replaceFileEditMatches(content, selectedMatches, newString) to
        ShellFileEditResult(selectedMatches.size)
}

internal fun shellFileSha256(content: ByteArray): String =
    java.security.MessageDigest.getInstance("SHA-256")
        .digest(content)
        .joinToString("") { "%02x".format(it) }

private fun preferredFileEditLineEnding(text: String): String? {
    var crlfCount = 0
    var lfCount = 0
    var firstLineEnding: String? = null
    var index = 0
    while (index < text.length) {
        if (text[index] == '\r' && index + 1 < text.length && text[index + 1] == '\n') {
            crlfCount += 1
            if (firstLineEnding == null) firstLineEnding = "\r\n"
            index += 2
        } else {
            if (text[index] == '\n') {
                lfCount += 1
                if (firstLineEnding == null) firstLineEnding = "\n"
            }
            index += 1
        }
    }
    return when {
        crlfCount > lfCount -> "\r\n"
        lfCount > crlfCount -> "\n"
        else -> firstLineEnding
    }
}

private fun String.withFileEditLineEnding(lineEnding: String): String {
    if ('\n' !in this) return this
    val normalized = replace("\r\n", "\n")
    return if (lineEnding == "\n") normalized else normalized.replace("\n", lineEnding)
}

internal fun describeConchConnectionFailure(serverUrl: String, error: Exception): String =
    describeConchRequestFailure(serverUrl, "public-key request", error)

internal fun describeConchRequestFailure(
    serverUrl: String,
    operation: String,
    error: Exception,
): String =
    when (error) {
        is java.net.UnknownHostException ->
            "Cannot resolve Conch host for $serverUrl: ${error.message ?: "unknown host"}"
        is java.net.ConnectException ->
            "Cannot connect to Conch at $serverUrl: ${error.message ?: "connection refused"}"
        is java.net.SocketTimeoutException,
        is java.io.InterruptedIOException ->
            "Conch $operation timed out at $serverUrl"
        is javax.net.ssl.SSLException ->
            "TLS connection to Conch at $serverUrl failed: ${error.message ?: "SSL error"}"
        else ->
            "Conch $operation to $serverUrl failed: ${error.message ?: error.javaClass.simpleName}"
    }

class ShellClient(
    private val serverUrl: String,
    private val apiKey: String,
    cachedPublicKey: String = ""
) {
    private var serverPublicKey: java.security.PublicKey? = null
    private var securityVerified = false
    private var currentAesKey: ByteArray? = null
    private var currentKeyPair: java.security.KeyPair? = null
    var lastError: String? = null
        private set

    init {
        if (cachedPublicKey.isNotBlank()) {
            try {
                serverPublicKey = ShellCrypto.decodePublicKey(cachedPublicKey)
            } catch (_: Exception) {
                // Will fetch fresh
            }
        }
    }

    suspend fun fetchPublicKey(): Boolean {
        if (securityVerified && serverPublicKey != null) return true
        if (apiKey.isBlank()) {
            lastError = "Conch authentication is disabled locally; no public-key exchange is needed"
            return false
        }
        return try {
            val challenge = ShellCrypto.generateNonce()
            val response = ConchNetwork.getTextResponse(
                "$serverUrl/public-key?challenge=$challenge",
                emptyMap()
            )
            if (!response.isSuccessful) {
                val detail = response.body.take(240).ifBlank { "empty response" }
                lastError = "Conch at $serverUrl returned HTTP ${response.code}: $detail"
                DebugLog.e("ShellClient", lastError!!)
                return false
            }
            val handshake = ConchHandshake.decode(response.body)
            if (!handshake.verify(apiKey, challenge)) {
                lastError = "Conch security handshake failed at $serverUrl. Check the API key and update Conch before sending requests."
                DebugLog.e("ShellClient", lastError!!)
                return false
            }
            serverPublicKey = ShellCrypto.decodePublicKey(handshake.public_key)
            securityVerified = true
            lastError = null
            true
        } catch (e: Exception) {
            lastError = describeConchConnectionFailure(serverUrl, e)
            DebugLog.w("ShellClient", lastError!!)
            false
        }
    }

    fun getServerPublicKeyBase64(): String? {
        return serverPublicKey?.let { ShellCrypto.encodePublicKey(it) }
    }

    data class PreparedRequest(
        val body: String,
        val headers: Map<String, String>,
        val isEncrypted: Boolean,
        val serverUrl: String
    )

    fun prepareRequest(
        command: String,
        timeoutMs: Int,
        workdir: String
    ): PreparedRequest {
        val jsonBody = buildJsonBody(command, timeoutMs, workdir)

        if (apiKey.isBlank()) {
            return PreparedRequest(jsonBody, mapOf("Content-Type" to "application/json"), false, serverUrl)
        }

        check(securityVerified) { "Conch security handshake is required before sending requests" }
        val pubKey = serverPublicKey
            ?: throw IllegalStateException("Server public key not available. Call fetchPublicKey() first.")

        // Generate ephemeral key pair and derive AES key
        val ephemeralKP = ShellCrypto.generateEphemeralKeyPair()
        val aesKey = ShellCrypto.deriveAesKey(ephemeralKP.private, pubKey)
        currentAesKey = aesKey
        currentKeyPair = ephemeralKP

        // Encrypt body
        val encryptedBody = ShellCrypto.encrypt(aesKey, jsonBody.toByteArray(Charsets.UTF_8))
        val bodyBytes = encryptedBody.toByteArray(Charsets.UTF_8)
        val bodySha256 = ShellCrypto.sha256Hex(bodyBytes)
        val timestamp = System.currentTimeMillis() / 1000
        val nonce = ShellCrypto.generateNonce()
        val clientPubKey = ShellCrypto.encodePublicKey(ephemeralKP.public)
        val signature = ShellCrypto.sign(apiKey, timestamp, "POST", "/execute", bodySha256, nonce, clientPubKey)

        val headers = mapOf(
            "Content-Type" to "application/octet-stream",
            "X-Timestamp" to timestamp.toString(),
            "X-Signature" to signature,
            "X-Nonce" to nonce,
            "X-Encryption" to "v2",
            "X-Client-Public-Key" to clientPubKey
        )

        return PreparedRequest(encryptedBody, headers, true, serverUrl)
    }

    fun decryptSseData(encryptedData: String): String {
        val key = currentAesKey ?: throw IllegalStateException("No session key")
        return String(ShellCrypto.decrypt(key, encryptedData), Charsets.UTF_8)
    }

    fun getSessionKey(): ByteArray? = currentAesKey

    private fun buildJsonBody(command: String, timeoutMs: Int, workdir: String): String {
        return buildJsonObject {
            put("command", command)
            put("timeout_ms", timeoutMs)
            if (workdir.isNotBlank()) {
                put("workdir", workdir)
            }
        }.toString()
    }

    // --- File API ---

    data class FileImageResult(
        val data: String,
        val mimeType: String,
        val size: Long,
        val error: String? = null,
    )

    data class GrepMatch(
        val path: String,
        val line: Int,
        val content: String
    )

    private suspend fun encryptedPost(
        path: String,
        payload: String,
        callTimeoutMillis: Long? = null,
    ): String {
        if (apiKey.isBlank()) {
            val response = try {
                ConchNetwork.postTextResponse(
                    "$serverUrl$path",
                    payload,
                    mapOf("Content-Type" to "application/json"),
                    callTimeoutMillis,
                )
            } catch (e: Exception) {
                throw IllegalStateException(
                    describeConchRequestFailure(serverUrl, "$path request", e),
                    e,
                )
            }
            if (!response.isSuccessful) {
                throw IllegalStateException(
                    "Conch at $serverUrl returned HTTP ${response.code}: " +
                        response.body.take(240).ifBlank { "empty response" },
                )
            }
            return response.body
        }
        // Lazily establish the encrypted session. The file endpoints need the server
        // public key just like /execute does, but (unlike executeCommand) nothing
        // pre-fetches it for the file tools — so fetch it here on first use.
        if (!securityVerified && !fetchPublicKey()) {
            throw IllegalStateException(lastError ?: "Failed to fetch server public key")
        }
        val pubKey = serverPublicKey
            ?: throw IllegalStateException("Server public key not available. Call fetchPublicKey() first.")

        val ephemeralKP = ShellCrypto.generateEphemeralKeyPair()
        val aesKey = ShellCrypto.deriveAesKey(ephemeralKP.private, pubKey)
        val encryptedBody = ShellCrypto.encrypt(aesKey, payload.toByteArray(Charsets.UTF_8))
        val bodyBytes = encryptedBody.toByteArray(Charsets.UTF_8)
        val bodySha256 = ShellCrypto.sha256Hex(bodyBytes)
        val timestamp = System.currentTimeMillis() / 1000
        val nonce = ShellCrypto.generateNonce()
        val clientPubKey = ShellCrypto.encodePublicKey(ephemeralKP.public)
        val signature = ShellCrypto.sign(apiKey, timestamp, "POST", path, bodySha256, nonce, clientPubKey)

        val headers = mapOf(
            "Content-Type" to "application/octet-stream",
            "X-Timestamp" to timestamp.toString(),
            "X-Signature" to signature,
            "X-Nonce" to nonce,
            "X-Encryption" to "v2",
            "X-Client-Public-Key" to clientPubKey
        )

        val response = try {
            ConchNetwork.postTextResponse(
                "$serverUrl$path",
                encryptedBody,
                headers,
                callTimeoutMillis,
            )
        } catch (e: Exception) {
            throw IllegalStateException(
                describeConchRequestFailure(serverUrl, "$path request", e),
                e,
            )
        }
        if (!response.isSuccessful) {
            throw IllegalStateException(
                "Conch at $serverUrl returned HTTP ${response.code}: " +
                    response.body.take(240).ifBlank { "empty response" },
            )
        }

        if (!ShellCrypto.verifyResponseSignature(aesKey, response.code, response.body, response.signature)) {
            throw IllegalStateException("Conch response authentication failed at $serverUrl")
        }
        val plaintext = try {
            ShellCrypto.decrypt(aesKey, response.body)
        } catch (e: Exception) {
            throw IllegalStateException(
                "Conch response decryption failed at $serverUrl: ${e.message}",
                e,
            )
        }
        return String(plaintext, Charsets.UTF_8)
    }

    private suspend fun filePost(path: String, payload: String): String =
        encryptedPost(path, payload)

    suspend fun fileRead(path: String, offset: Long = 0, limit: Long = 0): ShellFileReadResult {
        val effectiveLimit = if (limit in 1..SHELL_FILE_READ_MAX_BYTES) {
            limit
        } else {
            SHELL_FILE_READ_MAX_BYTES
        }
        val normalizedOffset = offset.coerceAtLeast(0)
        val payload = buildJsonBodyFileMixed(mapOf(
            "path" to path,
            "offset" to normalizedOffset,
            "limit" to effectiveLimit
        ))
        val jsonStr = filePost("/file/read", payload)
        val json = Json.parseToJsonElement(jsonStr).jsonObject
        val error = json["error"]?.jsonPrimitive?.content
        if (error != null) {
            return ShellFileReadResult(
                content = "",
                lines = 0,
                totalLines = 0,
                totalBytes = 0,
                returnedBytes = 0,
                offset = normalizedOffset,
                limit = effectiveLimit,
                truncated = false,
                error = error,
            )
        }
        val content = json["content"]?.jsonPrimitive?.content.orEmpty()
        val totalBytes = json["size"]?.jsonPrimitive?.content?.toLongOrNull() ?: 0L
        return ShellFileReadResult(
            content = content,
            lines = json["lines"]?.jsonPrimitive?.content?.toIntOrNull()
                ?: shellFileLineCount(content),
            totalLines = json["totalLines"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0,
            totalBytes = totalBytes,
            returnedBytes = content.toByteArray(Charsets.UTF_8).size.toLong(),
            offset = normalizedOffset.coerceAtMost(totalBytes),
            limit = effectiveLimit,
            truncated = json["truncated"]?.jsonPrimitive?.content?.toBooleanStrictOrNull()
                ?: (normalizedOffset + content.toByteArray(Charsets.UTF_8).size < totalBytes),
        )
    }

    suspend fun fileImage(path: String): FileImageResult {
        val payload = buildJsonBodyFile(mapOf("path" to path))
        val jsonStr = filePost("/file/image", payload)
        val json = Json.parseToJsonElement(jsonStr).jsonObject
        val error = json["error"]?.jsonPrimitive?.content
        if (error != null) {
            return FileImageResult("", "", 0L, error = error)
        }
        return FileImageResult(
            data = json["data"]?.jsonPrimitive?.content.orEmpty(),
            mimeType = json["mimeType"]?.jsonPrimitive?.content.orEmpty(),
            size = json["size"]?.jsonPrimitive?.content?.toLongOrNull() ?: 0L,
        )
    }

    suspend fun fileWrite(path: String, content: String): String? {
        val payload = buildJsonBodyFile(mapOf(
            "path" to path,
            "content" to content
        ))
        val jsonStr = filePost("/file/write", payload)
        val json = Json.parseToJsonElement(jsonStr).jsonObject
        return json["error"]?.jsonPrimitive?.content
    }

    suspend fun fileEdit(
        path: String,
        oldString: String,
        newString: String,
        replaceAll: Boolean,
    ): ShellFileEditResult {
        val payload = buildJsonBodyFileMixed(mapOf(
            "path" to path,
            "old_string" to oldString,
            "new_string" to newString,
            "replace_all" to replaceAll,
        ))
        val jsonStr = filePost("/file/edit", payload)
        val json = Json.parseToJsonElement(jsonStr).jsonObject
        val error = json["error"]?.jsonPrimitive?.content
        if (error != null) return ShellFileEditResult(0, error = error)
        return ShellFileEditResult(
            replacements = json["replacements"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0,
            sha256 = json["sha256"]?.jsonPrimitive?.content.orEmpty(),
        )
    }

    suspend fun fileGlob(
        pattern: String,
        basePath: String = "",
        depth: Int? = null,
    ): Result<Pair<List<String>, Boolean>> {
        val params = mutableMapOf<String, Any>("pattern" to pattern)
        if (basePath.isNotBlank()) params["path"] = basePath
        if (depth != null) params["depth"] = depth
        val payload = buildJsonBodyFileMixed(params)
        val jsonStr = filePost("/file/glob", payload)
        val json = Json.parseToJsonElement(jsonStr).jsonObject
        val error = json["error"]?.jsonPrimitive?.content
        if (error != null) return Result.failure(Exception(error))
        val files = json["files"]?.jsonArray?.map { it.jsonPrimitive.content } ?: emptyList()
        val truncated = json["truncated"]?.jsonPrimitive?.content?.toBooleanStrictOrNull() ?: false
        return Result.success(files to truncated)
    }

    suspend fun fileGrep(
        pattern: String,
        basePath: String = "",
        fileGlob: String = "",
    ): Result<Pair<List<GrepMatch>, Boolean>> {
        val params = mutableMapOf("pattern" to pattern)
        if (basePath.isNotBlank()) params["path"] = basePath
        if (fileGlob.isNotBlank()) params["glob"] = fileGlob
        val payload = buildJsonBodyFile(params)
        val jsonStr = filePost("/file/grep", payload)
        val json = Json.parseToJsonElement(jsonStr).jsonObject
        val error = json["error"]?.jsonPrimitive?.content
        if (error != null) return Result.failure(Exception(error))
        val matches = json["matches"]?.jsonArray?.map {
            val obj = it.jsonObject
            GrepMatch(
                path = obj["path"]?.jsonPrimitive?.content ?: "",
                line = obj["line"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0,
                content = obj["content"]?.jsonPrimitive?.content ?: ""
            )
        } ?: emptyList()
        val truncated = json["truncated"]?.jsonPrimitive?.content?.toBooleanStrictOrNull() ?: false
        return Result.success(matches to truncated)
    }

    private fun buildJsonBodyFileMixed(params: Map<String, Any>): String {
        return buildJsonObject {
            for ((key, value) in params) {
                when (value) {
                    is Long -> put(key, value)
                    is Int -> put(key, value)
                    is Boolean -> put(key, value)
                    else -> put(key, value.toString())
                }
            }
        }.toString()
    }

    private fun buildJsonBodyFile(params: Map<String, String>): String {
        return buildJsonObject {
            for ((key, value) in params) {
                put(key, value)
            }
        }.toString()
    }

    suspend fun startJob(
        command: String,
        timeoutMs: Int,
        workdir: String,
    ): String = encryptedPost(
        "/jobs/start",
        buildJsonBody(command, timeoutMs, workdir),
    )

    suspend fun listJobs(): String =
        encryptedPost("/jobs/list", "{}")

    suspend fun getJob(jobId: String): String =
        encryptedPost("/jobs/get", buildJsonObject { put("job_id", jobId) }.toString())

    suspend fun stopJob(
        jobId: String,
        callTimeoutMillis: Long? = null,
    ): String = encryptedPost(
        "/jobs/stop",
        buildJsonObject { put("job_id", jobId) }.toString(),
        callTimeoutMillis,
    )

    suspend fun acknowledgeJob(jobId: String): String =
        encryptedPost("/jobs/ack", buildJsonObject { put("job_id", jobId) }.toString())

}
