package com.newoether.agora.diagnostics

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class DiagnosticCaptureStoreTest {
    private val root = Files.createTempDirectory("agora-diagnostic-store").toFile()
    private val eventJson = Json {
        classDiscriminator = "payloadType"
        encodeDefaults = true
    }

    @After
    fun tearDown() {
        root.deleteRecursively()
    }

    @Test
    fun `default retained event budget is sixteen mebibytes`() {
        assertEquals(
            16L * 1024L * 1024L,
            DiagnosticCaptureStore.DEFAULT_MAX_RETAINED_PAYLOAD_BYTES,
        )
    }

    @Test
    fun `running session and serialized event bytes survive reload`() {
        val store = DiagnosticCaptureStore(root)
        var state = store.persistMetadata(
            DiagnosticStoredState(
                metadata = metadata(
                    state = DiagnosticCaptureState.RUNNING,
                    sessionId = "session-1",
                    startedAtMillis = 100L,
                ),
            ),
        )
        val event = wireEvent(sequence = 1L, value = "event-one")
        state = store.append(state, event)

        val restored = DiagnosticCaptureStore(root).load()
        val expectedBytes = serializedBytes(event)

        assertEquals(DiagnosticCaptureState.RUNNING, restored.metadata.state)
        assertEquals("session-1", restored.metadata.sessionId)
        assertEquals(2L, restored.metadata.nextSequence)
        assertEquals(listOf(1L), restored.events.map(DiagnosticEvent::sequence))
        assertEquals("event-one", restored.events.single().wireText())
        assertEquals(expectedBytes, restored.retainedPayloadBytes)
        assertEquals(listOf(expectedBytes), restored.retainedEventBytes)
        assertEquals(expectedBytes, eventFile(root, 1L).length())
        assertFalse(restored.metadata.capacityLimitReached)
    }

    @Test
    fun `text truncation keeps more than five hundred events when serialized budget fits`() {
        val store = DiagnosticCaptureStore(
            directory = root,
            maxPayloadBytes = 4,
            maxRetainedPayloadBytes = 1_000_000L,
        )
        val events = listOf(wireEvent(1L, "abcdef")) +
            (2L..513L).map { sequence -> wireEvent(sequence, "x") }
        val state = store.appendBatch(
            DiagnosticStoredState(metadata = metadata()),
            events,
        )

        assertEquals(513, state.events.size)
        assertEquals(1L, state.events.first().sequence)
        assertEquals(513L, state.events.last().sequence)
        assertEquals("abcd", state.events.first().wireText())
        assertTrue(state.events.first().wireCapture().truncated)
        assertEquals(1L, state.metadata.truncatedPayloadCount)
        assertEquals(0L, state.metadata.evictedEventCount)
        assertEquals(state.events.map(::serializedBytes), state.retainedEventBytes)
        assertEquals(state.retainedEventBytes.sum(), state.retainedPayloadBytes)
        assertFalse(state.metadata.capacityLimitReached)
    }

    @Test
    fun `append evicts the oldest complete events until serialized bytes fit`() {
        val first = wireEvent(1L, "first")
        val second = wireEvent(2L, "second")
        val third = wireEvent(3L, "third")
        val budget = serializedBytes(second) + serializedBytes(third)
        val store = DiagnosticCaptureStore(
            directory = root,
            maxPayloadBytes = 100,
            maxRetainedPayloadBytes = budget,
        )

        val state = store.appendBatch(
            DiagnosticStoredState(metadata = metadata()),
            listOf(first, second, third),
        )

        assertEquals(listOf(2L, 3L), state.events.map(DiagnosticEvent::sequence))
        assertEquals(listOf("second", "third"), state.events.map { it.wireText() })
        assertEquals(budget, state.retainedPayloadBytes)
        assertEquals(listOf(serializedBytes(second), serializedBytes(third)), state.retainedEventBytes)
        assertEquals(4L, state.metadata.nextSequence)
        assertEquals(1L, state.metadata.evictedEventCount)
        assertEquals(DiagnosticCaptureState.RUNNING, state.metadata.state)
        assertFalse(state.metadata.capacityLimitReached)
        assertFalse(eventFile(root, 1L).exists())
        assertTrue(eventFile(root, 2L).isFile)
        assertTrue(eventFile(root, 3L).isFile)
    }

    @Test
    fun `events reaching the exact serialized budget stay retained and running`() {
        val first = wireEvent(1L, "abc")
        val second = wireEvent(2L, "de")
        val budget = serializedBytes(first) + serializedBytes(second)
        val store = DiagnosticCaptureStore(
            directory = root,
            maxPayloadBytes = 100,
            maxRetainedPayloadBytes = budget,
        )

        val state = store.appendBatch(
            DiagnosticStoredState(metadata = metadata()),
            listOf(first, second),
        )

        assertEquals(listOf(1L, 2L), state.events.map(DiagnosticEvent::sequence))
        assertEquals(budget, state.retainedPayloadBytes)
        assertEquals(3L, state.metadata.nextSequence)
        assertEquals(0L, state.metadata.evictedEventCount)
        assertEquals(DiagnosticCaptureState.RUNNING, state.metadata.state)
        assertFalse(state.metadata.capacityLimitReached)
    }

    @Test
    fun `individually oversized event is dropped without evicting retained history`() {
        val retained = wireEvent(1L, "retained")
        val oversized = wireEvent(2L, "x".repeat(1_000))
        val budget = serializedBytes(retained)
        val store = DiagnosticCaptureStore(
            directory = root,
            maxPayloadBytes = 1_000,
            maxRetainedPayloadBytes = budget,
        )
        val initial = store.append(
            DiagnosticStoredState(metadata = metadata()),
            retained,
        )

        val state = store.append(initial, oversized)

        assertEquals(listOf(1L), state.events.map(DiagnosticEvent::sequence))
        assertEquals(budget, state.retainedPayloadBytes)
        assertEquals(3L, state.metadata.nextSequence)
        assertEquals(1L, state.metadata.droppedEventCount)
        assertEquals(0L, state.metadata.evictedEventCount)
        assertEquals(DiagnosticCaptureState.RUNNING, state.metadata.state)
        assertFalse(state.metadata.capacityLimitReached)
        assertTrue(eventFile(root, 1L).isFile)
        assertFalse(eventFile(root, 2L).exists())
    }

    @Test
    fun `load applies the rolling budget to legacy files and deletes the oldest`() {
        val first = wireEvent(1L, "older")
        val second = wireEvent(2L, "newer")
        val permissive = DiagnosticCaptureStore(
            directory = root,
            maxPayloadBytes = 100,
            maxRetainedPayloadBytes = serializedBytes(first) + serializedBytes(second),
        )
        permissive.appendBatch(
            DiagnosticStoredState(metadata = metadata()),
            listOf(first, second),
        )

        val tightened = DiagnosticCaptureStore(
            root,
            maxPayloadBytes = 100,
            maxRetainedPayloadBytes = serializedBytes(second),
        ).load()
        val reloaded = DiagnosticCaptureStore(
            root,
            maxPayloadBytes = 100,
            maxRetainedPayloadBytes = serializedBytes(second),
        ).load()

        assertEquals(listOf(2L), tightened.events.map(DiagnosticEvent::sequence))
        assertEquals(listOf(serializedBytes(second)), tightened.retainedEventBytes)
        assertEquals(serializedBytes(second), tightened.retainedPayloadBytes)
        assertEquals(3L, tightened.metadata.nextSequence)
        assertEquals(1L, tightened.metadata.evictedEventCount)
        assertEquals(DiagnosticCaptureState.RUNNING, tightened.metadata.state)
        assertFalse(tightened.metadata.capacityLimitReached)
        assertFalse(eventFile(root, 1L).exists())
        assertTrue(eventFile(root, 2L).isFile)
        assertEquals(tightened, reloaded)
    }

    @Test
    fun `legacy capacity pause resumes while a manual pause remains paused`() {
        val capacityRoot = File(root, "capacity")
        val capacityStore = DiagnosticCaptureStore(capacityRoot)
        val capacityEvent = wireEvent(1L, "capacity")
        val capacityState = capacityStore.append(
            DiagnosticStoredState(metadata = metadata()),
            capacityEvent,
        )
        capacityStore.persistMetadata(
            capacityState.copy(
                metadata = capacityState.metadata.copy(
                    state = DiagnosticCaptureState.PAUSED,
                    capacityLimitReached = true,
                ),
            ),
        )

        val resumed = DiagnosticCaptureStore(capacityRoot).load()

        assertEquals(DiagnosticCaptureState.RUNNING, resumed.metadata.state)
        assertFalse(resumed.metadata.capacityLimitReached)
        assertEquals(listOf(1L), resumed.events.map(DiagnosticEvent::sequence))

        val manualRoot = File(root, "manual")
        val manualStore = DiagnosticCaptureStore(manualRoot)
        val manualState = manualStore.append(
            DiagnosticStoredState(metadata = metadata(state = DiagnosticCaptureState.PAUSED)),
            wireEvent(1L, "manual"),
        )

        val restoredManual = DiagnosticCaptureStore(manualRoot).load()

        assertEquals(DiagnosticCaptureState.PAUSED, manualState.metadata.state)
        assertEquals(DiagnosticCaptureState.PAUSED, restoredManual.metadata.state)
        assertFalse(restoredManual.metadata.capacityLimitReached)
    }

    @Test
    fun `load drops a persisted event that cannot fit by itself`() {
        val oversized = wireEvent(5L, "x".repeat(100))
        val encoded = eventJson.encodeToString(oversized)
        eventFile(root, 5L).apply {
            parentFile?.mkdirs()
            writeText(encoded, Charsets.UTF_8)
        }
        val store = DiagnosticCaptureStore(
            directory = root,
            maxPayloadBytes = 1_000,
            maxRetainedPayloadBytes = serializedBytes(oversized) - 1L,
        )

        val restored = store.load()

        assertTrue(restored.events.isEmpty())
        assertEquals(1L, restored.metadata.droppedEventCount)
        assertEquals(6L, restored.metadata.nextSequence)
        assertEquals(0L, restored.retainedPayloadBytes)
        assertTrue(restored.retainedEventBytes.isEmpty())
        assertFalse(eventFile(root, 5L).exists())
    }

    @Test
    fun `load preserves legacy json bytes when normalization does not change the event`() {
        val event = wireEvent(1L, "legacy")
        val file = eventFile(root, 1L).apply {
            parentFile?.mkdirs()
            writeText("  ${eventJson.encodeToString(event)}\n", Charsets.UTF_8)
        }
        val originalBytes = file.length()

        val restored = DiagnosticCaptureStore(root).load()

        assertEquals(listOf(event), restored.events)
        assertEquals(listOf(originalBytes), restored.retainedEventBytes)
        assertEquals(originalBytes, restored.retainedPayloadBytes)
        assertEquals(originalBytes, file.length())
        assertTrue(file.readText(Charsets.UTF_8).startsWith("  "))
    }

    @Test
    fun `legacy schema one metadata defaults capacity marker to false`() {
        root.mkdirs()
        File(root, "capture-metadata.json").writeText(
            """{"schemaVersion":1,"state":"RUNNING","sessionId":"legacy","startedAtMillis":5,"nextSequence":1,"droppedEventCount":0,"evictedEventCount":2,"truncatedPayloadCount":0}""",
        )

        val restored = DiagnosticCaptureStore(root).load()

        assertEquals(DiagnosticCaptureState.RUNNING, restored.metadata.state)
        assertEquals("legacy", restored.metadata.sessionId)
        assertEquals(2L, restored.metadata.evictedEventCount)
        assertFalse(restored.metadata.capacityLimitReached)
    }

    @Test
    fun `reload truncates oversized persisted payload once and recounts serialized bytes`() {
        val permissive = DiagnosticCaptureStore(root, maxPayloadBytes = 100, maxRetainedPayloadBytes = 10_000L)
        val state = permissive.append(
            DiagnosticStoredState(metadata = metadata()),
            wireEvent(1L, "abcdef"),
        )

        val tightened = DiagnosticCaptureStore(
            root,
            maxPayloadBytes = 4,
            maxRetainedPayloadBytes = 10_000L,
        ).load()
        val secondReload = DiagnosticCaptureStore(
            root,
            maxPayloadBytes = 4,
            maxRetainedPayloadBytes = 10_000L,
        ).load()

        assertEquals("abcd", tightened.events.single().wireText())
        assertEquals(1L, tightened.metadata.truncatedPayloadCount)
        assertEquals(state.metadata.evictedEventCount, tightened.metadata.evictedEventCount)
        assertEquals(serializedBytes(tightened.events.single()), tightened.retainedPayloadBytes)
        assertEquals(listOf(tightened.retainedPayloadBytes), tightened.retainedEventBytes)
        assertEquals(1L, secondReload.metadata.truncatedPayloadCount)
        assertEquals(tightened.retainedPayloadBytes, secondReload.retainedPayloadBytes)
    }

    @Test
    fun `batch rejects non-positive event sequences`() {
        val store = DiagnosticCaptureStore(root)

        assertThrows(IllegalArgumentException::class.java) {
            store.appendBatch(
                DiagnosticStoredState(metadata = metadata(nextSequence = 0L)),
                listOf(wireEvent(0L, "invalid")),
            )
        }
        assertFalse(eventFile(root, 0L).exists())
    }

    @Test
    fun `clear preserves session state and sequence while resetting completeness counters`() {
        val event = wireEvent(7L, "retained")
        val store = DiagnosticCaptureStore(
            directory = root,
            maxPayloadBytes = 100,
            maxRetainedPayloadBytes = serializedBytes(event),
        )
        val stored = store.append(
            DiagnosticStoredState(
                metadata = metadata(
                    state = DiagnosticCaptureState.PAUSED,
                    sessionId = "paused-session",
                    startedAtMillis = 50L,
                    nextSequence = 7L,
                    dropped = 2L,
                    evicted = 3L,
                    truncated = 4L,
                ),
            ),
            event,
        )

        val cleared = store.clear(stored)
        val restored = store.load()

        assertEquals(listOf(7L), stored.events.map(DiagnosticEvent::sequence))
        assertEquals(8L, stored.metadata.nextSequence)
        assertTrue(cleared.events.isEmpty())
        assertEquals(DiagnosticCaptureState.PAUSED, restored.metadata.state)
        assertEquals("paused-session", restored.metadata.sessionId)
        assertEquals(8L, restored.metadata.nextSequence)
        assertEquals(0L, restored.metadata.droppedEventCount)
        assertEquals(0L, restored.metadata.evictedEventCount)
        assertEquals(0L, restored.metadata.truncatedPayloadCount)
        assertEquals(0L, restored.retainedPayloadBytes)
        assertTrue(restored.retainedEventBytes.isEmpty())
        assertFalse(restored.metadata.capacityLimitReached)
        assertTrue(restored.events.isEmpty())
    }

    @Test
    fun `load drops malformed event and ignores incomplete temporary write`() {
        val events = File(root, "events").apply { mkdirs() }
        eventFile(root, 1L).writeText("not-json")
        File(events, "00000000000000000002.json.tmp").writeText("partial")

        val restored = DiagnosticCaptureStore(root).load()

        assertTrue(restored.events.isEmpty())
        assertEquals(1L, restored.metadata.droppedEventCount)
        assertEquals(2L, restored.metadata.nextSequence)
        assertFalse(eventFile(root, 1L).exists())
        assertFalse(File(events, "00000000000000000002.json.tmp").exists())
    }

    @Test
    fun `metadata backup is used when primary is corrupt`() {
        val store = DiagnosticCaptureStore(root)
        store.persistMetadata(
            DiagnosticStoredState(
                metadata = metadata(
                    state = DiagnosticCaptureState.RUNNING,
                    sessionId = "backup-session",
                ),
            ),
        )
        val metadata = File(root, "capture-metadata.json")
        val backup = File(root, "capture-metadata.json.bak")
        assertTrue(metadata.renameTo(backup))
        metadata.writeText("corrupt")

        val restored = DiagnosticCaptureStore(root).load()

        assertEquals(DiagnosticCaptureState.RUNNING, restored.metadata.state)
        assertEquals("backup-session", restored.metadata.sessionId)
    }

    @Test
    fun `delete all removes persisted session`() {
        val store = DiagnosticCaptureStore(root)
        store.persistMetadata(
            DiagnosticStoredState(
                metadata = metadata(
                    state = DiagnosticCaptureState.RUNNING,
                    sessionId = "to-delete",
                ),
            ),
        )

        val deleted = store.deleteAll()

        assertEquals(DiagnosticCaptureMetadata(), deleted.metadata)
        assertFalse(root.exists())
    }

    private fun metadata(
        state: DiagnosticCaptureState = DiagnosticCaptureState.RUNNING,
        sessionId: String? = "session",
        startedAtMillis: Long? = 1L,
        nextSequence: Long = 1L,
        dropped: Long = 0L,
        evicted: Long = 0L,
        truncated: Long = 0L,
        capacityLimitReached: Boolean = false,
    ) = DiagnosticCaptureMetadata(
        state = state,
        sessionId = sessionId,
        startedAtMillis = startedAtMillis,
        nextSequence = nextSequence,
        droppedEventCount = dropped,
        evictedEventCount = evicted,
        truncatedPayloadCount = truncated,
        capacityLimitReached = capacityLimitReached,
    )

    private fun wireEvent(sequence: Long, value: String) = DiagnosticEvent(
        sequence = sequence,
        timestampMillis = sequence * 10L,
        context = DiagnosticRequestContext(requestKind = "chat"),
        payload = DiagnosticEventPayload.WireLine(
            lineNumber = sequence,
            line = CapturedDiagnosticText(
                value = value,
                originalLength = value.length,
                truncated = false,
                redacted = true,
            ),
        ),
    )

    private fun serializedBytes(event: DiagnosticEvent): Long =
        eventJson.encodeToString(event).toByteArray(Charsets.UTF_8).size.toLong()

    private fun eventFile(directory: File, sequence: Long): File =
        File(directory, "events/${sequence.toString().padStart(20, '0')}.json")

    private fun DiagnosticEvent.wireCapture(): CapturedDiagnosticText =
        (payload as DiagnosticEventPayload.WireLine).line

    private fun DiagnosticEvent.wireText(): String = wireCapture().value
}
