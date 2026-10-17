package com.newoether.agora.remote

import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.net.InetSocketAddress
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class FiloTimeoutTest {
    private val token = "a".repeat(64)
    private val id = "00000000-0000-4000-8000-000000000001"
    private val clientId = "22222222-2222-4222-8222-222222222222"

    @Test fun coldCreationOutlivesReadDeadlineWithoutChangingOrdinaryReads() = runBlocking {
        val posts = AtomicInteger()
        val executor = Executors.newCachedThreadPool()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.executor = executor
        server.createContext("/") { exchange ->
            assertEquals("Bearer $token", exchange.requestHeaders.getFirst("Authorization"))
            val write = exchange.requestMethod == "POST"
            if (write) posts.incrementAndGet()
            exchange.requestBody.use { it.readBytes() }
            Thread.sleep(150)
            val body = if (write) """{"id":"$id","title":"Created","cwd":"/fixture","updatedAt":1,"turnId":"turn-1","clientId":"$clientId"}"""
                else """{"models":[]}"""
            try {
                exchange.sendResponseHeaders(200, body.toByteArray().size.toLong())
                exchange.responseBody.use { it.write(body.toByteArray()) }
            } catch (_: IOException) { exchange.close() }
        }
        server.start()
        val transport = OkHttpClient.Builder().retryOnConnectionFailure(false)
            .readTimeout(40, TimeUnit.MILLISECONDS).callTimeout(100, TimeUnit.MILLISECONDS).build()
        try {
            val client = FiloClient("http://127.0.0.1:${server.address.port}/", token, transport, 1000)
            val created = client.create("hello", clientId, settings = RemoteSettings(model = "model"))
        assertEquals(id, created.id)
            try { client.models(); fail("Ordinary reads retain their shorter deadline") }
            catch (_: IOException) { }
            assertEquals(1, posts.get())
        } finally {
            server.stop(0)
            executor.shutdown()
            assertTrue(executor.awaitTermination(2, TimeUnit.SECONDS))
        }
    }

    @Test fun timedOutMutationIsNotRetriedEvenWithRetryingInjectedTransport() = runBlocking {
        val posts = AtomicInteger()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            posts.incrementAndGet()
            exchange.requestBody.use { it.readBytes() }
            Thread.sleep(300)
            exchange.close()
        }
        server.start()
        try {
            val transport = OkHttpClient.Builder().retryOnConnectionFailure(true).build()
            val client = FiloClient("http://127.0.0.1:${server.address.port}/", token, transport, 100)
            try { client.create("hello", clientId, settings = RemoteSettings(model = "model")); fail("Unknown native acceptance must be reported") }
            catch (_: IOException) { }
            assertEquals(1, posts.get())
        } finally { server.stop(0) }
    }
}
