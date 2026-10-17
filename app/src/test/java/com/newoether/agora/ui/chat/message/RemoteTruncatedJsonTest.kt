package com.newoether.agora.ui.chat.message

import com.newoether.agora.model.MessagePersistenceGuard
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class RemoteTruncatedJsonTest {
    @Test fun unfinishedObjectRetainsFieldsAndOpenString() {
        val prefix = "{\"count\":2,\"output\":\"partial"
        val source = persistenceAwareJsonSource(prefix + FiloPreviewTruncationMarker)
        assertEquals(prefix, source)
        val parsed = StreamingJsonParser.parse(source)
        assertEquals(StreamingJsonStatus.INCOMPLETE, parsed.status)
        val root = parsed.root as StreamingJsonObject
        assertEquals(listOf("count", "output"), root.entries.map { it.key })
        val partial = root.entries.last().value as StreamingJsonScalar
        assertEquals("partial", partial.content)
        assertFalse(partial.complete)
    }

    @Test fun truncatedArrayDoesNotInventItsMissingElement() {
        val prefix = "[{\"ok\":true},"
        val parsed = StreamingJsonParser.parse(persistenceAwareJsonSource(prefix + FiloPreviewTruncationMarker))
        assertEquals(StreamingJsonStatus.INCOMPLETE, parsed.status)
        assertEquals(1, (parsed.root as StreamingJsonArray).values.size)
    }

    @Test fun localPersistenceCanTruncateAnAlreadyBoundedRemotePreview() {
        val prefix = "{\"count\":12"
        assertEquals(prefix, persistenceAwareJsonSource(
            prefix + FiloPreviewTruncationMarker + MessagePersistenceGuard.TRUNCATION_MARKER,
        ))
    }

    @Test fun markerInsideAJsonStringRemainsData() {
        val source = "{\"text\":\"\\n[Filo preview truncated; full output remains in Codex.]\"}"
        assertEquals(source, persistenceAwareJsonSource(source))
        assertEquals(StreamingJsonStatus.COMPLETE, StreamingJsonParser.parse(source).status)
    }

    @Test fun impossibleJsonStillUsesThePlainTextFallback() {
        val source = persistenceAwareJsonSource("{\"count\":!" + FiloPreviewTruncationMarker)
        assertEquals(StreamingJsonStatus.INVALID, StreamingJsonParser.parse(source).status)
    }
}
