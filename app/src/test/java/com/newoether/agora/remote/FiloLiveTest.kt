package com.newoether.agora.remote

import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.UUID

/** Explicit opt-in: use only an isolated, already seeded native test session. */
class FiloLiveTest {
    @Test fun existingOwnerReceivesMessageThroughProductionHttpClient() = runBlocking {
        val url = System.getenv("FILO_TEST_URL")
        val tokenFile = System.getenv("FILO_TEST_TOKEN_FILE")
        val threadFile = System.getenv("FILO_TEST_THREAD_FILE")
        assumeTrue(url != null && tokenFile != null && threadFile != null)
        val client = FiloClient(url!!, File(tokenFile!!).readText().trim())
        val thread = File(threadFile!!).readText().trim()
        assertTrue(client.connect().isNotBlank())
        assertTrue(client.sessions().sessions.any { it.id == thread })
        assertTrue(client.conversation(thread).messages.any { it.text == "FILO_ANDROID_READY" })
        val clientId = UUID.randomUUID().toString()
        assertTrue(client.send(thread, "Reply with exactly FILO_ANDROID_OK.", clientId).isNotBlank())
        val page = withTimeout(90000) {
            var result = client.conversation(thread)
            while (result.messages.none { it.role == "assistant" && it.text.trim().removeSuffix(".") == "FILO_ANDROID_OK" }) {
                delay(1000)
                result = client.conversation(thread)
            }
            result
        }
        assertTrue(page.messages.any { it.clientId == clientId })
        assertEquals(4, projectRemoteMessages(page.messages).size)
    }
}
