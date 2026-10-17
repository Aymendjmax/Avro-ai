package com.newoether.agora.util

import android.util.Base64
import java.io.IOException
import kotlinx.serialization.json.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okio.Buffer
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class ConchEncryptedHttpTest {
    private val key = ByteArray(32) { it.toByte() }
    private fun frame(kind: String, sequence: Long, body: ByteArray = byteArrayOf()): String {
        val value = buildJsonObject {
            put("_conch_event", kind); put("_conch_sequence", sequence)
            put("body", Base64.encodeToString(body, Base64.NO_WRAP))
        }
        return "event: $kind\ndata: ${ShellCrypto.encrypt(key, value.toString().toByteArray())}\n\n"
    }

    @Test fun authenticatedChunksReconstructExactBytesAndRequireTerminal() {
        val expected = byteArrayOf(0, -1, 127, -128)
        val input = Buffer().writeUtf8(frame("http_body", 0, expected) + frame("http_end", 1))
        val source = ConchHttpBodySource(ConchHttpFrames(input, key.copyOf()))
        val output = Buffer()
        assertEquals(4L, source.read(output, 100))
        assertEquals(-1L, source.read(output, 100))
        assertArrayEquals(expected, output.readByteArray())
        val truncated = ConchHttpBodySource(ConchHttpFrames(Buffer().writeUtf8(frame("http_body", 0, expected)), key.copyOf()))
        assertEquals(4L, truncated.read(Buffer(), 100))
        assertTrue(runCatching { truncated.read(Buffer(), 100) }.exceptionOrNull() is IOException)
    }

    @Test fun reorderedReplayedAndRenamedFramesAreRejected() {
        for (input in listOf(frame("http_body", 1), frame("http_body", 0).replace("event: http_body", "event: http_end"))) {
            assertTrue(runCatching { ConchHttpFrames(Buffer().writeUtf8(input), key.copyOf()).read() }.exceptionOrNull() is IOException)
        }
        val frames = ConchHttpFrames(Buffer().writeUtf8(frame("http_body", 0) + frame("http_body", 0)), key.copyOf())
        frames.read()
        assertTrue(runCatching { frames.read() }.exceptionOrNull() is IOException)
    }

    @Test fun originalGoGatewayInteroperatesForRawBytesAndErrors() {
        val url = System.getenv("CONCH_HTTP_FIXTURE_URL")
        assumeTrue("Isolated original Go gateway fixture is opt-in", !url.isNullOrBlank())
        val bytes = ByteArray(150_003) { it.toByte() }
        val client = OkHttpClient.Builder().retryOnConnectionFailure(false).followRedirects(false)
            .addInterceptor(ConchEncryptedHttp("a".repeat(64))).build()
        for ((path, code) in listOf("/v1/echo" to 200, "/v1/error" to 409)) {
            client.newCall(Request.Builder().url(url!! + path)
                .post(bytes.toRequestBody("application/octet-stream".toMediaType())).build()).execute().use { response ->
                assertEquals(code, response.code)
                assertEquals("application/octet-stream", response.header("Content-Type"))
                assertArrayEquals(bytes, response.body.bytes())
            }
        }
    }
}
