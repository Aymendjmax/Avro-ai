package com.newoether.agora.data

import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStream
import java.io.OutputStreamWriter
import java.io.BufferedWriter
import java.io.RandomAccessFile
import java.nio.channels.Channels

private const val SNAPSHOT_CONVERSATION = "C"
private const val SNAPSHOT_RUN = "R"
private const val SNAPSHOT_MESSAGE = "M"
private const val SNAPSHOT_TASK = "T"
private const val SNAPSHOT_LOOP = "L"

/** Byte range covering one or more newline-delimited spool records. */
internal data class ExportSpoolSlice(val startOffset: Long, val endOffset: Long)

/** One exported conversation's byte slice plus the watermark needed to index it. */
internal data class ExportSpoolConversation(
    val id: String,
    val dataChangedAt: Long,
    val slice: ExportSpoolSlice,
)

/**
 * In-memory map over an export spool. Sized by layout metadata only: one entry per conversation
 * plus one range per task block, never the record bodies themselves.
 */
internal class ExportSpoolIndex(
    val conversations: List<ExportSpoolConversation>,
    val taskSlices: List<ExportSpoolSlice>,
) {
    val watermarks: Map<String, Long>
        get() = conversations.associate { it.id to it.dataChangedAt }
}

/**
 * Writes newline-delimited spool records while recording the byte slices needed to revisit one
 * conversation at a time. Record payloads are single-line JSON, so '\n' is a safe frame and the
 * writer never needs to re-scan the file to locate a record.
 */
internal class ExportSpoolWriter(file: File) : Closeable {
    private class CountingOutputStream(private val delegate: OutputStream) : OutputStream() {
        var written = 0L
            private set

        override fun write(byteValue: Int) {
            delegate.write(byteValue)
            written++
        }

        override fun write(buffer: ByteArray, offset: Int, length: Int) {
            delegate.write(buffer, offset, length)
            written += length
        }

        override fun flush() = delegate.flush()

        override fun close() = delegate.close()
    }

    private val counter = CountingOutputStream(FileOutputStream(file))
    private val writer = BufferedWriter(OutputStreamWriter(counter, Charsets.UTF_8))
    private val conversations = mutableListOf<ExportSpoolConversation>()
    private var currentId: String? = null
    private var currentDataChangedAt = 0L
    private var currentStartOffset = 0L
    private val taskRanges = mutableListOf<ExportSpoolSlice>()
    private var taskOpen = false
    private var taskStartOffset = 0L

    fun writeConversation(id: String, dataChangedAt: Long, json: String) {
        closeCurrentConversation()
        closeCurrentTask()
        currentId = id
        currentDataChangedAt = dataChangedAt
        currentStartOffset = counter.written
        writeRecord(SNAPSHOT_CONVERSATION, json)
    }

    fun writeRun(json: String) = writeWithinConversation(SNAPSHOT_RUN, json)

    fun writeMessage(json: String) = writeWithinConversation(SNAPSHOT_MESSAGE, json)

    fun writeLoop(json: String) = writeWithinConversation(SNAPSHOT_LOOP, json)

    fun writeTask(json: String) {
        closeCurrentConversation()
        if (!taskOpen) {
            taskOpen = true
            taskStartOffset = counter.written
        }
        writeRecord(SNAPSHOT_TASK, json)
    }

    /** Flushes pending bytes, closes the open slice boundaries, and returns the spool layout. */
    fun finish(): ExportSpoolIndex {
        closeCurrentConversation()
        closeCurrentTask()
        return ExportSpoolIndex(conversations.toList(), taskRanges.toList())
    }

    override fun close() = writer.close()

    private fun writeWithinConversation(type: String, json: String) {
        check(currentId != null) { "Spool record $type emitted outside a conversation" }
        writeRecord(type, json)
    }

    private fun writeRecord(type: String, json: String) {
        writer.write(type)
        writer.write('\t'.code)
        writer.write(json)
        writer.write('\n'.code)
    }

    private fun closeCurrentConversation() {
        val id = currentId ?: return
        writer.flush()
        conversations += ExportSpoolConversation(
            id = id,
            dataChangedAt = currentDataChangedAt,
            slice = ExportSpoolSlice(currentStartOffset, counter.written),
        )
        currentId = null
    }

    private fun closeCurrentTask() {
        if (!taskOpen) return
        writer.flush()
        taskRanges += ExportSpoolSlice(taskStartOffset, counter.written)
        taskOpen = false
    }
}

/** Reads spool records from byte slices without holding more than the current record. */
internal class ExportSpoolReader(private val file: File) {
    companion object {
        private const val READ_CHUNK_BYTES = 64 * 1024
        private const val NEWLINE = '\n'.code
    }

    fun readRecords(slice: ExportSpoolSlice, onRecord: (type: String, json: String) -> Unit) {
        if (slice.endOffset <= slice.startOffset) return
        RandomAccessFile(file, "r").use { randomAccess ->
            val endOffset = minOf(slice.endOffset, randomAccess.length())
            if (endOffset <= slice.startOffset) return
            randomAccess.seek(slice.startOffset)
            var remaining = endOffset - slice.startOffset
            val input = BufferedInputStream(Channels.newInputStream(randomAccess.channel), READ_CHUNK_BYTES)
            val line = ByteArrayOutputStream()
            val chunk = ByteArray(READ_CHUNK_BYTES)
            while (remaining > 0L) {
                val read = input.read(chunk, 0, minOf(remaining, READ_CHUNK_BYTES.toLong()).toInt())
                if (read < 0) break
                remaining -= read
                var segmentStart = 0
                for (index in 0 until read) {
                    if (chunk[index].toInt() == NEWLINE) {
                        line.write(chunk, segmentStart, index - segmentStart)
                        emitLine(line, onRecord)
                        line.reset()
                        segmentStart = index + 1
                    }
                }
                if (segmentStart < read) line.write(chunk, segmentStart, read - segmentStart)
            }
            if (line.size() > 0) emitLine(line, onRecord)
        }
    }

    private fun emitLine(line: ByteArrayOutputStream, onRecord: (String, String) -> Unit) {
        val text = String(line.toByteArray(), Charsets.UTF_8)
        val tab = text.indexOf('\t')
        if (tab <= 0) throw IOException("Invalid export spool record")
        onRecord(text.substring(0, tab), text.substring(tab + 1))
    }

    /**
     * Pull-based iterator over the raw payloads of matching records in consecutive slices.
     * Only the current record is held; each slice is streamed in fixed chunks and file
     * handles are released as slices are exhausted.
     */
    fun recordIterator(slices: List<ExportSpoolSlice>, type: String? = null): Iterator<String> =
        SpoolRecordIterator(slices, type)

    fun recordIterator(slice: ExportSpoolSlice, type: String): Iterator<String> =
        recordIterator(listOf(slice), type)

    private inner class SpoolRecordIterator(
        private val slices: List<ExportSpoolSlice>,
        private val typeFilter: String?,
    ) : Iterator<String> {
        private var sliceIndex = 0
        private var randomAccess: RandomAccessFile? = null
        private var input: BufferedInputStream? = null
        private var remaining = 0L
        private var chunk = ByteArray(0)
        private var chunkPos = 0
        private val carry = ByteArrayOutputStream()
        private var pending: String? = null
        private var exhausted = false

        override fun hasNext(): Boolean {
            if (pending == null && !exhausted) advance()
            return pending != null
        }

        override fun next(): String {
            if (!hasNext()) throw NoSuchElementException("Export spool iterator exhausted")
            return checkNotNull(pending).also { pending = null }
        }

        private fun advance() {
            while (true) {
                val line = nextLine()
                if (line == null) {
                    exhausted = true
                    closeSource()
                    return
                }
                val tab = line.indexOf('\t')
                if (tab <= 0) {
                    exhausted = true
                    closeSource()
                    throw IOException("Invalid export spool record")
                }
                if (typeFilter == null || line.substring(0, tab) == typeFilter) {
                    pending = line.substring(tab + 1)
                    return
                }
            }
        }

        private fun nextLine(): String? {
            while (true) {
                if (chunkPos >= chunk.size && !refill()) {
                    if (carry.size() == 0) return null
                    val unterminated = String(carry.toByteArray(), Charsets.UTF_8)
                    carry.reset()
                    return unterminated
                }
                var newline = -1
                var index = chunkPos
                while (index < chunk.size) {
                    if (chunk[index].toInt() == NEWLINE) {
                        newline = index
                        break
                    }
                    index++
                }
                if (newline >= 0) {
                    carry.write(chunk, chunkPos, newline - chunkPos)
                    chunkPos = newline + 1
                    val complete = String(carry.toByteArray(), Charsets.UTF_8)
                    carry.reset()
                    return complete
                }
                carry.write(chunk, chunkPos, chunk.size - chunkPos)
                chunkPos = chunk.size
            }
        }

        private fun refill(): Boolean {
            while (true) {
                val stream = input
                if (stream != null) {
                    if (remaining <= 0L) {
                        closeSource()
                        continue
                    }
                    val buffer = ByteArray(minOf(remaining, READ_CHUNK_BYTES.toLong()).toInt())
                    val read = stream.read(buffer, 0, buffer.size)
                    if (read <= 0) {
                        closeSource()
                        continue
                    }
                    remaining -= read
                    chunk = buffer
                    chunkPos = 0
                    return true
                }
                if (!openNextSlice()) return false
            }
        }

        private fun openNextSlice(): Boolean {
            while (sliceIndex < slices.size) {
                val slice = slices[sliceIndex]
                sliceIndex++
                if (slice.endOffset <= slice.startOffset) continue
                val access = RandomAccessFile(file, "r")
                val end = minOf(slice.endOffset, access.length())
                if (end <= slice.startOffset) {
                    access.close()
                    continue
                }
                access.seek(slice.startOffset)
                randomAccess = access
                input = BufferedInputStream(Channels.newInputStream(access.channel), READ_CHUNK_BYTES)
                remaining = end - slice.startOffset
                return true
            }
            return false
        }

        private fun closeSource() {
            input?.close()
            input = null
            randomAccess?.close()
            randomAccess = null
            remaining = 0L
        }
    }
}
