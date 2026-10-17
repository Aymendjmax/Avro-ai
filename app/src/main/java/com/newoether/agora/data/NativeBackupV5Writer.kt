package com.newoether.agora.data

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry
import org.apache.commons.compress.archivers.zip.ZipArchiveOutputStream

/**
 * Streams version 5 archive entries from single-record iterators. No caller-visible step ever
 * materialises the whole snapshot: each conversation entry is assembled while its already
 * rewritten records stream past, byte-equivalent to the previous per-entry JsonObject assembly.
 */
internal object NativeBackupV5Writer {
    private val CONVERSATION_OPEN = "{\"conversations\":[".encodeToByteArray()
    private val RUNS_SEPARATOR = "],\"runs\":[".encodeToByteArray()
    private val MESSAGES_SEPARATOR = "],\"messages\":[".encodeToByteArray()
    private val LOOPS_SEPARATOR = "],\"loops\":[".encodeToByteArray()
    private val ARRAY_CLOSE = "]}".encodeToByteArray()
    private val TASKS_OPEN = "{\"tasks\":[".encodeToByteArray()
    private val RECORD_SEPARATOR = ','.code

    /** Raw-copies an unchanged conversation item and its referenced media from the baseline. */
    fun copyConversationFromBaseline(
        zip: ZipArchiveOutputStream,
        baseline: NativeBackupV5Baseline,
        baselineEntry: NativeConversationIndexEntry,
        copiedMedia: MutableSet<String>,
    ): NativeConversationIndexEntry {
        baseline.copyRaw(baselineEntry.entry, zip)
        baselineEntry.mediaEntries.forEach { media ->
            if (copiedMedia.add(media)) baseline.copyRaw(media, zip)
        }
        return baselineEntry
    }

    /**
     * Writes one `conv/items/<hex-id>.json` entry from streamed records. The callers supply
     * already archive-rewritten JSON, and [mediaEntries] lists the media entries this payload
     * references so an incremental baseline can raw-copy them later.
     */
    fun writeConversationEntry(
        zip: ZipArchiveOutputStream,
        conversationId: String,
        dataChangedAt: Long,
        conversationJson: String,
        runs: Iterator<String>,
        messages: Iterator<String>,
        loops: Iterator<String>,
        mediaEntries: Set<String>,
    ): NativeConversationIndexEntry {
        val entry = NativeBackupFormat.conversationEntry(conversationId)
        zip.putArchiveEntry(ZipArchiveEntry(entry))
        zip.write(CONVERSATION_OPEN)
        zip.writeJson(conversationJson)
        zip.write(RUNS_SEPARATOR)
        zip.writeRecords(runs)
        zip.write(MESSAGES_SEPARATOR)
        zip.writeRecords(messages)
        zip.write(LOOPS_SEPARATOR)
        zip.writeRecords(loops)
        zip.write(ARRAY_CLOSE)
        zip.closeArchiveEntry()
        return NativeConversationIndexEntry(
            id = conversationId,
            dataChangedAt = dataChangedAt,
            entry = entry,
            mediaEntries = mediaEntries.sorted(),
        )
    }

    /** Writes `conv/tasks.json` from raw task records. */
    fun writeTasksEntry(zip: ZipArchiveOutputStream, tasks: Iterator<String>) {
        zip.putArchiveEntry(ZipArchiveEntry(NativeBackupFormat.TASKS_ENTRY))
        zip.write(TASKS_OPEN)
        zip.writeRecords(tasks)
        zip.write(ARRAY_CLOSE)
        zip.closeArchiveEntry()
    }

    /** Writes `conv/index.json` describing every emitted conversation entry. */
    fun writeConversationIndex(zip: ZipArchiveOutputStream, index: List<NativeConversationIndexEntry>) {
        zip.putArchiveEntry(ZipArchiveEntry(NativeBackupFormat.CONVERSATION_INDEX_ENTRY))
        zip.writeJson(Json.encodeToString(NativeConversationIndex(index)))
        zip.closeArchiveEntry()
    }

    private fun ZipArchiveOutputStream.writeRecords(records: Iterator<String>) {
        var first = true
        while (records.hasNext()) {
            if (!first) write(RECORD_SEPARATOR)
            first = false
            writeJson(records.next())
        }
    }

    private fun ZipArchiveOutputStream.writeJson(value: String) {
        write(value.encodeToByteArray())
    }
}
