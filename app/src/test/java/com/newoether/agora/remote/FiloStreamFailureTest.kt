package com.newoether.agora.remote

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test

class FiloStreamFailureTest {
    @Test fun httpAndSseFailuresKeepBoundedVisibleDetailsWithoutPuttingSecretsInExceptions() = runBlocking {
        val token = "a".repeat(64)
        val message = "Original desktop owner unavailable: $token " + "x".repeat(3000)
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            val stream = exchange.requestURI.path.endsWith("/events")
            val json = "{\"code\":\"native_unavailable\",\"error\":\"$message\"}"
            val bytes = (if (stream) "event: error\ndata: $json\n\n" else json).toByteArray()
            exchange.responseHeaders.add("Content-Type", if (stream) "text/event-stream" else "application/json")
            exchange.sendResponseHeaders(if (stream) 200 else 409, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
        try {
            val client = applicationFixtureClient("http://127.0.0.1:${server.address.port}/", token)
            for (stream in listOf(false, true)) {
                try {
                    val id = "00000000-0000-0000-0000-000000000001"
                    if (stream) client.events(id).first() else client.conversation(id)
                    fail("Native errors cannot publish a page")
                } catch (error: Exception) {
                    val detail = remoteErrorDetail(error)!!
                    assertEquals(2048, detail.length)
                    assertEquals("native_unavailable", remoteErrorCode(error))
                    org.junit.Assert.assertTrue(detail.startsWith("Original desktop owner unavailable: [redacted]"))
                    org.junit.Assert.assertFalse(error.toString().contains(token))
                    org.junit.Assert.assertFalse(error.toString().contains("Original desktop"))
                    assertEquals(RemoteFailure.SERVICE, classifyRemoteFailure(error))
                }
            }
        } finally { server.stop(0) }
    }

    @Test fun serverErrorEventIsAServiceFailureWhilePrematureEofIsNetworkFailure() = runBlocking {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        var body = "event: error\ndata: {}\n\n"
        server.createContext("/") { exchange ->
            val bytes = body.toByteArray()
            exchange.responseHeaders.add("Content-Type", "text/event-stream")
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
        try {
            val client = applicationFixtureClient("http://127.0.0.1:${server.address.port}/", "a".repeat(64))
            for (expected in listOf(RemoteFailure.SERVICE, RemoteFailure.NETWORK)) {
                try {
                    client.events("00000000-0000-0000-0000-000000000001").first()
                    fail("An unsuccessful stream cannot publish a page")
                } catch (error: Exception) { assertEquals(expected, classifyRemoteFailure(error)) }
                body = ": heartbeat\n\n"
            }
        } finally { server.stop(0) }
    }
}
