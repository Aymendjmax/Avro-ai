package com.newoether.agora.util

import android.app.Application
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class ConchReflectionTest {
    @Test fun reflectingAnEncryptedWriteRequestCannotBecomeSuccess() = runTest {
        val pair = ShellCrypto.generateEphemeralKeyPair()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/public-key") { exchange ->
            val challenge = exchange.requestURI.rawQuery.substringAfter("challenge=")
            val body = Json.encodeToString(signedConchFixture(
                ShellCrypto.encodePublicKey(pair.public), challenge,
            )).toByteArray()
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        server.createContext("/") { exchange ->
            // Blind intermediary: does not decrypt, authenticate or perform a file write.
            val reflected = exchange.requestBody.use { it.readBytes() }
            exchange.sendResponseHeaders(200, reflected.size.toLong())
            exchange.responseBody.use { it.write(reflected) }
        }
        server.start()
        try {
            val client = ShellClient("http://127.0.0.1:${server.address.port}", "isolated-fixture-key",
                ShellCrypto.encodePublicKey(pair.public))
            var failure: Exception? = null
            try { client.fileWrite("unused-fixture-path", "fixture-bytes") }
            catch (error: Exception) { failure = error }
            assertNotNull("reflected write must not be accepted", failure)
            assertTrue(failure!!.message.orEmpty().contains("response authentication failed"))
        } finally { server.stop(0) }
    }
}
