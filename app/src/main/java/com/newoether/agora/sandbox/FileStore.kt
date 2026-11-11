package com.newoether.agora.sandbox

import android.content.Context
import java.io.File
import java.io.InputStream
import java.security.MessageDigest
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Metadata record for a permanent FileStore attachment or output file.
 */
@Serializable
data class FileStoreMetadata(
    val fileName: String,
    val safeFileName: String,
    val sandboxPath: String,
    val mimeType: String,
    val fileSize: Long,
    val sha256: String,
    val chatId: String,
    val sourceUri: String? = null,
    val timestamp: Long = System.currentTimeMillis(),
)

/**
 * Central storage and workspace manager for permanent files in the local PRoot sandbox.
 * Standardizes paths:
 * - /home/uploads/<chat>/
 * - /home/outputs/<chat>/
 * - /home/projects/
 * - /home/notes/ (with notes/INDEX.md)
 * - /home/.avro/ (internal metadata and secrets)
 */
object FileStore {
    const val ROOT_DIR_NAME = "sandbox-home"
    const val DEFAULT_MAX_UPLOAD_BYTES = 100L * 1024L * 1024L

    fun getSandboxHomeDir(context: Context): File =
        File(context.filesDir, ROOT_DIR_NAME).apply { mkdirs() }

    /**
     * Initializes the standard directory structure and INDEX.md if absent.
     */
    fun initWorkspace(context: Context) {
        val home = getSandboxHomeDir(context)
        File(home, "uploads").mkdirs()
        File(home, "outputs").mkdirs()
        File(home, "projects").mkdirs()
        File(home, "notes").mkdirs()
        File(home, ".avro").mkdirs()
        File(home, ".avro/metadata").mkdirs()

        val indexMd = File(home, "notes/INDEX.md")
        if (!indexMd.exists()) {
            runCatching {
                indexMd.writeText(
                    "# Avro Notes Index\n\nGlobal notes and workspace index across conversations.\n"
                )
            }
        }
    }

    /**
     * Copies an attachment input stream into /home/uploads/<chatId>/<safeFileName>,
     * computes its SHA-256 hash, and saves persistent JSON metadata.
     * Enforces a maximum upload byte bound to prevent unbounded disk usage.
     */
    fun storeUpload(
        context: Context,
        chatId: String,
        originalFileName: String?,
        inputStream: InputStream,
        mimeType: String?,
        sourceUri: String? = null,
        maxBytes: Long = DEFAULT_MAX_UPLOAD_BYTES,
    ): FileStoreMetadata {
        initWorkspace(context)
        val home = getSandboxHomeDir(context)
        val safeChatId = sanitizeChatId(chatId)
        val chatUploadsDir = File(home, "uploads/$safeChatId").apply { mkdirs() }

        val safeName = sanitizeFileName(originalFileName ?: "attachment")
        val targetFile = allocateUniqueFile(chatUploadsDir, safeName)

        val digest = MessageDigest.getInstance("SHA-256")
        var totalBytes = 0L

        try {
            targetFile.outputStream().use { out ->
                val buffer = ByteArray(8192)
                var read: Int
                while (inputStream.read(buffer).also { read = it } != -1) {
                    totalBytes += read
                    if (totalBytes > maxBytes) {
                        throw IllegalStateException(
                            "Attachment size exceeds FileStore upload limit of ${maxBytes / (1024 * 1024)}MB",
                        )
                    }
                    out.write(buffer, 0, read)
                    digest.update(buffer, 0, read)
                }
            }
        } catch (e: Exception) {
            targetFile.delete()
            throw e
        }

        val sha256 = digest.digest().joinToString("") { "%02x".format(it) }
        val finalMime = mimeType ?: "application/octet-stream"
        val sandboxVirtualPath = "/home/uploads/$safeChatId/${targetFile.name}"

        val metadata = FileStoreMetadata(
            fileName = originalFileName ?: targetFile.name,
            safeFileName = targetFile.name,
            sandboxPath = sandboxVirtualPath,
            mimeType = finalMime,
            fileSize = totalBytes,
            sha256 = sha256,
            chatId = safeChatId,
            sourceUri = sourceUri,
        )

        val metaDir = File(home, ".avro/metadata/$safeChatId").apply { mkdirs() }
        val metaFile = File(metaDir, "${targetFile.name}.json")
        runCatching {
            metaFile.writeText(Json.encodeToString(metadata))
        }

        return metadata
    }

    /**
     * Deletes the conversation-specific folders upon conversation deletion.
     */
    fun deleteChatFolders(context: Context, chatId: String) {
        val home = getSandboxHomeDir(context)
        val safeChatId = sanitizeChatId(chatId)
        runCatching { File(home, "uploads/$safeChatId").deleteRecursively() }
        runCatching { File(home, "outputs/$safeChatId").deleteRecursively() }
        runCatching { File(home, ".avro/metadata/$safeChatId").deleteRecursively() }
    }

    /**
     * Migrates uploaded files and metadata from a temporary or previous chat ID (e.g. NEW_CHAT_WORKSPACE_ID)
     * to the real persistent conversation ID when the first message is sent.
     * Updates sandboxPath on each migrated SelectedAttachment.
     */
    fun migrateChatUploads(
        context: Context,
        fromChatId: String,
        toChatId: String,
        attachments: List<com.newoether.agora.model.SelectedAttachment>,
    ): List<com.newoether.agora.model.SelectedAttachment> {
        val safeFrom = sanitizeChatId(fromChatId)
        val safeTo = sanitizeChatId(toChatId)
        if (safeFrom == safeTo || attachments.isEmpty()) return attachments

        val home = getSandboxHomeDir(context)
        val fromUploadsDir = File(home, "uploads/$safeFrom")
        val fromMetaDir = File(home, ".avro/metadata/$safeFrom")
        val toUploadsDir = File(home, "uploads/$safeTo").apply { mkdirs() }
        val toMetaDir = File(home, ".avro/metadata/$safeTo").apply { mkdirs() }

        val prefixFrom = "/home/uploads/$safeFrom/"

        return attachments.map { attachment ->
            val oldSandboxPath = attachment.sandboxPath
            if (oldSandboxPath != null && oldSandboxPath.startsWith(prefixFrom)) {
                val oldFileName = oldSandboxPath.removePrefix(prefixFrom)
                val sourceFile = File(fromUploadsDir, oldFileName)
                val sourceMetaFile = File(fromMetaDir, "$oldFileName.json")

                if (sourceFile.exists()) {
                    val targetFile = allocateUniqueFile(toUploadsDir, oldFileName)
                    val moved = sourceFile.renameTo(targetFile) || runCatching {
                        sourceFile.copyTo(targetFile, overwrite = true)
                        sourceFile.delete()
                    }.isSuccess

                    if (moved) {
                        val newSandboxPath = "/home/uploads/$safeTo/${targetFile.name}"
                        val oldMeta = if (sourceMetaFile.exists()) {
                            runCatching {
                                Json.decodeFromString<FileStoreMetadata>(sourceMetaFile.readText())
                            }.getOrNull()
                        } else null

                        val updatedMeta = (oldMeta ?: FileStoreMetadata(
                            fileName = attachment.fileName ?: targetFile.name,
                            safeFileName = targetFile.name,
                            sandboxPath = newSandboxPath,
                            mimeType = attachment.mimeType ?: "application/octet-stream",
                            fileSize = targetFile.length(),
                            sha256 = attachment.sha256 ?: "",
                            chatId = safeTo,
                            sourceUri = attachment.uri,
                        )).copy(
                            safeFileName = targetFile.name,
                            sandboxPath = newSandboxPath,
                            chatId = safeTo,
                        )

                        runCatching {
                            File(toMetaDir, "${targetFile.name}.json").writeText(Json.encodeToString(updatedMeta))
                            sourceMetaFile.delete()
                        }

                        return@map attachment.copy(
                            sandboxPath = newSandboxPath,
                            sha256 = updatedMeta.sha256,
                        )
                    }
                }
            }
            attachment
        }.also {
            runCatching {
                if (fromUploadsDir.isDirectory && fromUploadsDir.list().isNullOrEmpty()) {
                    fromUploadsDir.delete()
                }
                if (fromMetaDir.isDirectory && fromMetaDir.list().isNullOrEmpty()) {
                    fromMetaDir.delete()
                }
            }
        }
    }

    fun sanitizeChatId(chatId: String): String =
        chatId.replace(Regex("[^a-zA-Z0-9_.-]"), "_").ifBlank { "default" }

    fun sanitizeFileName(fileName: String): String {
        val base = File(fileName).name
        // Preserve Unicode letters (\p{L}) and numbers (\p{N}), only strip path separators and forbidden filesystem characters
        val sanitized = base.replace(Regex("[\\\\/:*?\"<>|\\u0000-\\u001F\\u007F]"), "_")
            .replace(Regex("[^\\p{L}\\p{N}_.-]"), "_")
            .trim()
        return sanitized.takeUnless { it.isBlank() || it == "." || it == ".." } ?: "attachment"
    }

    internal fun allocateUniqueFile(dir: File, safeName: String): File {
        val dotIndex = safeName.lastIndexOf('.')
        val prefix = if (dotIndex > 0) safeName.substring(0, dotIndex) else safeName
        val suffix = if (dotIndex > 0) safeName.substring(dotIndex) else ""

        var candidate = File(dir, safeName)
        var count = 1
        while (candidate.exists()) {
            candidate = File(dir, "${prefix}_$count$suffix")
            count++
        }
        return candidate
    }

    data class WorkspaceStorageStats(
        val uploadsBytes: Long,
        val outputsBytes: Long,
        val projectsBytes: Long,
        val notesBytes: Long,
        val totalBytes: Long,
    )

    fun getDirectorySizeBytes(dir: File): Long {
        if (!dir.exists() || !dir.isDirectory) return 0L
        return runCatching {
            dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
        }.getOrDefault(0L)
    }

    fun calculateWorkspaceStorage(context: Context): WorkspaceStorageStats {
        val home = getSandboxHomeDir(context)
        val uploads = getDirectorySizeBytes(File(home, "uploads"))
        val outputs = getDirectorySizeBytes(File(home, "outputs"))
        val projects = getDirectorySizeBytes(File(home, "projects"))
        val notes = getDirectorySizeBytes(File(home, "notes"))
        val total = uploads + outputs + projects + notes
        return WorkspaceStorageStats(uploads, outputs, projects, notes, total)
    }

    fun clearOutputs(context: Context): Boolean {
        val outputsDir = File(getSandboxHomeDir(context), "outputs")
        return runCatching {
            outputsDir.listFiles()?.forEach { it.deleteRecursively() }
            true
        }.getOrDefault(false)
    }

    fun clearUploads(context: Context): Boolean {
        val home = getSandboxHomeDir(context)
        val uploadsDir = File(home, "uploads")
        val metaDir = File(home, ".avro/metadata")
        return runCatching {
            uploadsDir.listFiles()?.forEach { it.deleteRecursively() }
            metaDir.listFiles()?.forEach { it.deleteRecursively() }
            true
        }.getOrDefault(false)
    }

    fun clearWorkspace(context: Context): Boolean {
        val home = getSandboxHomeDir(context)
        return runCatching {
            File(home, "outputs").listFiles()?.forEach { it.deleteRecursively() }
            File(home, "uploads").listFiles()?.forEach { it.deleteRecursively() }
            File(home, "projects").listFiles()?.forEach { it.deleteRecursively() }
            File(home, ".avro").listFiles()?.forEach { it.deleteRecursively() }
            initWorkspace(context)
            true
        }.getOrDefault(false)
    }

    fun formatStorageSize(bytes: Long): String {
        if (bytes <= 0L) return "0 B"
        val units = arrayOf("B", "KB", "MB", "GB", "TB")
        var b = bytes.toDouble()
        var unitIndex = 0
        while (b >= 1024.0 && unitIndex < units.lastIndex) {
            b /= 1024.0
            unitIndex++
        }
        return if (unitIndex == 0) "${bytes} B" else String.format(java.util.Locale.US, "%.1f %s", b, units[unitIndex])
    }
}
