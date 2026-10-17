package com.newoether.agora.remote

import com.newoether.agora.model.SelectedAttachment
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okio.Buffer
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.security.MessageDigest

class FiloAttachmentUploadTest {
    @get:Rule val temporary = TemporaryFolder()
    private fun fixture(bytes: ByteArray): SelectedAttachment {
        val file = temporary.newFile(); file.writeBytes(bytes)
        return SelectedAttachment(uri = file.toURI().toString(), type = "file", fileName = "original.bin",
            mimeType = "application/octet-stream", fileSize = bytes.size.toLong(), localPath = file.path)
    }

    @Test fun rawBytesAreChunkedAndEveryOffsetIsConfirmedBeforeCompletion() = runBlocking {
        val bytes = ByteArray(600_003) { (it % 256).toByte() }
        val item = fixture(bytes)
        val uploaded = ByteArrayOutputStream()
        val json = Json { ignoreUnknownKeys = true }
        var metadata: RemoteUpload? = null
        var completed = false
        val receipt = uploadRemoteAttachment(item) { path, method, body ->
            val buffer = Buffer(); body.writeTo(buffer)
            when {
                path == "v1/uploads" -> {
                    assertEquals("POST", method)
                    metadata = json.decodeFromString<RemoteUpload>(buffer.readUtf8()).copy(id = "a".repeat(32))
                    json.encodeToString(metadata!!)
                }
                method == "PUT" -> {
                    assertEquals("v1/uploads/${"a".repeat(32)}?offset=${uploaded.size()}", path)
                    assertTrue(buffer.size <= 256 * 1024)
                    uploaded.write(buffer.readByteArray())
                    "{\"offset\":${uploaded.size()}}"
                }
                else -> {
                    assertEquals(bytes.size, uploaded.size())
                    completed = true
                    json.encodeToString(metadata!!.copy(path = "C:/target/original.bin"))
                }
            }
        }
        assertTrue(completed)
        assertArrayEquals(bytes, uploaded.toByteArray())
        assertEquals(MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }, receipt.sha256)
    }

    @Test fun wrongOffsetStopsWithoutCompletionOrAutomaticReplay() = runBlocking {
        val item = fixture(byteArrayOf(0, -1, 1))
        var chunks = 0
        var completes = 0
        val json = Json { ignoreUnknownKeys = true }
        val failure = runCatching { uploadRemoteAttachment(item) { path, method, body ->
            val buffer = Buffer(); body.writeTo(buffer)
            when {
                path == "v1/uploads" -> json.encodeToString(json.decodeFromString<RemoteUpload>(buffer.readUtf8()).copy(id = "b".repeat(32)))
                method == "PUT" -> { chunks++; "{\"offset\":0}" }
                else -> { completes++; error("must not complete") }
            }
        } }.exceptionOrNull()
        assertTrue(failure is java.io.IOException)
        assertEquals(1, chunks)
        assertEquals(0, completes)
    }

    @Test fun substitutedReceiptNeverStartsRawUpload() = runBlocking {
        var calls = 0
        val json = Json { ignoreUnknownKeys = true }
        val failure = runCatching { uploadRemoteAttachment(fixture(byteArrayOf(7))) { _, _, body ->
            calls++
            val buffer = Buffer(); body.writeTo(buffer)
            json.encodeToString(json.decodeFromString<RemoteUpload>(buffer.readUtf8()).copy(id = "../elsewhere"))
        } }.exceptionOrNull()
        assertNotNull(failure)
        assertEquals(1, calls)
    }

    @Test fun changedLocalFileNeverStartsNetworkUpload() = runBlocking {
        val item = fixture(byteArrayOf(7)).copy(fileSize = 2)
        var calls = 0
        assertNotNull(runCatching { uploadRemoteAttachment(item) { _, _, _ -> calls++; "{}" } }.exceptionOrNull())
        assertEquals(0, calls)
    }
}
