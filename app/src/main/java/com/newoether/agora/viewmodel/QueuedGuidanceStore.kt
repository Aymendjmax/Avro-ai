package com.newoether.agora.viewmodel

import com.newoether.agora.model.AttachmentMeta
import com.newoether.agora.model.MessageSource
import com.newoether.agora.model.SelectedAttachment
import com.newoether.agora.util.AttachmentFiles
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.UUID

/**
 * A message queued behind an in-progress generation. It deliberately remains outside Room until
 * the next durable boundary accepts it into a fresh Run.
 */
internal data class QueuedSend(
    val id: String,
    val text: String,
    /** Model selected in the originating conversation when Send was tapped. */
    val modelId: String,
    val attachments: List<SelectedAttachment>,
    /** Provenance only; drain always creates a fresh Run and never reuses this id. */
    val runId: String,
    /** Pure projection of canonical READY attachment artifacts. */
    val preparedImages: List<String> = emptyList(),
    val preparedAttachmentMetaJson: String? = null,
    /** Immutable foreground admission; legacy/internal queue producers may capture at drain time. */
    val generationSnapshot: GenerationAdmissionSnapshot? = null,
    val createdAt: Long = System.currentTimeMillis(),
    /** Null for a message the user typed; set for automatic input such as ask_user answers. */
    val source: MessageSource? = null,
)

internal data class GuidanceBatchLease(
    val id: String,
    val batch: List<QueuedSend>,
) {
    init {
        require(id.isNotBlank())
        require(batch.isNotEmpty())
    }
}

/**
 * One queue drain becomes one Run whose user bubbles are the FIFO batch with ADJACENT sends of the
 * same kind merged: consecutive typed messages form one bubble, consecutive ask_user answers form
 * another. Order, content and attachment ownership are preserved.
 */
internal fun mergeQueuedGuidance(batch: List<QueuedSend>): List<QueuedSend> {
    require(batch.isNotEmpty())
    val groups = mutableListOf<MutableList<QueuedSend>>()
    batch.forEach { queued ->
        val last = groups.lastOrNull()
        if (last != null && last.last().source?.kind == queued.source?.kind) last += queued
        else groups += mutableListOf(queued)
    }
    return groups.map(::mergeSameKind)
}

private fun mergeSameKind(batch: List<QueuedSend>): QueuedSend {
    val first = batch.first()
    val source = first.source?.let { firstSource ->
        if (firstSource.kind == MessageSource.Kind.ASK_USER) {
            MessageSource.askUser(batch.flatMap { checkNotNull(it.source).askUser })
        } else {
            firstSource
        }
    }
    val attachmentItems = buildList {
        var imageOffset = 0
        batch.forEach { queued ->
            queued.preparedAttachmentMetaJson
                ?.let { raw -> Json.decodeFromString<AttachmentMeta>(raw).items }
                .orEmpty()
                .forEach { item ->
                    add(
                        item.imageIndex?.let { index ->
                            item.copy(imageIndex = imageOffset + index)
                        } ?: item,
                    )
                }
            imageOffset += queued.preparedImages.size
        }
    }
    return first.copy(
        text = if (source != null && source.kind == MessageSource.Kind.ASK_USER) {
            source.askUserReadableText()
        } else {
            batch.joinToString(separator = "\n\n", transform = QueuedSend::text)
        },
        source = source,
        modelId = batch.last().modelId,
        attachments = batch.flatMap(QueuedSend::attachments),
        preparedImages = batch.flatMap(QueuedSend::preparedImages),
        preparedAttachmentMetaJson = attachmentItems
            .takeIf(List<*>::isNotEmpty)
            ?.let { Json.encodeToString(AttachmentMeta(it)) },
        generationSnapshot = batch.last().generationSnapshot,
    )
}

/**
 * Sole owner of pending and claimed in-memory guidance for one conversation.
 *
 * A claim transfers the complete FIFO batch to one lease. Failed claims return to the front;
 * durable claims transfer attachment ownership to Room; disposal owns only still-pending cleanup.
 */
internal class GuidanceLeaseStore(
    private val reclaimAttachments: (List<SelectedAttachment>) -> Unit,
    private val newLeaseId: () -> String = { UUID.randomUUID().toString() },
) {
    private val lock = Any()
    private val _queuedSends = MutableStateFlow<List<QueuedSend>>(emptyList())
    val queuedSends: StateFlow<List<QueuedSend>> = _queuedSends.asStateFlow()

    private val claimedGuidance = mutableMapOf<String, List<QueuedSend>>()
    private var claimRevision = 0L
    private var disposed = false

    /** Monotonic evidence that pending guidance became the next ordinary generation input. */
    fun currentClaimRevision(): Long = synchronized(lock) { claimRevision }

    fun hasPendingOrClaimedSince(revision: Long): Boolean = synchronized(lock) {
        _queuedSends.value.isNotEmpty() || claimRevision != revision
    }

    fun enqueue(send: QueuedSend) {
        synchronized(lock) {
            check(!disposed) { "Conversation guidance store was disposed" }
            AttachmentFiles.setLivePaths(send, AttachmentFiles.ownedPaths(send.attachments) + send.preparedImages)
            _queuedSends.value = _queuedSends.value + send
        }
    }

    fun remove(id: String): QueuedSend? = synchronized(lock) {
        val removed = _queuedSends.value.firstOrNull { it.id == id } ?: return null
        _queuedSends.value = _queuedSends.value.filterNot { it.id == id }
        removed
    }

    /** Transfer the pending batch to one explicit in-flight owner. */
    fun claim(): GuidanceBatchLease? = synchronized(lock) {
        if (disposed || _queuedSends.value.isEmpty()) return null
        val lease = GuidanceBatchLease(newLeaseId(), _queuedSends.value)
        _queuedSends.value = emptyList()
        check(claimedGuidance.put(lease.id, lease.batch) == null)
        claimRevision += 1
        lease
    }

    /** Settle one exact lease without allowing duplicate or unknown results to mutate the queue. */
    fun settle(leaseId: String, durable: Boolean): Boolean {
        var orphaned = emptyList<QueuedSend>()
        synchronized(lock) {
            val batch = claimedGuidance.remove(leaseId) ?: return false
            when {
                durable -> batch.forEach(AttachmentFiles::releaseLivePaths)
                disposed -> orphaned = batch
                else -> _queuedSends.value = batch + _queuedSends.value
            }
        }
        orphaned.forEach(::discard)
        return true
    }

    fun discard(send: QueuedSend) {
        AttachmentFiles.releaseLivePaths(send)
        if (send.attachments.isNotEmpty()) reclaimAttachments(send.attachments)
    }

    /** Mark the owner closed and transfer its still-pending batch to the disposal caller. */
    fun disposePending(): List<QueuedSend> = synchronized(lock) {
        disposed = true
        _queuedSends.value.also { _queuedSends.value = emptyList() }
    }
}
