package com.newoether.agora.remote

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicInteger

class FiloTransportRecoveryTest {
    private val token = "a".repeat(64)
    private val id = "00000000-0000-4000-8000-000000000001"
    private val info = """{"protocolVersion":2,"agent":"codex","sessionMode":"existing","messageDelivery":"native-steer","outputMode":"live-messages","supportsLazyMessages":true,"device":"test"}"""
    private fun HttpExchange.reply(status: Int, body: String) {
        val bytes = body.toByteArray()
        sendResponseHeaders(status, bytes.size.toLong())
        responseBody.use { it.write(bytes) }
    }
    private fun server(handler: (HttpExchange) -> Unit): HttpServer =
        HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/") { exchange ->
                assertEquals("Bearer $token", exchange.requestHeaders.getFirst("Authorization"))
                if (exchange.requestURI.path == "/v1/info") exchange.reply(200, info) else handler(exchange)
            }
            start()
        }
    private fun client(server: HttpServer) = applicationFixtureClient("http://127.0.0.1:${server.address.port}/", token)

    @Test fun readRecoversWhenAPooledConnectionClosesBeforeResponseHeaders() = runBlocking {
        val requests = AtomicInteger()
        val server = server { exchange ->
            assertEquals("GET", exchange.requestMethod)
            if (requests.incrementAndGet() == 1) exchange.close()
            else exchange.reply(200, """{"models":[]}""")
        }
        try {
            val client = client(server)
            client.connect()
            assertTrue(client.models().isEmpty())
            assertEquals(2, requests.get())
        } finally { server.stop(0) }
    }

    @Test fun eventsRecoverBeforeTheFirstResponseWithoutReplayingWrites() = runBlocking {
        val requests = AtomicInteger()
        val server = server { exchange ->
            assertEquals("GET", exchange.requestMethod)
            if (requests.incrementAndGet() == 1) exchange.close()
            else exchange.reply(200, "data: {\"messages\":[],\"nodes\":[],\"nextCursor\":null,\"queued\":[]}\n\n")
        }
        try {
            val client = client(server)
            client.connect()
            assertTrue(client.events(id).first().messages.isEmpty())
            assertEquals(2, requests.get())
        } finally { server.stop(0) }
    }

    @Test fun archiveIsNeverRetriedAfterAnAmbiguousConnectionClose() = runBlocking {
        val requests = AtomicInteger()
        val server = server { exchange ->
            assertEquals("POST", exchange.requestMethod)
            requests.incrementAndGet()
            exchange.requestBody.use { it.readBytes() }
            exchange.close()
        }
        try {
            val client = client(server)
            client.connect()
            try { client.archiveSession(id); fail("An unconfirmed archive must fail") }
            catch (_: IOException) { }
            assertEquals(1, requests.get())
        } finally { server.stop(0) }
    }

    @Test fun authenticationFailuresAreNotRetried() = runBlocking {
        val requests = AtomicInteger()
        val server = server { exchange -> requests.incrementAndGet(); exchange.reply(401, "{}") }
        try {
            val client = client(server)
            client.connect()
            try { client.models(); fail("Authentication must fail") }
            catch (error: FiloHttpException) { assertEquals(401, error.status) }
            assertEquals(1, requests.get())
        } finally { server.stop(0) }
    }
}
