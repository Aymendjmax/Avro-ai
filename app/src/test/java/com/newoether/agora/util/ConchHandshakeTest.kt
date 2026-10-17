package com.newoether.agora.util

import android.app.Application
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicInteger
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

internal fun signedConchFixture(publicKey: String, challenge: String): ConchHandshake {
    val features = "hkdf-extract-v2,response-auth-v1,sse-sequence-v1,rejection-auth-v1"
    fun signature(payload: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec("isolated-fixture-key".toByteArray(), "HmacSHA256"))
        return mac.doFinal("server-nonce|$payload".toByteArray()).joinToString("") { "%02x".format(it) }
    }
    return ConchHandshake(publicKey, "server-nonce", signature(publicKey), features, challenge,
        signature("conch-handshake-v2|$publicKey|$challenge|$features"))
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class ConchHandshakeTest {
    @Test fun handshakeRejectsSubstitutionReplayAndMissingCapabilities() {
        val fixture = signedConchFixture("public-key", "fresh-challenge")
        assertTrue(fixture.verify("isolated-fixture-key", "fresh-challenge"))
        for (altered in listOf(fixture.copy(public_key = "substituted"),
            fixture.copy(challenge = "old"), fixture.copy(features = ""),
            fixture.copy(features_signature = ""), fixture.copy(nonce = "old"))) {
            assertFalse(altered.verify("isolated-fixture-key", "fresh-challenge"))
        }
        assertFalse(fixture.verify("wrong-key", "fresh-challenge"))
        assertFalse(fixture.verify("isolated-fixture-key", "next-challenge"))
    }

    @Test fun cachedKeyCannotBypassSecurityPreflightBeforeFileMutation() = runTest {
        val publicKey = ShellCrypto.encodePublicKey(ShellCrypto.generateEphemeralKeyPair().public)
        val mutations = AtomicInteger()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/public-key") { exchange ->
            val old = signedConchFixture(publicKey, "").copy(features = "", features_signature = "")
            val body = Json.encodeToString(old).toByteArray()
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        server.createContext("/file/write") { exchange ->
            mutations.incrementAndGet()
            exchange.sendResponseHeaders(500, -1)
            exchange.close()
        }
        server.start()
        try {
            val client = ShellClient("http://127.0.0.1:${server.address.port}", "isolated-fixture-key", publicKey)
            val failure = runCatching { client.fileWrite("unused", "unused") }.exceptionOrNull()
            assertTrue(failure?.message.orEmpty().contains("security handshake failed"))
            assertEquals(0, mutations.get())
        } finally { server.stop(0) }
    }
}
