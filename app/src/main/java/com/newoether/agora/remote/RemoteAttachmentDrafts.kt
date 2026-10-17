package com.newoether.agora.remote

import android.net.Uri
import com.newoether.agora.model.AttachmentImportState
import com.newoether.agora.model.SelectedAttachment
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import java.io.IOException
import java.util.UUID

internal class RemoteAttachmentDrafts(
    private val store: RemoteAttachmentStore?, private val scope: CoroutineScope,
    private val state: MutableStateFlow<RemoteState>, private val failed: (String, Exception) -> Unit,
) {
    private fun editable(owner: String) = state.value.attempts[owner]?.delivery !in
        setOf(RemoteDelivery.SUBMITTING, RemoteDelivery.ACCEPTED, RemoteDelivery.UNKNOWN)

    fun add(owner: String, uris: List<Uri>) {
        if (!editable(owner) || uris.isEmpty()) return
        if (store == null) { failed(owner, RemoteAttachmentException("Attachment storage is unavailable")); return }
        if (uris.size + state.value.attachments[owner].orEmpty().size > REMOTE_ATTACHMENT_COUNT) {
            failed(owner, RemoteAttachmentException("Choose up to $REMOTE_ATTACHMENT_COUNT attachments")); return
        }
        val items = uris.map { SelectedAttachment(localId = UUID.randomUUID().toString(), uri = it.toString(),
            type = "file", importState = AttachmentImportState.PROCESSING) }
        state.value = state.value.copy(attachments = state.value.attachments +
            (owner to (state.value.attachments[owner].orEmpty() + items)))
        scope.launch {
            for (item in items) {
                try {
                    val imported = store.import(Uri.parse(item.uri), item.localId)
                    val current = state.value.attachments[owner].orEmpty()
                    if (current.none { it.localId == item.localId }) { store.remove(imported); continue }
                    replace(owner, imported)
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (error: Exception) {
                    replace(owner, item.copy(importState = AttachmentImportState.FAILED))
                    failed(owner, error)
                }
            }
        }
    }

    private fun replace(owner: String, item: SelectedAttachment) {
        state.value = state.value.copy(attachments = state.value.attachments + (owner to
            state.value.attachments[owner].orEmpty().map { if (it.localId == item.localId) item else it }))
    }

    fun remove(owner: String, id: String) {
        if (!editable(owner)) return
        val current = state.value.attachments[owner].orEmpty()
        current.firstOrNull { it.localId == id }?.let { store?.remove(it) }
        state.value = state.value.copy(attachments = state.value.attachments + (owner to current.filterNot { it.localId == id }))
    }

    fun retry(owner: String, id: String) {
        if (!editable(owner)) return
        val item = state.value.attachments[owner].orEmpty().firstOrNull { it.localId == id &&
            it.importState == AttachmentImportState.FAILED } ?: return
        remove(owner, id)
        add(owner, listOf(Uri.parse(item.uri)))
    }
}
