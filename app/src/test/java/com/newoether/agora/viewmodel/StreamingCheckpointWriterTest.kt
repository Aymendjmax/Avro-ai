package com.newoether.agora.viewmodel

import com.newoether.agora.model.ChatMessage
import com.newoether.agora.model.Participant
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class StreamingCheckpointWriterTest {
    @Test
    fun ordinaryUpdatesConflateWhileFlushWaitsForNewestDurableSnapshot() = runBlocking {
        val firstWriteStarted = CompletableDeferred<Unit>()
        val releaseFirstWrite = CompletableDeferred<Unit>()
        val persisted = mutableListOf<String>()
        var first = true
        val writer = StreamingCheckpointWriter(
            scope = this,
            persist = { message ->
                if (first) {
                    first = false
                    firstWriteStarted.complete(Unit)
                    releaseFirstWrite.await()
                }
                synchronized(persisted) { persisted += message.text }
                true
            },
            onFailure = { throw AssertionError(it) },
        )

        writer.enqueue(message("one"))
        firstWriteStarted.await()
        // These calls are synchronous even though the Room writer is deliberately blocked.
        writer.enqueue(message("two"))
        writer.enqueue(message("three"))
        val flushed = async { writer.flush(message("four")) }
        releaseFirstWrite.complete(Unit)

        assertTrue(flushed.await())
        writer.cancelAndJoin()
        assertEquals("one", persisted.first())
        assertEquals("four", persisted.last())
        assertTrue(persisted.size <= 3)
    }

    @Test
    fun ordinaryPersistenceFailureDoesNotKillTheWriter() = runBlocking {
        val expected = IllegalStateException("ordinary checkpoint failed")
        val firstAttempted = CompletableDeferred<Unit>()
        val failures = mutableListOf<Exception>()
        val persisted = mutableListOf<String>()
        val writer = StreamingCheckpointWriter(
            scope = this,
            persist = { message ->
                if (message.text == "bad") {
                    firstAttempted.complete(Unit)
                    throw expected
                }
                persisted += message.text
                true
            },
            onFailure = failures::add,
        )

        writer.enqueue(message("bad"))
        firstAttempted.await()
        assertTrue(writer.flush(message("recovered")))
        writer.cancelAndJoin()

        assertEquals(listOf("recovered"), persisted)
        assertEquals(listOf(expected), failures)
    }

    @Test
    fun forcedPersistenceFailurePropagatesAndALaterFlushRecovers() = runBlocking {
        val expected = IllegalStateException("forced checkpoint failed")
        val failures = mutableListOf<Exception>()
        var failNext = true
        val writer = StreamingCheckpointWriter(
            scope = this,
            persist = {
                if (failNext) {
                    failNext = false
                    throw expected
                }
                true
            },
            onFailure = failures::add,
        )

        val failure = runCatching { writer.flush(message("fails")) }.exceptionOrNull()
        assertSame(expected, failure)
        assertTrue(writer.flush(message("recovers")))
        writer.cancelAndJoin()

        assertEquals(listOf(expected), failures)
    }

    @Test
    fun streamingCheckpointOwnerRechecksLatestIdentityAtTheWriterBoundary() = runBlocking {
        var latestChecks = 0
        val persisted = mutableListOf<String>()
        val checkpoints = StreamingMessageCheckpoints(
            scope = this,
            isLatestPersist = { ++latestChecks == 1 },
            persist = { message ->
                persisted += message.text
                true
            },
            onFailure = { throw AssertionError(it) },
        )

        checkpoints.persist(message("stale"), force = true)
        checkpoints.close()

        assertTrue(latestChecks >= 2)
        assertTrue(persisted.isEmpty())
    }

    @Test
    fun throttledLazyCheckpointDoesNotBuildGrowingSnapshot() = runBlocking {
        var now = 1_000L
        var snapshotsBuilt = 0
        val checkpoints = StreamingMessageCheckpoints(
            scope = this,
            isLatestPersist = { true },
            persist = { true },
            onFailure = { throw AssertionError(it) },
            nowMs = { now },
        )

        checkpoints.persistLazy {
            snapshotsBuilt++
            message("first")
        }
        checkpoints.persistLazy {
            snapshotsBuilt++
            message("suppressed")
        }
        now += 1_000L
        checkpoints.persistLazy {
            snapshotsBuilt++
            message("second")
        }
        checkpoints.close()

        assertEquals(2, snapshotsBuilt)
    }

    private fun message(text: String) = ChatMessage(
        id = "model",
        text = text,
        participant = Participant.MODEL,
    )
}
