package com.newoether.agora.sandbox

import android.system.Os
import com.newoether.agora.util.SHELL_FILE_EDIT_MAX_BYTES
import com.newoether.agora.util.SHELL_FILE_GLOB_MAX_MATCHES
import com.newoether.agora.util.SHELL_FILE_GREP_MAX_CONTENT_CHARS
import com.newoether.agora.util.SHELL_FILE_GREP_MAX_FILE_BYTES
import com.newoether.agora.util.SHELL_FILE_GREP_MAX_MATCHES
import com.newoether.agora.util.SHELL_FILE_READ_MAX_BYTES
import com.newoether.agora.util.SHELL_FILE_WRITE_MAX_BYTES
import com.newoether.agora.util.ShellFileEditResult
import com.newoether.agora.util.ShellFileReadResult
import com.newoether.agora.util.editShellFileContent
import com.newoether.agora.util.shellFileLineCount
import com.newoether.agora.util.shellFileSha256
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.io.FileOutputStream
internal suspend fun SandboxPathResolver.fileRead(
    path: String,
    offset: Long,
    limit: Long,
): ShellFileReadResult = withContext(Dispatchers.IO) {
    val file = resolvePath(path)
    if (!file.exists()) throw IllegalStateException("File not found: $path")
    val totalBytes = file.length()
    val normalizedOffset = offset.coerceAtLeast(0)
    val start = normalizedOffset.coerceAtMost(totalBytes)
    val effectiveLimit = if (limit in 1..SHELL_FILE_READ_MAX_BYTES) {
        limit
    } else {
        SHELL_FILE_READ_MAX_BYTES
    }
    val requestedBytes = minOf(effectiveLimit, totalBytes - start).toInt()
    val buffer = ByteArray(requestedBytes)
    val bytesRead = java.io.RandomAccessFile(file, "r").use { input ->
        input.seek(start)
        var totalRead = 0
        while (totalRead < buffer.size) {
            val count = input.read(buffer, totalRead, buffer.size - totalRead)
            if (count <= 0) break
            totalRead += count
        }
        totalRead
    }
    val content = String(buffer, 0, bytesRead, Charsets.UTF_8)
    ShellFileReadResult(
        content = content,
        lines = shellFileLineCount(content),
        totalLines = if (totalBytes <= SHELL_FILE_READ_MAX_BYTES) {
            shellFileLineCount(file.readText(Charsets.UTF_8))
        } else {
            0
        },
        totalBytes = totalBytes,
        returnedBytes = content.toByteArray(Charsets.UTF_8).size.toLong(),
        offset = start,
        limit = effectiveLimit,
        truncated = start + bytesRead < totalBytes,
    )
}

private fun writeSandboxFileAtomically(
    file: File,
    content: ByteArray,
    beforeReplace: () -> String? = { null },
): String? {
    if (file.exists() && !file.isFile) return "path is not a regular file"
    val parent = file.parentFile ?: return "file has no parent directory"
    if (!parent.exists() && !parent.mkdirs()) return "failed to create parent directory"
    val mode = if (file.exists()) {
        Os.stat(file.absolutePath).st_mode and 0x1FF
    } else {
        0x1A4
    }
    val tempFile = File.createTempFile(".${file.name}.", ".tmp", parent)
    try {
        FileOutputStream(tempFile).use { output ->
            output.write(content)
            output.fd.sync()
        }
        Os.chmod(tempFile.absolutePath, mode)
        beforeReplace()?.let { return it }
        Os.rename(tempFile.absolutePath, file.absolutePath)
    } finally {
        tempFile.delete()
    }
    return null
}

internal suspend fun SandboxPathResolver.fileWrite(
    path: String,
    content: String,
    mutationMutex: Mutex,
): String? = withContext(Dispatchers.IO) {
    mutationMutex.withLock {
        try {
            val contentBytes = content.toByteArray(Charsets.UTF_8)
            if (contentBytes.size > SHELL_FILE_WRITE_MAX_BYTES) {
                return@withLock "content exceeds 1MB limit"
            }
            val file = resolvePath(path)
            writeSandboxFileAtomically(file, contentBytes)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (e: Exception) {
            "Sandbox file write failed: ${e.message}"
        }
    }
}

internal suspend fun SandboxPathResolver.fileGlob(
    pattern: String,
    basePath: String,
    homeMountPath: String,
    depth: Int?,
): Pair<List<String>, Boolean> = withContext(Dispatchers.IO) {
    currentCoroutineContext().ensureActive()
    val base = resolveSandboxPath(basePath.ifBlank { homeMountPath })
    val files = mutableListOf<String>()
    // null = legacy full recursion; <=0 = explicit unlimited; >=1 = max levels.
    val remaining = if (depth == null || depth <= 0) -1 else depth
    walkVirtualFiles(base.file, files, base.physicalRoot.canonicalPath, base.virtualRoot, remaining)
    currentCoroutineContext().ensureActive()
    val matches = globMatch(files, pattern)
    matches.take(SHELL_FILE_GLOB_MAX_MATCHES) to
        (matches.size >= SHELL_FILE_GLOB_MAX_MATCHES)
}

internal suspend fun SandboxPathResolver.fileGrep(
    pattern: String,
    basePath: String,
    homeMountPath: String,
    fileGlob: String,
): Result<Pair<List<SandboxManager.GrepMatch>, Boolean>> = withContext(Dispatchers.IO) {
    try {
        val regex = Regex(pattern)
        val base = resolveSandboxPath(basePath.ifBlank { homeMountPath })
        val allFiles = mutableListOf<String>()
        walkVirtualFiles(
            base.file,
            allFiles,
            base.physicalRoot.canonicalPath,
            base.virtualRoot,
        )
        currentCoroutineContext().ensureActive()
        val files = if (fileGlob.isBlank()) allFiles else globMatch(allFiles, fileGlob)
        val matches = mutableListOf<SandboxManager.GrepMatch>()
        fileLoop@ for (file in files) {
            currentCoroutineContext().ensureActive()
            try {
                val resolved = if (file.startsWith("/")) resolvePath(file) else resolvePath("/$file")
                if (!resolved.exists() || resolved.length() > SHELL_FILE_GREP_MAX_FILE_BYTES) continue
                val text = resolved.readText(Charsets.UTF_8)
                // Skip binary files: a NUL byte in the content is the standard
                // heuristic grep itself uses to avoid emitting garbage matches.
                if (text.contains('\u0000')) continue
                val lines = text.lineSequence().iterator()
                var lineNumber = 0
                while (lines.hasNext()) {
                    currentCoroutineContext().ensureActive()
                    lineNumber += 1
                    val line = lines.next()
                    if (regex.containsMatchIn(line)) {
                        matches.add(
                            SandboxManager.GrepMatch(
                                path = file,
                                line = lineNumber,
                                content = line.take(SHELL_FILE_GREP_MAX_CONTENT_CHARS),
                            ),
                        )
                        if (matches.size >= SHELL_FILE_GREP_MAX_MATCHES) break@fileLoop
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {}
        }
        Result.success(matches to (matches.size >= SHELL_FILE_GREP_MAX_MATCHES))
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (e: Exception) {
        Result.failure(e)
    }
}

internal suspend fun SandboxPathResolver.fileEdit(
    path: String,
    oldString: String,
    newString: String,
    replaceAll: Boolean,
    mutationMutex: Mutex,
): ShellFileEditResult = withContext(Dispatchers.IO) {
    mutationMutex.withLock {
        try {
            val file = resolvePath(path)
            if (!file.exists()) {
                return@withLock ShellFileEditResult(0, error = "File not found: $path")
            }
            if (!file.isFile) {
                return@withLock ShellFileEditResult(0, error = "path is not a regular file")
            }
            if (file.length() > SHELL_FILE_EDIT_MAX_BYTES) {
                return@withLock ShellFileEditResult(0, error = "file exceeds 16MB edit limit")
            }

            val originalBytes = file.readBytes()
            if (originalBytes.size > SHELL_FILE_EDIT_MAX_BYTES) {
                return@withLock ShellFileEditResult(0, error = "file exceeds 16MB edit limit")
            }
            val (editedContent, editResult) = editShellFileContent(
                content = originalBytes.toString(Charsets.UTF_8),
                oldString = oldString,
                newString = newString,
                replaceAll = replaceAll,
            )
            if (editedContent == null) return@withLock editResult

            val editedBytes = editedContent.toByteArray(Charsets.UTF_8)
            if (editedBytes.size > SHELL_FILE_EDIT_MAX_BYTES) {
                return@withLock ShellFileEditResult(0, error = "edited file exceeds 16MB limit")
            }
            val writeError = writeSandboxFileAtomically(file, editedBytes) {
                if (!file.readBytes().contentEquals(originalBytes)) {
                    "file changed concurrently before edit could be applied"
                } else {
                    null
                }
            }
            if (writeError != null) {
                return@withLock ShellFileEditResult(0, error = writeError)
            }
            editResult.copy(sha256 = shellFileSha256(editedBytes))
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (e: Exception) {
            ShellFileEditResult(0, error = "Sandbox file edit failed: ${e.message}")
        }
    }
}
