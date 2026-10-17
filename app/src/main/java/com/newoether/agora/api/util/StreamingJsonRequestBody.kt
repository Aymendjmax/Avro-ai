package com.newoether.agora.api.util

import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody
import okio.BufferedSink
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.security.MessageDigest
import java.util.Base64
import java.util.UUID

private val JSON_MEDIA_TYPE: MediaType = "application/json; charset=utf-8".toMediaType()

internal data class StreamingJsonRequest(
    val body: RequestBody,
    val diagnosticJson: String,
)

/**
 * Projects image files into JSON as opaque placeholders. The final request body replaces each
 * placeholder with base64 while OkHttp writes the request, so image bytes never become a String
 * or a whole-request byte array. The body reopens each immutable attachment on every write and is
 * therefore replayable for endpoint fallback and transport retries.
 */
internal class Base64FileRegistry {
    private data class FilePart(
        val placeholder: String,
        val file: File,
        val byteCount: Long,
        val lastModified: Long,
        val sha256: ByteArray,
    ) {
        val diagnosticMarker: String
            get() = "[STREAMED_BASE64:${byteCount}_BYTES]"

        fun metadataMatches(): Boolean =
            file.isFile && file.length() == byteCount && file.lastModified() == lastModified
    }

    private val files = mutableListOf<FilePart>()

    fun register(path: String): String? {
        val part = runCatching {
            val file = File(path).canonicalFile
            if (!file.isFile) return@runCatching null
            val byteCount = file.length()
            val lastModified = file.lastModified()
            val sha256 = calculateSha256(file)
            if (!file.isFile || file.length() != byteCount || file.lastModified() != lastModified) {
                return@runCatching null
            }
            FilePart(
                placeholder = "__AGORA_BASE64_${UUID.randomUUID()}__",
                file = file,
                byteCount = byteCount,
                lastModified = lastModified,
                sha256 = sha256,
            )
        }.getOrNull() ?: return null
        files += part
        return part.placeholder
    }

    fun prepare(templateJson: String): StreamingJsonRequest? {
        if (files.isEmpty()) return null
        val positionedFiles = files.map { part ->
            val position = templateJson.indexOf(part.placeholder)
            require(position >= 0) { "Base64 placeholder is missing from serialized request" }
            require(templateJson.indexOf(part.placeholder, position + part.placeholder.length) < 0) {
                "Base64 placeholder occurs more than once in serialized request"
            }
            position to part
        }.sortedBy { it.first }

        val segments = ArrayList<ByteArray>(positionedFiles.size + 1)
        val orderedFiles = ArrayList<FilePart>(positionedFiles.size)
        val diagnosticJson = StringBuilder(templateJson.length)
        var cursor = 0
        for ((position, part) in positionedFiles) {
            require(position >= cursor) { "Base64 placeholders overlap" }
            val segment = templateJson.substring(cursor, position)
            segments += segment.toByteArray(Charsets.UTF_8)
            orderedFiles += part
            diagnosticJson.append(segment).append(part.diagnosticMarker)
            cursor = position + part.placeholder.length
        }
        val finalSegment = templateJson.substring(cursor)
        segments += finalSegment.toByteArray(Charsets.UTF_8)
        diagnosticJson.append(finalSegment)

        return StreamingJsonRequest(
            body = ReplayableJsonRequestBody(segments, orderedFiles),
            diagnosticJson = diagnosticJson.toString(),
        )
    }

    private class ReplayableJsonRequestBody(
        private val segments: List<ByteArray>,
        private val files: List<FilePart>,
    ) : RequestBody() {
        private val encodedContentLength = calculateContentLength(segments, files)

        override fun contentType(): MediaType = JSON_MEDIA_TYPE

        override fun contentLength(): Long = encodedContentLength

        override fun writeTo(sink: BufferedSink) {
            files.forEachIndexed { index, part ->
                if (!part.metadataMatches()) {
                    throw IOException("Image attachment changed before request upload")
                }
                sink.write(segments[index])
                val digest = MessageDigest.getInstance("SHA-256")
                val base64Output = Base64.getEncoder().wrap(
                    NonClosingOutputStream(sink.outputStream()),
                )
                var completed = false
                try {
                    part.file.inputStream().use { input ->
                        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                        var remaining = part.byteCount
                        while (remaining > 0L) {
                            val read = input.read(
                                buffer,
                                0,
                                minOf(buffer.size.toLong(), remaining).toInt(),
                            )
                            if (read < 0) {
                                throw IOException("Image attachment changed during request upload")
                            }
                            digest.update(buffer, 0, read)
                            base64Output.write(buffer, 0, read)
                            remaining -= read
                        }
                        if (input.read() >= 0) {
                            throw IOException("Image attachment changed during request upload")
                        }
                    }
                    if (!part.metadataMatches() || !digest.digest().contentEquals(part.sha256)) {
                        throw IOException("Image attachment changed during request upload")
                    }
                    completed = true
                } finally {
                    if (completed) base64Output.close()
                }
            }
            sink.write(segments.last())
        }
    }

    private class NonClosingOutputStream(
        private val delegate: OutputStream,
    ) : OutputStream() {
        override fun write(value: Int) = delegate.write(value)

        override fun write(buffer: ByteArray, offset: Int, length: Int) =
            delegate.write(buffer, offset, length)

        override fun flush() = delegate.flush()

        override fun close() = Unit
    }

    private companion object {
        fun calculateSha256(file: File): ByteArray {
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    digest.update(buffer, 0, read)
                }
            }
            return digest.digest()
        }

        fun calculateContentLength(
            segments: List<ByteArray>,
            files: List<FilePart>,
        ): Long {
            var total = 0L
            segments.forEach { segment -> total = Math.addExact(total, segment.size.toLong()) }
            files.forEach { part ->
                require(part.byteCount >= 0L) { "Image attachment length is invalid" }
                val completeGroups = part.byteCount / 3L
                val remainderLength = if (part.byteCount % 3L == 0L) 0L else 4L
                val encodedLength = Math.addExact(
                    Math.multiplyExact(completeGroups, 4L),
                    remainderLength,
                )
                total = Math.addExact(total, encodedLength)
            }
            return total
        }
    }
}
