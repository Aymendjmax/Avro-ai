package com.newoether.agora.remote

import com.newoether.agora.model.SelectedAttachment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import kotlin.coroutines.coroutineContext

@Serializable
internal data class RemoteUpload(
    val id: String = "", val name: String, val mime: String, val size: Long,
    val sha256: String, val path: String? = null,
)
@Serializable private data class UploadOffset(val offset: Long)

/** The bounded chunk wire shares Filo's authenticated request transport. */
internal suspend fun uploadRemoteAttachment(
    item: SelectedAttachment,
    request: suspend (String, String, RequestBody) -> String,
): RemoteUpload = withContext(Dispatchers.IO) {
    val file = item.localPath?.let(::File) ?: throw FiloInputException()
    val size = file.length()
    if (!file.isFile || size > REMOTE_ATTACHMENT_LIMIT || size != item.fileSize) throw FiloInputException()
    val hash = MessageDigest.getInstance("SHA-256")
    file.inputStream().use { input ->
        val buffer = ByteArray(64 * 1024)
        while (true) {
            coroutineContext.ensureActive()
            val count = input.read(buffer)
            if (count < 0) break
            hash.update(buffer, 0, count)
        }
    }
    val sha256 = hash.digest().joinToString("") { "%02x".format(it) }
    val json = Json { ignoreUnknownKeys = true }
    val jsonType = "application/json".toMediaType()
    val proposal = RemoteUpload(name = item.fileName ?: "attachment", mime = item.mimeType ?: "application/octet-stream",
        size = size, sha256 = sha256)
    val begin = json.decodeFromString<RemoteUpload>(request("v1/uploads", "POST", json.encodeToString(proposal).toRequestBody(jsonType)))
    require(begin.id.matches(Regex("[a-f0-9]{32}")) && begin.name == proposal.name && begin.mime == proposal.mime &&
        begin.size == size && begin.sha256 == sha256) { "Invalid upload receipt" }
    var offset = 0L
    file.inputStream().use { input ->
        val buffer = ByteArray(256 * 1024)
        while (true) {
            coroutineContext.ensureActive()
            val count = input.read(buffer)
            if (count < 0) break
            val result = json.decodeFromString<UploadOffset>(request("v1/uploads/${begin.id}?offset=$offset", "PUT",
                buffer.copyOf(count).toRequestBody("application/octet-stream".toMediaType())))
            if (result.offset != offset + count) throw IOException("Attachment upload offset was not confirmed")
            offset = result.offset
        }
    }
    if (offset != size) throw IOException("Attachment changed during upload")
    val complete = json.decodeFromString<RemoteUpload>(request("v1/uploads/${begin.id}/complete", "POST", "{}".toRequestBody(jsonType)))
    require(complete.copy(path = null) == begin.copy(path = null) && !complete.path.isNullOrBlank()) {
        "Attachment completion was not confirmed"
    }
    complete
}
