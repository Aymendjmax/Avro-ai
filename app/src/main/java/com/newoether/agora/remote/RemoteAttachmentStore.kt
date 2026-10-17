package com.newoether.agora.remote

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.newoether.agora.model.SelectedAttachment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.util.UUID
import kotlin.coroutines.coroutineContext

internal const val REMOTE_ATTACHMENT_LIMIT = 128L * 1024 * 1024
internal const val REMOTE_ATTACHMENT_COUNT = 16
internal class RemoteAttachmentException(message: String) : IOException(message)

/** Copies original bytes into a private draft; no OCR, PDF extraction or conversion. */
internal class RemoteAttachmentStore(context: Context) {
    private val resolver = context.applicationContext.contentResolver
    private val directory = File(context.cacheDir, "remote-attachments")

    suspend fun import(uri: Uri, id: String): SelectedAttachment = withContext(Dispatchers.IO) {
        val mime = resolver.getType(uri) ?: "application/octet-stream"
        if (mime.startsWith("video/", ignoreCase = true)) throw RemoteAttachmentException("Video attachments are not supported")
        var name = "attachment"
        var declaredSize: Long? = null
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME).takeIf { it >= 0 }?.let {
                    name = cursor.getString(it)?.takeIf(String::isNotBlank) ?: name
                }
                cursor.getColumnIndex(OpenableColumns.SIZE).takeIf { it >= 0 && !cursor.isNull(it) }?.let {
                    declaredSize = cursor.getLong(it).takeIf { size -> size >= 0 }
                }
            }
        }
        if (declaredSize != null && declaredSize > REMOTE_ATTACHMENT_LIMIT) throw RemoteContentLimitException()
        if (!directory.isDirectory && !directory.mkdirs()) throw RemoteAttachmentException("Attachment storage is unavailable")
        val file = File(directory, UUID.randomUUID().toString())
        try {
            var count = 0L
            resolver.openInputStream(uri)?.use { input ->
                file.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        coroutineContext.ensureActive()
                        val read = input.read(buffer)
                        if (read < 0) break
                        count += read
                        if (count > REMOTE_ATTACHMENT_LIMIT) throw RemoteContentLimitException()
                        output.write(buffer, 0, read)
                    }
                }
            } ?: throw RemoteAttachmentException("Could not open the selected attachment")
            if (declaredSize != null && count != declaredSize) throw RemoteAttachmentException("The attachment changed while being copied")
            SelectedAttachment(localId = id, uri = uri.toString(), type = if (mime.startsWith("image/")) "image" else "file",
                fileName = name, mimeType = mime, fileSize = count, localPath = file.absolutePath)
        } catch (error: Throwable) { file.delete(); throw error }
    }

    fun remove(item: SelectedAttachment) {
        val path = item.localPath ?: return
        val file = File(path)
        if (file.parentFile?.canonicalFile == directory.canonicalFile) file.delete()
    }
}
