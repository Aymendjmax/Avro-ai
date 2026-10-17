package com.newoether.agora.mcp

import com.newoether.agora.data.McpServerConfig
import com.newoether.agora.data.McpTransportType
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.util.Collections
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import kotlinx.coroutines.runBlocking

class McpTransportTest {
    private data class RecordedRequest(
        val method: String,
        val protocolHeader: String?,
        val protocolBody: String?,
        val sessionId: String?,
    )

    @Test
    fun streamableHttpRetriesInitializeAndKeepsNegotiatedVersion() {
        val server = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1")).apply {
            soTimeout = 5_000
        }
        val requests = Collections.synchronizedList(mutableListOf<RecordedRequest>())
        val serverFailure = AtomicReference<Throwable?>(null)
        val worker = thread(name = "mcp-negotiation-test-server", isDaemon = true) {
            try {
                repeat(4) { index ->
                    server.accept().use { socket ->
                        socket.soTimeout = 5_000
                        val reader = socket.getInputStream().bufferedReader()
                        checkNotNull(reader.readLine()) { "Missing HTTP request line" }
                        val headers = buildMap {
                            while (true) {
                                val line = reader.readLine() ?: break
                                if (line.isEmpty()) break
                                val separator = line.indexOf(':')
                                if (separator > 0) {
                                    put(
                                        line.substring(0, separator).trim().lowercase(),
                                        line.substring(separator + 1).trim(),
                                    )
                                }
                            }
                        }
                        val bodyLength = headers["content-length"]?.toIntOrNull() ?: 0
                        val body = CharArray(bodyLength).also { chars ->
                            var offset = 0
                            while (offset < chars.size) {
                                val count = reader.read(chars, offset, chars.size - offset)
                                check(count >= 0) { "Unexpected end of request body" }
                                offset += count
                            }
                        }.concatToString()
                        val method = Regex("\\\"method\\\":\\\"([^\\\"]+)\\\"")
                            .find(body)?.groupValues?.get(1).orEmpty()
                        val bodyVersion = Regex("\\\"protocolVersion\\\":\\\"([^\\\"]+)\\\"")
                            .find(body)?.groupValues?.get(1)
                        requests += RecordedRequest(
                            method = method,
                            protocolHeader = headers["mcp-protocol-version"],
                            protocolBody = bodyVersion,
                            sessionId = headers["mcp-session-id"],
                        )
                        val status: String
                        val responseHeaders: String
                        val responseBody: String
                        when (index) {
                            0 -> {
                                status = "400 Bad Request"
                                responseHeaders = ""
                                responseBody = """{"jsonrpc":"2.0","error":{"code":-32000,"message":"Unsupported protocol version"}}"""
                            }
                            1 -> {
                                status = "200 OK"
                                responseHeaders = "Mcp-Session-Id: negotiated-session\r\n"
                                responseBody = """{"jsonrpc":"2.0","id":2,"result":{"protocolVersion":"2025-06-18","capabilities":{},"serverInfo":{"name":"test","version":"1"}}}"""
                            }
                            2 -> {
                                status = "202 Accepted"
                                responseHeaders = ""
                                responseBody = ""
                            }
                            else -> {
                                status = "200 OK"
                                responseHeaders = ""
                                responseBody = """{"jsonrpc":"2.0","id":3,"result":{"tools":[]}}"""
                            }
                        }
                        val bytes = responseBody.toByteArray()
                        val response = buildString {
                            append("HTTP/1.1 $status\r\n")
                            append("Content-Type: application/json\r\n")
                            append(responseHeaders)
                            append("Content-Length: ${bytes.size}\r\n")
                            append("Connection: close\r\n\r\n")
                            append(responseBody)
                        }
                        socket.getOutputStream().apply {
                            write(response.toByteArray())
                            flush()
                        }
                    }
                }
            } catch (error: Throwable) {
                serverFailure.set(error)
            }
        }
        try {
            McpProtocolClient(
                endpoint = "http://127.0.0.1:${server.localPort}/mcp",
                customHeaders = emptyMap(),
                transportType = McpTransportType.STREAMABLE_HTTP,
            ).use { client ->
                assertTrue(runBlocking { client.listTools() }.isEmpty())
            }
        } finally {
            worker.join(5_000)
            server.close()
        }
        assertFalse("HTTP server did not finish", worker.isAlive)
        assertEquals(null, serverFailure.get())
        assertEquals(
            listOf("initialize", "initialize", "notifications/initialized", "tools/list"),
            requests.map(RecordedRequest::method),
        )
        assertEquals(
            listOf("2025-11-25", "2025-06-18", "2025-06-18", "2025-06-18"),
            requests.map(RecordedRequest::protocolHeader),
        )
        assertEquals("2025-11-25", requests[0].protocolBody)
        assertEquals("2025-06-18", requests[1].protocolBody)
        assertEquals(listOf(null, null, "negotiated-session", "negotiated-session"), requests.map(RecordedRequest::sessionId))
    }

    @Test
    fun streamableHttpVersionsFallBackInDescendingCompatibilityOrder() {
        assertEquals(
            listOf("2025-11-25", "2025-06-18", "2025-03-26", "2024-11-05"),
            McpProtocolClient.STREAMABLE_HTTP_PROTOCOL_VERSIONS,
        )
        assertTrue(
            isUnsupportedMcpProtocolVersion(
                IOException("MCP HTTP 400: Unsupported protocol version")
            )
        )
        assertTrue(
            isUnsupportedMcpProtocolVersion(
                IOException("Unsupported MCP protocol version: 2025-11-25")
            )
        )
        assertFalse(isUnsupportedMcpProtocolVersion(IOException("MCP HTTP 401: Unauthorized")))
    }
    @Test
    fun existingConfigDefaultsToStreamableHttp() {
        val config = Json.decodeFromString<McpServerConfig>(
            """{"id":"old","name":"Existing","url":"https://example.com/mcp"}""",
        )

        assertEquals(McpTransportType.STREAMABLE_HTTP, config.transport)
        assertEquals("\"sse\"", Json.encodeToString(McpTransportType.SSE))
    }

    @Test
    fun sseParserHandlesEndpointAndMultilineMessageEvents() {
        val parser = McpSseEventParser()

        assertEquals(null, parser.accept("event: endpoint"))
        assertEquals(null, parser.accept("data: /messages?session=abc"))
        assertEquals(
            McpSseEvent("endpoint", "/messages?session=abc"),
            parser.accept(""),
        )

        assertEquals(null, parser.accept("event: message"))
        assertEquals(null, parser.accept("data: {\"jsonrpc\":\"2.0\","))
        assertEquals(null, parser.accept("data: \"id\":1,\"result\":{}}"))
        assertEquals(
            McpSseEvent(
                "message",
                "{\"jsonrpc\":\"2.0\",\n\"id\":1,\"result\":{}}",
            ),
            parser.accept(""),
        )
    }

    @Test
    fun legacyMessageEndpointMustStayOnTheConfiguredOrigin() {
        val stream = "https://example.com/events".toHttpUrl()

        assertEquals(
            "https://example.com/messages?session=abc",
            resolveLegacySseMessageEndpoint(stream, "/messages?session=abc").toString(),
        )
        assertTrue(
            runCatching {
                resolveLegacySseMessageEndpoint(stream, "https://attacker.example/messages")
            }.isFailure,
        )
        assertTrue(
            runCatching {
                resolveLegacySseMessageEndpoint(stream, "https://user@example.com/messages")
            }.isFailure,
        )
    }

    @Test
    fun headerValidationRejectsInjectionReservedNamesAndNonAsciiNames() {
        assertTrue(isValidMcpHeaderName("X-Api-Key"))
        assertTrue(isValidMcpHeaderValue("Bearer abc"))
        assertTrue(isReservedMcpHeaderName("content-type"))
        assertFalse(isValidMcpHeaderName("Bad Header"))
        assertFalse(isValidMcpHeaderName("密钥"))
        assertFalse(isValidMcpHeaderValue("line1\r\nline2"))
    }
}
