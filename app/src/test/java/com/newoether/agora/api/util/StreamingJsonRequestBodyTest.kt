package com.newoether.agora.api.util

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okio.Buffer
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.util.Base64

class StreamingJsonRequestBodyTest {
    @Test
    fun manyFilesStreamWithExactLengthAndReplayIdentically() {
        val directory = Files.createTempDirectory("agora-streaming-json-").toFile()
        try {
            val registry = Base64FileRegistry()
            val payloads = listOf(ByteArray(0)) + (0 until 64).map { index ->
                ByteArray(4_097 + index) { offset -> ((index * 31 + offset) and 0xff).toByte() }
            }
            val placeholders = payloads.mapIndexed { index, payload ->
                val file = File(directory, "frame-$index.jpg").apply { writeBytes(payload) }
                checkNotNull(registry.register(file.absolutePath))
            }
            val template = placeholders.joinToString(
                prefix = "{\"note\":\"\u56fe\u50cf\ud83d\ude42\",\"images\":[\"",
                separator = "\",\"",
                postfix = "\"]}",
            )
            val request = checkNotNull(registry.prepare(template))

            val first = Buffer().also(request.body::writeTo).readByteArray()
            val second = Buffer().also(request.body::writeTo).readByteArray()

            assertArrayEquals(first, second)
            assertEquals(first.size.toLong(), request.body.contentLength())
            val images = Json.parseToJsonElement(first.decodeToString())
                .jsonObject.getValue("images").jsonArray
            assertEquals(65, images.size)
            payloads.forEachIndexed { index, payload ->
                assertEquals(
                    Base64.getEncoder().encodeToString(payload),
                    images[index].jsonPrimitive.content,
                )
            }
            assertTrue(request.diagnosticJson.contains("[STREAMED_BASE64:"))
            assertTrue(request.diagnosticJson.contains("_BYTES]"))
            assertFalse(request.diagnosticJson.contains("__AGORA_BASE64_"))
            assertFalse(request.diagnosticJson.contains(images[1].jsonPrimitive.content))
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun sameLengthReplacementIsRejectedEvenWhenTimestampIsRestored() {
        val file = File.createTempFile("agora-stream-replay-", ".jpg")
        try {
            val original = ByteArray(8_193) { index -> (index and 0xff).toByte() }
            file.writeBytes(original)
            val originalTimestamp = file.lastModified()
            val registry = Base64FileRegistry()
            val placeholder = checkNotNull(registry.register(file.absolutePath))
            val request = checkNotNull(
                registry.prepare("{\"image\":\"$placeholder\"}"),
            )
            request.body.writeTo(Buffer())

            file.writeBytes(original.map { byte -> (byte.toInt() xor 0x5a).toByte() }.toByteArray())
            assertTrue(file.setLastModified(originalTimestamp))
            assertEquals(original.size.toLong(), file.length())
            assertEquals(originalTimestamp, file.lastModified())

            assertThrows(IOException::class.java) {
                request.body.writeTo(Buffer())
            }
        } finally {
            file.delete()
        }
    }

    @Test
    fun missingFilesAreNotRegistered() {
        val registry = Base64FileRegistry()
        assertEquals(null, registry.register("Z:/agora/missing/image.png"))
        assertEquals(null, registry.prepare("{\"images\":[]}"))
    }
}
