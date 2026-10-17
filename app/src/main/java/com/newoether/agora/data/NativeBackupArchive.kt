package com.newoether.agora.data

import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.File
import java.io.FileInputStream
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.nio.channels.SeekableByteChannel
import java.util.zip.CRC32
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry
import org.apache.commons.compress.archivers.zip.ZipFile as CommonsZipFile

/**
 * On-demand reader over a validated backup ZIP. Opening checks the whole central directory and
 * byte-verifies the small in-memory metadata, so a preview never has to decompress the archive.
 * Streamed payloads carry no fixed size cap and are byte-verified as they are read: a [stream]
 * that is read to its end fails on a size or CRC mismatch. Resources are capacity-checked before
 * import and verified while they are copied.
 */
internal class NativeBackupArchive private constructor(
    private val zip: CommonsZipFile,
    private val sourceCloseable: Closeable?,
    private val ownedTemporaryFile: File?,
    private val entries: Map<String, ZipArchiveEntry>,
    private val metadataLimitBytes: Long,
) : Closeable, NativeGraphEntrySource {
    override fun has(name: String): Boolean = entries.containsKey(name)

    fun size(name: String): Long = entries[name]?.size ?: -1L

    override fun bytes(name: String): ByteArray? {
        val entry = entries[name] ?: return null
        if (isResourceEntry(name)) {
            throw IOException("Resource entry must be copied to storage: $name")
        }
        if (isStreamedPayload(name)) {
            throw IOException("Streamed payload must be read as a stream: $name")
        }
        return zip.getInputStream(entry).use { input ->
            readBounded(input, metadataLimitBytes, name)
        }
    }

    operator fun get(name: String): ByteArray? = bytes(name)

    override fun stream(name: String): InputStream? {
        val entry = entries[name] ?: return null
        if (isResourceEntry(name)) {
            throw IOException("Resource entry must be copied to storage: $name")
        }
        return CheckedEntryStream(zip.getInputStream(entry), entry.size, entry.crc, name)
    }

    fun prefix(name: String, byteCount: Int): ByteArray? {
        require(byteCount >= 0)
        val entry = entries[name] ?: return null
        return zip.getInputStream(entry).use { input ->
            val bytes = ByteArray(byteCount)
            var total = 0
            while (total < byteCount) {
                val count = input.read(bytes, total, byteCount - total)
                if (count < 0) break
                if (count == 0) continue
                total += count
            }
            bytes.copyOf(total)
        }
    }

    fun names(): List<String> = entries.keys.toList()

    fun preflightImportResources(
        conversationsSelected: Boolean,
        settingsSelected: Boolean,
        archiveVersion: Int,
        destinationRoot: File,
        customFontLimitBytes: Long = Long.MAX_VALUE,
        availableBytes: () -> Long = { destinationRoot.usableSpace },
    ): Long {
        val selected = entries.values.filter { entry ->
            conversationsSelected && isConversationResource(entry.name)
        }.toMutableList()
        if (settingsSelected) {
            val fontEntry = if (archiveVersion >= 4) {
                entries[NativeBackupFormat.CUSTOM_FONT_ENTRY]
            } else {
                entries.values.firstOrNull { isLegacyCustomFont(it.name) }
            }
            if (fontEntry != null) selected += fontEntry
        }
        // A v5 import spools every conversation item and the task list to local storage first.
        val spooledEntries = if (conversationsSelected && archiveVersion >= 5) {
            entries.values.filter { entry ->
                entry.name == NativeBackupFormat.TASKS_ENTRY ||
                    entry.name.startsWith(NativeBackupFormat.CONVERSATION_ENTRY_PREFIX)
            }
        } else {
            emptyList()
        }
        val spoolBytes = spooledEntries.fold(0L) { total, entry ->
            checkedAdd(total, entry.size, "Conversation data size is too large")
        }
        val requiredBytes = selected.fold(spoolBytes) { total, entry ->
            val extractedSize = if (
                isLegacyCustomFont(entry.name) && entry.size > customFontLimitBytes
            ) {
                0L
            } else {
                entry.size
            }
            checkedAdd(total, extractedSize, "Selected resource size is too large")
        }
        // Contents are verified by the checked copy that extracts them; reading them here too
        // would double the cost of a large import.
        ensureAvailable(requiredBytes, availableBytes(), destinationRoot)
        return requiredBytes
    }

    fun copyTo(
        name: String,
        target: File,
        maxBytes: Long = Long.MAX_VALUE,
        availableBytes: () -> Long = { target.parentFile?.usableSpace ?: target.usableSpace },
    ): Long? {
        val entry = entries[name] ?: return null
        return zip.getInputStream(entry).use { input ->
            copyStreamToFile(
                input = input,
                target = target,
                declaredSize = entry.size,
                expectedCrc = entry.crc,
                maxBytes = maxBytes,
                availableBytes = availableBytes,
                sourceName = name,
            )
        }
    }


    override fun close() {
        try {
            zip.close()
        } finally {
            try {
                sourceCloseable?.close()
            } finally {
                ownedTemporaryFile?.delete()
            }
        }
    }

    companion object {
        internal const val MAX_METADATA_BYTES = 256L * 1024L * 1024L
        private const val BUFFER_BYTES = 32 * 1024
        private const val NON_SEEKABLE_SOURCE_MESSAGE =
            "Backup source does not support random access; download it to local storage and select the local file"

        fun open(context: Context, uri: Uri): NativeBackupArchive? {
            val descriptor = context.contentResolver.openFileDescriptor(uri, "r") ?: return null
            val source = ParcelFileDescriptor.AutoCloseInputStream(descriptor)
            return try {
                openSeekableChannel(
                    channel = source.channel,
                    sourceCloseable = source,
                    ownedTemporaryFile = null,
                )
            } catch (error: Exception) {
                runCatching { source.close() }
                if (error is IOException) throw error
                throw IOException("Invalid backup archive", error)
            }
        }

        internal fun open(
            temporaryFile: File,
            metadataLimitBytes: Long = MAX_METADATA_BYTES,
        ): NativeBackupArchive {
            val source = FileInputStream(temporaryFile)
            return try {
                openSeekableChannel(
                    channel = source.channel,
                    sourceCloseable = source,
                    ownedTemporaryFile = temporaryFile,
                    metadataLimitBytes = metadataLimitBytes,
                )
            } catch (error: Exception) {
                runCatching { source.close() }
                temporaryFile.delete()
                if (error is IOException) throw error
                throw IOException("Invalid backup archive", error)
            }
        }

        internal fun open(
            channel: SeekableByteChannel,
            metadataLimitBytes: Long = MAX_METADATA_BYTES,
        ): NativeBackupArchive =
            openSeekableChannel(
                channel = channel,
                sourceCloseable = null,
                ownedTemporaryFile = null,
                metadataLimitBytes = metadataLimitBytes,
            )

        private fun openSeekableChannel(
            channel: SeekableByteChannel,
            sourceCloseable: Closeable?,
            ownedTemporaryFile: File?,
            metadataLimitBytes: Long = MAX_METADATA_BYTES,
        ): NativeBackupArchive {
            try {
                val position = channel.position()
                channel.position(position)
                channel.size()
            } catch (error: Exception) {
                runCatching { channel.close() }
                throw IOException(NON_SEEKABLE_SOURCE_MESSAGE, error)
            }

            var zip: CommonsZipFile? = null
            try {
                zip = CommonsZipFile.builder()
                    .setSeekableByteChannel(channel)
                    .get()
                val entries = validateArchive(zip, metadataLimitBytes)
                return NativeBackupArchive(
                    zip = zip,
                    sourceCloseable = sourceCloseable,
                    ownedTemporaryFile = ownedTemporaryFile,
                    entries = entries,
                    metadataLimitBytes = metadataLimitBytes,
                )
            } catch (error: Exception) {
                runCatching { zip?.close() ?: channel.close() }
                ownedTemporaryFile?.delete()
                if (error is IOException) throw error
                throw IOException("Invalid backup archive", error)
            }
        }

        internal fun copyStreamToFile(
            input: InputStream,
            target: File,
            declaredSize: Long = -1L,
            expectedCrc: Long = -1L,
            maxBytes: Long = Long.MAX_VALUE,
            availableBytes: () -> Long = {
                target.parentFile?.usableSpace ?: target.usableSpace
            },
            bufferBytes: Int = BUFFER_BYTES,
            sourceName: String = target.name,
        ): Long {
            require(bufferBytes > 0)
            try {
                if (declaredSize < -1L) throw IOException("Invalid declared size for $sourceName")
                if (declaredSize > maxBytes) {
                    throw IOException("$sourceName exceeds the import size limit")
                }
                target.parentFile?.let { parent ->
                    if (!parent.isDirectory && !parent.mkdirs()) {
                        throw IOException("Could not create import destination: ${parent.absolutePath}")
                    }
                }
                if (declaredSize >= 0L) {
                    ensureAvailable(declaredSize, availableBytes(), target)
                }
                target.outputStream().buffered().use { output ->
                    val buffer = ByteArray(bufferBytes)
                    val crc = CRC32()
                    var total = 0L
                    while (true) {
                        val readLimit = if (
                            declaredSize >= 0L && declaredSize - total < buffer.size
                        ) {
                            (declaredSize - total + 1L).toInt()
                        } else {
                            buffer.size
                        }
                        val count = input.read(buffer, 0, readLimit)
                        if (count < 0) break
                        if (count == 0) continue
                        total = checkedAdd(total, count.toLong(), "$sourceName is too large")
                        if (declaredSize >= 0L && total > declaredSize) {
                            throw IOException(
                                "$sourceName size mismatch: declared $declaredSize bytes, read more",
                            )
                        }
                        if (total > maxBytes) {
                            throw IOException("$sourceName exceeds the import size limit")
                        }
                        ensureAvailable(count.toLong(), availableBytes(), target)
                        crc.update(buffer, 0, count)
                        output.write(buffer, 0, count)
                    }
                    verifyEntry(total, declaredSize, crc.value, expectedCrc, sourceName)
                    return total
                }
            } catch (error: Exception) {
                target.delete()
                throw error
            }
        }

        private fun validateArchive(
            zip: CommonsZipFile,
            metadataLimitBytes: Long,
        ): Map<String, ZipArchiveEntry> {
            require(metadataLimitBytes >= 0L)
            val files = linkedMapOf<String, ZipArchiveEntry>()
            val canonicalNames = mutableSetOf<String>()
            var declaredMetadataBytes = 0L
            val enumeration = zip.entries
            while (enumeration.hasMoreElements()) {
                val entry = enumeration.nextElement()
                validateRawEntryName(entry)
                val canonicalName = validateEntryName(entry)
                if (!canonicalNames.add(canonicalName)) {
                    throw IOException("Duplicate or ambiguous ZIP entry: ${entry.name}")
                }
                if (entry.size < 0L) {
                    throw IOException("ZIP entry has an unknown declared size: ${entry.name}")
                }
                if (entry.crc < 0L) {
                    throw IOException("ZIP entry has an unknown CRC: ${entry.name}")
                }
                if (entry.isDirectory) {
                    if (entry.size > 0L) {
                        throw IOException("ZIP directory entry contains data: ${entry.name}")
                    }
                    continue
                }
                files[entry.name] = entry
                if (isInMemoryMetadata(entry.name)) {
                    declaredMetadataBytes = checkedAdd(
                        declaredMetadataBytes,
                        entry.size,
                        "Backup metadata is too large",
                    )
                    if (declaredMetadataBytes > metadataLimitBytes) {
                        throw IOException("Backup metadata exceeds the 256 MiB limit")
                    }
                }
            }
            canonicalNames.forEach { name ->
                var slash = name.lastIndexOf('/')
                while (slash > 0) {
                    val parent = name.substring(0, slash)
                    if (parent in files) {
                        throw IOException("Ambiguous ZIP entry path: $name")
                    }
                    slash = parent.lastIndexOf('/')
                }
            }
            // Streamed payloads are the bulk of a large backup; they are verified where they are read
            // instead of costing a full extra pass here.
            files.values.asSequence()
                .filter { isInMemoryMetadata(it.name) }
                .forEach { entry ->
                    zip.getInputStream(entry).use { input ->
                        consumeChecked(input, entry.size, entry.crc, metadataLimitBytes, entry.name)
                    }
                }
            return files
        }

        private fun validateRawEntryName(entry: ZipArchiveEntry) {
            if (entry.rawName.any { it == '\\'.code.toByte() }) {
                throw IOException("Ambiguous ZIP entry path: ${entry.name}")
            }
        }

        private fun validateEntryName(entry: ZipArchiveEntry): String {
            val name = entry.name
            if (name.isEmpty() || name.startsWith('/') || name.startsWith('\\')) {
                throw IOException("Unsafe ZIP entry path: $name")
            }
            if ('\\' in name || (name.length >= 2 && name[0].isLetter() && name[1] == ':')) {
                throw IOException("Ambiguous ZIP entry path: $name")
            }
            val canonical = if (entry.isDirectory) name.dropLast(1) else name
            if (canonical.isEmpty()) throw IOException("Unsafe ZIP entry path: $name")
            val segments = canonical.split('/')
            if (segments.any { it.isEmpty() || it == "." || it == ".." }) {
                throw IOException("Unsafe ZIP entry path: $name")
            }
            return canonical
        }

        private fun isResourceEntry(name: String): Boolean =
            isConversationResource(name) || isLegacyCustomFont(name)

        private fun isStreamedPayload(name: String): Boolean =
            name == NativeBackupFormat.CONVERSATIONS_ENTRY ||
                name == NativeBackupFormat.TASKS_ENTRY ||
                name.startsWith(NativeBackupFormat.CONVERSATION_ENTRY_PREFIX)

        private fun isInMemoryMetadata(name: String): Boolean =
            !isResourceEntry(name) && !isStreamedPayload(name)

        private fun isConversationResource(name: String): Boolean =
            name.startsWith(NativeBackupFormat.IMAGE_MEDIA_PREFIX) ||
                name.startsWith(NativeBackupFormat.VIDEO_MEDIA_PREFIX) ||
                name.startsWith(NativeBackupFormat.DRAFT_MEDIA_PREFIX) ||
                name.startsWith("images/") ||
                name.startsWith("videos/")

        private fun isLegacyCustomFont(name: String): Boolean =
            name.startsWith("custom_font/") &&
                !name.removePrefix("custom_font/").contains('/')

        private fun readBounded(
            input: InputStream,
            maxBytes: Long,
            sourceName: String,
        ): ByteArray {
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(BUFFER_BYTES)
            var total = 0L
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                if (count == 0) continue
                total = checkedAdd(total, count.toLong(), "$sourceName is too large")
                if (total > maxBytes) {
                    throw IOException("$sourceName exceeds the metadata size limit")
                }
                output.write(buffer, 0, count)
            }
            return output.toByteArray()
        }

        private fun consumeChecked(
            input: InputStream,
            declaredSize: Long,
            expectedCrc: Long,
            maxBytes: Long,
            sourceName: String,
        ): Long {
            val buffer = ByteArray(BUFFER_BYTES)
            val crc = CRC32()
            var total = 0L
            while (true) {
                val readLimit = if (
                    declaredSize >= 0L && declaredSize - total < buffer.size
                ) {
                    (declaredSize - total + 1L).toInt()
                } else {
                    buffer.size
                }
                val count = input.read(buffer, 0, readLimit)
                if (count < 0) break
                if (count == 0) continue
                total = checkedAdd(total, count.toLong(), "$sourceName is too large")
                if (declaredSize >= 0L && total > declaredSize) {
                    throw IOException(
                        "$sourceName size mismatch: declared $declaredSize bytes, read more",
                    )
                }
                if (total > maxBytes) {
                    throw IOException("$sourceName exceeds the metadata size limit")
                }
                crc.update(buffer, 0, count)
            }
            verifyEntry(total, declaredSize, crc.value, expectedCrc, sourceName)
            return total
        }

        private fun verifyEntry(
            actualSize: Long,
            declaredSize: Long,
            actualCrc: Long,
            expectedCrc: Long,
            sourceName: String,
        ) {
            if (declaredSize >= 0L && actualSize != declaredSize) {
                throw IOException(
                    "$sourceName size mismatch: declared $declaredSize bytes, read $actualSize",
                )
            }
            if (expectedCrc >= 0L && actualCrc != expectedCrc) {
                throw IOException("$sourceName CRC mismatch")
            }
        }

        private fun ensureAvailable(required: Long, available: Long, destination: File) {
            if (required > available) {
                throw IOException(
                    "Insufficient storage for ${destination.absolutePath}: " +
                        "$required bytes required, $available available",
                )
            }
        }

        private fun checkedAdd(left: Long, right: Long, message: String): Long =
            try {
                Math.addExact(left, right)
            } catch (_: ArithmeticException) {
                throw IOException(message)
            }
    }
}

/** Reads the rest of this stream, so a checked archive stream verifies its size and CRC. */
internal fun InputStream.readToEnd() {
    val buffer = ByteArray(8192)
    while (read(buffer, 0, buffer.size) >= 0) Unit
}

/**
 * Reads one streamed payload while checking it against its ZIP record. A mismatch surfaces when
 * the end of the data is reached. Closing early only closes: an aborted or cancelled import must
 * not wait for gigabytes to be read, and a reader that needs the check calls [readToEnd].
 */
private class CheckedEntryStream(
    input: InputStream,
    private val declaredSize: Long,
    private val expectedCrc: Long,
    private val sourceName: String,
) : FilterInputStream(input) {
    private val crc = CRC32()
    private var total = 0L
    private var verified = false

    override fun read(): Int {
        val value = super.read()
        if (value < 0) verify() else record(1) { crc.update(value) }
        return value
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        val count = super.read(b, off, len)
        if (count < 0) verify() else record(count) { crc.update(b, off, count) }
        return count
    }

    override fun skip(n: Long): Long {
        // Skipped bytes still have to be checked, so they are read rather than passed over.
        val buffer = ByteArray(minOf(n, 8192L).toInt().coerceAtLeast(1))
        var skipped = 0L
        while (skipped < n) {
            val count = read(buffer, 0, minOf(buffer.size.toLong(), n - skipped).toInt())
            if (count < 0) break
            skipped += count
        }
        return skipped
    }

    override fun markSupported(): Boolean = false

    private inline fun record(bytes: Int, update: () -> Unit) {
        total += bytes
        if (declaredSize >= 0L && total > declaredSize) {
            throw IOException("$sourceName size mismatch: declared $declaredSize bytes, read more")
        }
        update()
    }

    private fun verify() {
        if (verified) return
        verified = true
        if (declaredSize >= 0L && total != declaredSize) {
            throw IOException("$sourceName size mismatch: declared $declaredSize bytes, read $total")
        }
        if (expectedCrc >= 0L && crc.value != expectedCrc) {
            throw IOException("$sourceName CRC mismatch")
        }
    }
}
