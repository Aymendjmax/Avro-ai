package com.newoether.agora.remote

import com.newoether.agora.ui.chat.message.StreamingTailFadeTracker
import org.junit.Assert.*
import org.junit.Test

class RemoteStreamDeltasTest {
    private val active = RemoteRuntime("active", "turn")
    private val message = RemoteMessage("answer", "turn", null, "assistant", "Hello", 1)
    private fun deltas(messages: List<RemoteMessage>) =
        projectRemoteMessages(messages, active).first().segments!!.first().streamingTextDeltas

    @Test fun initialHistoryIsOpaqueButAppendedSnapshotUsesOriginalGlyphFade() {
        val source = RemoteStreamDeltas()
        val first = source.apply(emptyList(), listOf(message), null, active)
        assertTrue(deltas(first).isEmpty())
        val fade = StreamingTailFadeTracker()
        assertTrue(fade.update("Hello", 10, deltas(first)).birthTimesMs.isEmpty())
        val updated = source.apply(first, listOf(message.copy(text = "Hello 🌍!\n")), active, active)
        val projected = projectRemoteMessages(updated, active).first().segments!!.first()
        assertEquals("Hello 🌍!", projected.content)
        assertEquals(3, projected.streamingTextDeltas.single().codePointCount)
        assertArrayEquals(longArrayOf(20, 20, 20),
            fade.update(projected.content, 20, projected.streamingTextDeltas).birthTimesMs)
        val repeated = source.apply(updated, listOf(message.copy(text = "Hello 🌍!\n")), active, active)
        assertEquals(deltas(updated), deltas(repeated))
        assertArrayEquals(longArrayOf(20, 20, 20),
            fade.update(projected.content, 30, deltas(repeated)).birthTimesMs)
    }

    @Test fun finalChunkRetainsDeltaIdentityAndRewritesDoNotInventText() {
        val source = RemoteStreamDeltas()
        val first = source.apply(listOf(message), listOf(message.copy(text = "Hello world")), active, active)
        val terminal = source.apply(first, listOf(message.copy(text = "Hello world!")), active, RemoteRuntime("idle"))
        assertEquals(listOf(6, 1), deltas(terminal).map { it.codePointCount })
        val rewritten = source.apply(terminal, listOf(message.copy(text = "Edited history")), RemoteRuntime("idle"), RemoteRuntime("idle"))
        assertTrue(deltas(rewritten).isEmpty())
    }

    @Test fun conflatedSnapshotsKeepAllNewPublishedBoundariesWithoutReplayingHistory() {
        val source = RemoteStreamDeltas()
        val first = source.apply(listOf(message), listOf(message.copy(text = "Hello one")), active, active)
        val next = source.apply(first, listOf(message.copy(text = "Hello one two")), active, active)
        val projected = projectRemoteMessages(next, active).first().segments!!.first()
        val fade = StreamingTailFadeTracker()
        fade.update("Hello", 0, emptyList())
        assertEquals(8, fade.update(projected.content, 100, projected.streamingTextDeltas).birthTimesMs.size)
        val history = source.apply(emptyList(), listOf(message.copy(turnId = "old")), active, active)
        assertTrue(history.single().streamingTextDeltas.isEmpty())
    }
}
