package com.newoether.agora.util
import com.newoether.agora.tool.DURABLE_JOB_CANCELLATION_STOP_TIMEOUT_MS
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals

import org.junit.Assert.assertTrue
import org.junit.Test

class ShellClientErrorTest {
    @Test
    fun connectionRefusedIsNotReportedAsEncryptionFailure() {
        val message = describeConchConnectionFailure(
            "http://oneplus:14216",
            java.net.ConnectException("Connection refused"),
        )

        assertTrue(message.contains("Cannot connect to Conch"))
        assertTrue(message.contains("Connection refused"))
        assertTrue(!message.contains("encryption", ignoreCase = true))
    }

    @Test
    fun unknownHostNamesTheResolutionFailure() {
        val message = describeConchConnectionFailure(
            "http://missing:14216",
            java.net.UnknownHostException("missing"),
        )

        assertTrue(message.contains("Cannot resolve Conch host"))
        assertTrue(message.contains("missing"))
    }

    @Test
    fun jobTimeoutNamesTheOperationInsteadOfEncryption() {
        val message = describeConchRequestFailure(
            "http://oneplus:14216",
            "/jobs/get request",
            java.net.SocketTimeoutException("timeout"),
        )

        assertTrue(message.contains("/jobs/get request timed out"))
        assertTrue(!message.contains("encryption", ignoreCase = true))
    }
    @Test
    fun stopJobCallTimeoutBoundsAnUnresponsiveServer() {
        val server = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1")).apply {
            soTimeout = 5_000
        }
        val acceptedSocket = AtomicReference<Socket?>()
        val requestPath = AtomicReference<String?>()
        val releaseServer = CountDownLatch(1)
        val serverFailure = AtomicReference<Throwable?>()
        val worker = thread(name = "shell-stop-timeout-test-server", isDaemon = true) {
            try {
                server.accept().use { socket ->
                    acceptedSocket.set(socket)
                    socket.soTimeout = 5_000
                    val reader = socket.getInputStream().bufferedReader()
                    val requestLine = checkNotNull(reader.readLine())
                    requestPath.set(requestLine.split(' ')[1])
                    var contentLength = 0
                    while (true) {
                        val line = reader.readLine() ?: break
                        if (line.isEmpty()) break
                        val separator = line.indexOf(':')
                        if (
                            separator > 0 &&
                            line.substring(0, separator)
                                .equals("content-length", ignoreCase = true)
                        ) {
                            contentLength = line.substring(separator + 1).trim().toInt()
                        }
                    }
                    var remaining = contentLength
                    val buffer = CharArray(256)
                    while (remaining > 0) {
                        val count = reader.read(buffer, 0, minOf(buffer.size, remaining))
                        check(count >= 0) { "Unexpected end of request body" }
                        remaining -= count
                    }
                    releaseServer.await(15, TimeUnit.SECONDS)
                }
            } catch (error: Throwable) {
                if (!server.isClosed) serverFailure.set(error)
            }
        }
        val startedAt = System.nanoTime()
        val failure = runCatching {
            runBlocking {
                ShellClient(
                    serverUrl = "http://127.0.0.1:${server.localPort}",
                    apiKey = "",
                ).stopJob(
                    "job-1",
                    callTimeoutMillis = DURABLE_JOB_CANCELLATION_STOP_TIMEOUT_MS,
                )
            }
        }.exceptionOrNull()
        val elapsedMillis = (System.nanoTime() - startedAt) / 1_000_000
        releaseServer.countDown()
        acceptedSocket.get()?.close()
        server.close()
        worker.join(5_000)
        assertEquals("/jobs/stop", requestPath.get())
        assertTrue(failure is IllegalStateException)
        assertTrue(failure?.message.orEmpty().contains("/jobs/stop request timed out"))
        assertTrue("Call returned too early after ${elapsedMillis}ms", elapsedMillis >= 4_000L)
        assertTrue("Call took ${elapsedMillis}ms", elapsedMillis < 10_000L)
        assertTrue("Server thread did not finish", !worker.isAlive)
        assertEquals(null, serverFailure.get())
    }
}
