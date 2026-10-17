package com.newoether.agora.diagnostics

import android.os.Build
import androidx.annotation.RequiresApi
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.file.Files
import java.util.ArrayDeque
import java.util.TreeMap
import kotlin.math.max

internal data class DiagnosticStoredState(
    val metadata: DiagnosticCaptureMetadata = DiagnosticCaptureMetadata(),
    val events: List<DiagnosticEvent> = emptyList(),
    val retainedPayloadBytes: Long = 0L,
    val retainedEventBytes: List<Long> = emptyList(),
)

/** Owns the app-private durable representation of the active diagnostic capture session. */
internal class DiagnosticCaptureStore(
    private val directory: File,
    private val maxPayloadBytes: Int = DEFAULT_MAX_PAYLOAD_BYTES,
    private val maxRetainedPayloadBytes: Long = DEFAULT_MAX_RETAINED_PAYLOAD_BYTES,
) {
    private val eventsDirectory = File(directory, EVENTS_DIRECTORY)
    private val metadataFile = File(directory, METADATA_FILE)
    private val json = Json {
        classDiscriminator = "payloadType"
        encodeDefaults = true
        ignoreUnknownKeys = true
    }

    init {
        require(maxPayloadBytes > 0)
        require(maxRetainedPayloadBytes > 0L)
    }

    fun load(): DiagnosticStoredState {
        ensureDirectories()
        cleanupTemporaryFiles(directory)
        cleanupTemporaryFiles(eventsDirectory)

        val loadedMetadata = readMetadata() ?: DiagnosticCaptureMetadata()
        if (loadedMetadata.schemaVersion != SCHEMA_VERSION) return deleteAll()

        var dropped = 0L
        var evicted = 0L
        var maxSequenceSeen = 0L
        var evictionCutoff = 0L
        var retainedBytes = 0L
        val retainedEvents = TreeMap<Long, RetainedEvent>()
        forEachEventFile { file ->
            val sequence = file.name.removeSuffix(EVENT_EXTENSION).toLongOrNull()
            if (
                sequence == null ||
                sequence <= 0L ||
                file.name != eventFile(sequence).name
            ) {
                dropped++
                deleteFile(file)
                return@forEachEventFile
            }
            maxSequenceSeen = max(maxSequenceSeen, sequence)
            if (file.length() > maxRetainedPayloadBytes) {
                dropped++
                deleteFile(file)
                return@forEachEventFile
            }
            val encodedJson = try {
                file.readText(Charsets.UTF_8)
            } catch (_: Exception) {
                null
            }
            val event = try {
                encodedJson?.let { json.decodeFromString<DiagnosticEvent>(it) }
            } catch (_: Exception) {
                null
            }
            if (event == null || event.sequence != sequence) {
                dropped++
                deleteFile(file)
                return@forEachEventFile
            }
            val normalized = normalize(event)
            val rewriteRequired = normalized.event != event
            val eventBytes = if (rewriteRequired) normalized.eventBytes else file.length()
            if (eventBytes > maxRetainedPayloadBytes) {
                dropped++
                deleteFile(file)
                return@forEachEventFile
            }
            if (sequence <= evictionCutoff) {
                evicted++
                deleteFile(file)
                return@forEachEventFile
            }
            retainedEvents[sequence] = RetainedEvent(
                file = file,
                normalized = normalized,
                eventBytes = eventBytes,
                rewriteRequired = rewriteRequired,
            )
            retainedBytes += eventBytes
            while (retainedBytes > maxRetainedPayloadBytes) {
                val oldestEntry = checkNotNull(retainedEvents.pollFirstEntry())
                retainedBytes -= oldestEntry.value.eventBytes
                evictionCutoff = max(evictionCutoff, oldestEntry.key)
                deleteFile(oldestEntry.value.file)
                evicted++
            }
        }

        var newlyTruncated = 0L
        val loadedEvents = mutableListOf<DiagnosticEvent>()
        val loadedEventBytes = mutableListOf<Long>()
        retainedEvents.values.forEach { retained ->
            if (retained.rewriteRequired) {
                writeAtomically(retained.file, retained.normalized.encodedJson)
            }
            newlyTruncated += retained.normalized.newlyTruncatedCount
            loadedEvents += retained.normalized.event
            loadedEventBytes += retained.eventBytes
        }

        val nextSequence = max(
            loadedMetadata.nextSequence,
            maxSequenceSeen + 1L,
        )
        val resumedState = if (
            loadedMetadata.capacityLimitReached &&
            loadedMetadata.sessionId != null &&
            loadedMetadata.state == DiagnosticCaptureState.PAUSED
        ) {
            DiagnosticCaptureState.RUNNING
        } else {
            loadedMetadata.state
        }
        val state = DiagnosticStoredState(
            metadata = loadedMetadata.copy(
                state = resumedState,
                nextSequence = nextSequence,
                droppedEventCount = loadedMetadata.droppedEventCount + dropped,
                evictedEventCount = loadedMetadata.evictedEventCount + evicted,
                truncatedPayloadCount = loadedMetadata.truncatedPayloadCount + newlyTruncated,
                capacityLimitReached = false,
            ),
            events = loadedEvents,
            retainedPayloadBytes = retainedBytes,
            retainedEventBytes = loadedEventBytes,
        )
        persistMetadata(state)
        return state
    }

    fun append(state: DiagnosticStoredState, event: DiagnosticEvent): DiagnosticStoredState =
        appendBatch(state, listOf(event))

    fun appendBatch(
        state: DiagnosticStoredState,
        events: List<DiagnosticEvent>,
    ): DiagnosticStoredState {
        if (events.isEmpty()) return state
        ensureDirectories()
        require(state.events.size == state.retainedEventBytes.size) {
            "Diagnostic event byte accounting is out of sync"
        }
        require(state.retainedEventBytes.sum() == state.retainedPayloadBytes) {
            "Diagnostic retained byte total is out of sync"
        }
        events.forEachIndexed { index, event ->
            require(event.sequence > 0L) {
                "Diagnostic event sequence must be positive: ${event.sequence}"
            }
            require(event.sequence == state.metadata.nextSequence + index) {
                "Diagnostic event sequence is not contiguous: ${event.sequence}"
            }
        }

        val retainedEvents = ArrayDeque<DiagnosticEvent>(state.events.size + events.size)
        val retainedEventBytes = ArrayDeque<Long>(state.retainedEventBytes.size + events.size)
        retainedEvents.addAll(state.events)
        retainedEventBytes.addAll(state.retainedEventBytes)
        var retainedPayloadBytes = state.retainedPayloadBytes
        var dropped = 0L
        var evicted = 0L
        var truncated = 0L
        for (event in events) {
            val target = eventFile(event.sequence)
            require(!target.exists()) {
                "Diagnostic event sequence already exists: ${event.sequence}"
            }
            val normalized = normalize(event)
            if (normalized.eventBytes > maxRetainedPayloadBytes) {
                dropped++
                continue
            }
            writeAtomically(target, normalized.encodedJson)
            retainedEvents.addLast(normalized.event)
            retainedEventBytes.addLast(normalized.eventBytes)
            retainedPayloadBytes += normalized.eventBytes
            truncated += normalized.truncatedCount
            while (retainedPayloadBytes > maxRetainedPayloadBytes) {
                val oldestEvent = checkNotNull(retainedEvents.peekFirst())
                val oldestEventBytes = checkNotNull(retainedEventBytes.peekFirst())
                deleteFile(eventFile(oldestEvent.sequence))
                retainedEvents.removeFirst()
                retainedEventBytes.removeFirst()
                retainedPayloadBytes -= oldestEventBytes
                evicted++
            }
        }

        val next = DiagnosticStoredState(
            metadata = state.metadata.copy(
                nextSequence = events.last().sequence + 1L,
                droppedEventCount = state.metadata.droppedEventCount + dropped,
                evictedEventCount = state.metadata.evictedEventCount + evicted,
                truncatedPayloadCount = state.metadata.truncatedPayloadCount + truncated,
                capacityLimitReached = false,
            ),
            events = retainedEvents.toList(),
            retainedPayloadBytes = retainedPayloadBytes,
            retainedEventBytes = retainedEventBytes.toList(),
        )
        return persistMetadata(next)
    }

    fun persistMetadata(state: DiagnosticStoredState): DiagnosticStoredState {
        ensureDirectories()
        writeAtomically(metadataFile, json.encodeToString(state.metadata))
        return state
    }

    fun clear(state: DiagnosticStoredState): DiagnosticStoredState {
        ensureDirectories()
        forEachEventFile(::deleteFile)
        cleanupTemporaryFiles(eventsDirectory)
        return persistMetadata(
            DiagnosticStoredState(
                metadata = state.metadata.copy(
                    droppedEventCount = 0L,
                    evictedEventCount = 0L,
                    truncatedPayloadCount = 0L,
                    capacityLimitReached = false,
                ),
            ),
        )
    }

    fun deleteAll(): DiagnosticStoredState {
        directory.deleteRecursively()
        return DiagnosticStoredState()
    }

    private fun normalize(event: DiagnosticEvent): NormalizedEvent {
        var truncatedCount = 0L
        var newlyTruncatedCount = 0L
        fun CapturedDiagnosticText.bounded(): CapturedDiagnosticText {
            val bytes = value.toByteArray(Charsets.UTF_8)
            val oversized = bytes.size > maxPayloadBytes
            val bounded = if (!oversized) {
                this
            } else {
                newlyTruncatedCount++
                copy(
                    value = decodeUtf8Prefix(bytes, maxPayloadBytes),
                    truncated = true,
                )
            }
            if (bounded.truncated) truncatedCount++
            return bounded
        }

        val payload = when (val current = event.payload) {
            is DiagnosticEventPayload.HttpRequest -> current.copy(
                url = current.url.bounded(),
                body = current.body.bounded(),
            )
            is DiagnosticEventPayload.HttpResponseBody -> current.copy(body = current.body.bounded())
            is DiagnosticEventPayload.WireLine -> current.copy(line = current.line.bounded())
            is DiagnosticEventPayload.ParsedStreamEvent -> current.copy(
                content = current.content?.bounded(),
            )
            is DiagnosticEventPayload.HttpStage,
            is DiagnosticEventPayload.RuntimeTransition -> current
        }
        val normalized = event.copy(payload = payload)
        val encodedJson = json.encodeToString(normalized)
        return NormalizedEvent(
            event = normalized,
            truncatedCount = truncatedCount,
            newlyTruncatedCount = newlyTruncatedCount,
            encodedJson = encodedJson,
            eventBytes = encodedJson.toByteArray(Charsets.UTF_8).size.toLong(),
        )
    }

    private fun readMetadata(): DiagnosticCaptureMetadata? {
        val backup = backupFile(metadataFile)
        return sequenceOf(metadataFile, backup)
            .filter(File::isFile)
            .mapNotNull { file ->
                runCatching {
                    json.decodeFromString<DiagnosticCaptureMetadata>(file.readText(Charsets.UTF_8))
                }.getOrNull()
            }
            .firstOrNull()
    }

    private fun forEachEventFile(action: (File) -> Unit) {
        forEachFile(eventsDirectory) { file ->
            if (file.isFile && file.name.endsWith(EVENT_EXTENSION)) action(file)
        }
    }

    private fun forEachFile(parent: File, action: (File) -> Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            forEachFileApi26(parent, action)
        } else {
            parent.listFiles().orEmpty().forEach(action)
        }
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private fun forEachFileApi26(parent: File, action: (File) -> Unit) {
        Files.newDirectoryStream(parent.toPath()).use { entries ->
            entries.forEach { path -> action(path.toFile()) }
        }
    }

    private fun eventFile(sequence: Long): File =
        File(eventsDirectory, sequence.toString().padStart(SEQUENCE_DIGITS, '0') + EVENT_EXTENSION)

    private fun ensureDirectories() {
        require(directory.exists() || directory.mkdirs()) { "Unable to create $directory" }
        require(eventsDirectory.exists() || eventsDirectory.mkdirs()) {
            "Unable to create $eventsDirectory"
        }
    }

    private fun writeAtomically(target: File, content: String) {
        val temporary = temporaryFile(target)
        val backup = backupFile(target)
        temporary.delete()
        FileOutputStream(temporary).use { output ->
            output.write(content.toByteArray(Charsets.UTF_8))
            output.fd.sync()
        }
        backup.delete()
        val movedOriginal = target.exists() && target.renameTo(backup)
        require(!target.exists() || movedOriginal) { "Unable to back up $target" }
        if (!temporary.renameTo(target)) {
            if (movedOriginal) backup.renameTo(target)
            throw IllegalStateException("Unable to replace $target")
        }
        backup.delete()
    }

    private fun cleanupTemporaryFiles(parent: File) {
        forEachFile(parent) { file ->
            if (file.isFile && file.name.endsWith(TEMPORARY_SUFFIX)) deleteFile(file)
        }
    }

    private fun deleteFile(file: File) {
        require(file.delete() || !file.exists()) { "Unable to delete $file" }
    }

    private fun temporaryFile(target: File): File = File(target.parentFile, target.name + TEMPORARY_SUFFIX)
    private fun backupFile(target: File): File = File(target.parentFile, target.name + BACKUP_SUFFIX)

    private fun decodeUtf8Prefix(bytes: ByteArray, maxBytes: Int): String =
        Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.IGNORE)
            .onUnmappableCharacter(CodingErrorAction.IGNORE)
            .decode(ByteBuffer.wrap(bytes, 0, maxBytes))
            .toString()

    private data class NormalizedEvent(
        val event: DiagnosticEvent,
        val truncatedCount: Long,
        val newlyTruncatedCount: Long,
        val encodedJson: String,
        val eventBytes: Long,
    )

    private data class RetainedEvent(
        val file: File,
        val normalized: NormalizedEvent,
        val eventBytes: Long,
        val rewriteRequired: Boolean,
    )

    internal companion object {
        const val DEFAULT_MAX_PAYLOAD_BYTES = 2 * 1024 * 1024
        const val DEFAULT_MAX_RETAINED_PAYLOAD_BYTES = 16L * 1024L * 1024L
        private const val SCHEMA_VERSION = 1
        private const val EVENTS_DIRECTORY = "events"
        private const val METADATA_FILE = "capture-metadata.json"
        private const val EVENT_EXTENSION = ".json"
        private const val TEMPORARY_SUFFIX = ".tmp"
        private const val BACKUP_SUFFIX = ".bak"
        private const val SEQUENCE_DIGITS = 20
    }
}
