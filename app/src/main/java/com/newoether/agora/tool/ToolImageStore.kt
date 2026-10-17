package com.newoether.agora.tool

import android.content.Context
import android.graphics.BitmapFactory
import android.util.Base64
import android.util.Base64InputStream
import com.newoether.agora.model.ToolImageAttachment
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest
import java.util.UUID

/**
 * One binary-safe persistence boundary for images returned by any tool transport.
 *
 * Tool servers are untrusted inputs. The store bounds the encoded and decoded payload, verifies
 * that Android can identify real raster dimensions, writes into app-private storage, fsyncs, and
 * atomically publishes the final file. Both MCP and built-in Conch tools use this exact path.
 */
class ToolImageStore(
    context: Context,
    private val directory: File = File(context.applicationContext.filesDir, "tool-media"),
) {
    companion object {
        const val MAX_IMAGE_BYTES = 20L * 1024L * 1024L
    }

    /** Binary streams retain the same validation/atomic-file owner without an image-sized byte array. */
    fun persistStream(
        input: InputStream,
        mimeType: String,
        filePrefix: String = "tool",
    ): ToolImageAttachment {
        val mime = mimeType.substringBefore(';').trim().lowercase()
        if (!mime.startsWith("image/")) throw IOException("Unsupported tool image type")
        if (!directory.exists() && !directory.mkdirs()) throw IOException("Could not create tool media directory")
        val destination = File(directory, "${safePrefix(filePrefix)}_${UUID.randomUUID()}.${extensionFor(mime)}")
        val temporary = File(directory, "." + destination.name + ".tmp")
        val digest = MessageDigest.getInstance("SHA-256")
        var size = 0L
        try {
            temporary.outputStream().use { output ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    size += count
                    if (size > MAX_IMAGE_BYTES) throw IOException("Tool image exceeds its byte bound")
                    output.write(buffer, 0, count)
                    digest.update(buffer, 0, count)
                }
                output.fd.sync()
            }
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(temporary.absolutePath, bounds)
            if (size == 0L || bounds.outWidth <= 0 || bounds.outHeight <= 0)
                throw IOException("Tool returned an unsupported or invalid image")
            if (!temporary.renameTo(destination)) throw IOException("Could not finalize tool image")
            return ToolImageAttachment(destination.absolutePath, mime, size, bounds.outWidth, bounds.outHeight,
                digest.digest().joinToString("") { "%02x".format(it) })
        } finally { temporary.delete() }
    }


    fun persistBase64(
        data: String,
        mimeType: String,
        filePrefix: String = "tool",
    ): ToolImageAttachment {
        val estimatedBytes = data.length.toLong() * 3L / 4L
        if (estimatedBytes > MAX_IMAGE_BYTES + 3L) {
            throw IOException("Tool image exceeds ${MAX_IMAGE_BYTES / (1024 * 1024)} MB")
        }
        val encoded = object : InputStream() {
            private var position = 0

            override fun read(): Int {
                if (position >= data.length) return -1
                val value = data[position++].code
                if (value > 127) throw IOException("Tool image contains invalid base64")
                return value
            }

            override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                if (offset < 0 || length < 0 || offset > buffer.size - length) {
                    throw IndexOutOfBoundsException()
                }
                if (length == 0) return 0
                if (position >= data.length) return -1
                val count = minOf(length, data.length - position)
                for (index in 0 until count) {
                    val value = data[position++].code
                    if (value > 127) throw IOException("Tool image contains invalid base64")
                    buffer[offset + index] = value.toByte()
                }
                return count
            }
        }
        return Base64InputStream(encoded, Base64.DEFAULT).use { decoded ->
            persistStream(decoded, mimeType, filePrefix)
        }
    }

    fun persistGeneratedBytes(
        bytes: ByteArray,
        filePrefix: String = "generated",
    ): ToolImageAttachment {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        val detectedMimeType = bounds.outMimeType
            ?.takeIf { it.startsWith("image/") }
            ?: throw IOException("Generated image has an unknown media type")
        return persistBytes(bytes, detectedMimeType, filePrefix)
    }

    fun persistBytes(
        bytes: ByteArray,
        mimeType: String,
        filePrefix: String = "tool",
    ): ToolImageAttachment {
        val mime = mimeType.substringBefore(';').trim().lowercase()
        if (!mime.startsWith("image/")) {
            throw IOException("Unsupported tool media type: $mime")
        }
        if (bytes.isEmpty()) throw IOException("Tool image is empty")
        if (bytes.size.toLong() > MAX_IMAGE_BYTES) {
            throw IOException("Tool image exceeds ${MAX_IMAGE_BYTES / (1024 * 1024)} MB")
        }

        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            throw IOException("Tool returned an unsupported or invalid image")
        }

        if (!directory.exists() && !directory.mkdirs()) {
            throw IOException("Could not create tool media directory")
        }
        val safePrefix = safePrefix(filePrefix)
        val destination = File(
            directory,
            "${safePrefix}_${UUID.randomUUID()}.${extensionFor(mime)}",
        )
        val temporary = File(directory, ".${destination.name}.tmp")
        try {
            temporary.outputStream().use { output ->
                output.write(bytes)
                output.fd.sync()
            }
            if (!temporary.renameTo(destination)) {
                throw IOException("Could not finalize tool image")
            }
        } finally {
            if (temporary.exists()) temporary.delete()
        }

        val sha256 = MessageDigest.getInstance("SHA-256")
            .digest(bytes)
            .joinToString("") { "%02x".format(it) }
        return ToolImageAttachment(
            path = destination.absolutePath,
            mimeType = mime,
            sizeBytes = bytes.size.toLong(),
            width = bounds.outWidth,
            height = bounds.outHeight,
            sha256 = sha256,
        )
    }

    private fun safePrefix(filePrefix: String): String = filePrefix
        .map { char -> if (char.isLetterOrDigit() || char == '_') char else '_' }
        .joinToString("")
        .trim('_')
        .ifBlank { "tool" }
        .take(32)

    private fun extensionFor(mime: String): String = when (mime) {
        "image/jpeg", "image/jpg" -> "jpg"
        "image/png" -> "png"
        "image/webp" -> "webp"
        "image/gif" -> "gif"
        "image/heic", "image/heif" -> "heic"
        "image/avif" -> "avif"
        else -> "img"
    }
}
