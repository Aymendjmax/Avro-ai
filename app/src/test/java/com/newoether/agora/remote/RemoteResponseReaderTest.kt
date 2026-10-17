package com.newoether.agora.remote

import okio.Buffer
import okio.Source
import okio.Timeout
import okio.buffer
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class RemoteResponseReaderTest {
    private class HugeSource(private val total: Long = 169568024) : Source {
        var supplied = 0L
        private val chunk = ByteArray(8192) { 'x'.code.toByte() }
        override fun read(sink: Buffer, byteCount: Long): Long {
            val count = minOf(byteCount, chunk.size.toLong(), total - supplied).toInt()
            if (count == 0) return -1
            sink.write(chunk, 0, count); supplied += count
            return count.toLong()
        }
        override fun timeout() = Timeout.NONE
        override fun close() {}
    }

    @Test fun oversizedUnterminatedSseStopsBeforeDecodingOrReadingTheReportedCrashPayload() {
        val source = HugeSource()
        source.buffer().use { buffered ->
            assertThrows(IOException::class.java) { buffered.readRemoteEventLine() }
            assertTrue(source.supplied <= MAX_REMOTE_RESPONSE_BYTES + 8192)
            assertTrue(buffered.buffer.size <= MAX_REMOTE_RESPONSE_BYTES + 8192)
        }
    }

    @Test fun unknownLengthHttpBodyIsBoundedBeforeStringAllocation() {
        val source = HugeSource()
        source.buffer().use { buffered ->
            assertThrows(IOException::class.java) { buffered.readRemoteResponse() }
            assertTrue(source.supplied <= MAX_REMOTE_RESPONSE_BYTES + 8192)
        }
    }

    @Test fun nativeUnicodeEventsAndHeartbeatsRemainSeparateLines() {
        val buffer = Buffer().writeUtf8("data: {\"text\":\"你好 🌍\"}\n\n: keepalive\r\n")
        assertEquals("data: {\"text\":\"你好 🌍\"}", buffer.readRemoteEventLine())
        assertEquals("", buffer.readRemoteEventLine())
        assertEquals(": keepalive", buffer.readRemoteEventLine())
        assertNull(buffer.readRemoteEventLine())
        assertEquals("{\"ok\":true}", Buffer().writeUtf8("{\"ok\":true}").readRemoteResponse())
    }

    @Test fun sizeBoundaryIsBytesAndAnIncompleteEventDoesNotBecomeAValidSnapshot() {
        val bounded = Buffer().writeUtf8("x".repeat(MAX_REMOTE_RESPONSE_BYTES.toInt())).writeByte(10)
        assertEquals(MAX_REMOTE_RESPONSE_BYTES.toInt(), bounded.readRemoteEventLine()!!.length)
        assertThrows(IOException::class.java) { Buffer().writeUtf8("data: {}").readRemoteEventLine() }
    }
}
