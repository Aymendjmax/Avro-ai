package com.newoether.agora.viewmodel

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

private const val MAX_GLOBAL_ATTACHMENT_PROCESSING = 2
private const val MAX_OWNER_ATTACHMENT_PROCESSING = 1

/** Schedules attachment work for one composer controller without owning draft sessions. */
internal class ComposerAttachmentProcessingQueue(
    private val selectedOwnerId: () -> String?,
) {
    private data class ProcessingKey(
        val ownerId: String,
        val attachmentId: String,
    )

    private class ProcessingRequest(
        val key: ProcessingKey,
        val generation: Long,
        val sequence: Long,
    ) {
        val admitted = CompletableDeferred<Unit>()
    }
    private val processingMutex = Mutex()
    private val queuedProcessing = linkedMapOf<ProcessingKey, ProcessingRequest>()
    private val activeProcessing = mutableMapOf<ProcessingKey, ProcessingRequest>()
    private val activeProcessingByOwner = mutableMapOf<String, Int>()
    private var processingSequence = 0L
    private var lastDispatchedOwnerId: String? = null

    suspend fun <T> withProcessingPermit(
        ownerId: String,
        attachmentId: String,
        generation: Long,
        block: suspend () -> T,
    ): T {
        val request = processingMutex.withLock {
            ProcessingRequest(
                key = ProcessingKey(ownerId, attachmentId),
                generation = generation,
                sequence = ++processingSequence,
            ).also { next ->
                val latestGeneration = maxOf(
                    queuedProcessing[next.key]?.generation ?: Long.MIN_VALUE,
                    activeProcessing[next.key]?.generation ?: Long.MIN_VALUE,
                )
                if (latestGeneration >= generation) {
                    next.admitted.cancel(
                        CancellationException("Superseded attachment processing"),
                    )
                } else {
                    queuedProcessing.put(next.key, next)?.admitted?.cancel()
                    dispatchProcessingLocked()
                }
            }
        }
        return try {
            request.admitted.await()
            block()
        } finally {
            withContext(NonCancellable) {
                processingMutex.withLock {
                    if (queuedProcessing[request.key] === request) {
                        queuedProcessing.remove(request.key)
                    }
                    if (activeProcessing[request.key] === request) {
                        activeProcessing.remove(request.key)
                        val remaining = activeProcessingByOwner.getValue(request.key.ownerId) - 1
                        if (remaining == 0) {
                            activeProcessingByOwner.remove(request.key.ownerId)
                        } else {
                            activeProcessingByOwner[request.key.ownerId] = remaining
                        }
                    }
                    dispatchProcessingLocked()
                }
            }
        }
    }
    suspend fun refreshProcessingPriority() {
        processingMutex.withLock {
            dispatchProcessingLocked()
        }
    }
    private fun dispatchProcessingLocked() {
        while (activeProcessing.size < MAX_GLOBAL_ATTACHMENT_PROCESSING) {
            val eligible = queuedProcessing.values.filter { request ->
                request.key !in activeProcessing &&
                    activeProcessingByOwner.getOrDefault(request.key.ownerId, 0) <
                    MAX_OWNER_ATTACHMENT_PROCESSING
            }
            if (eligible.isEmpty()) return
            val selected = selectedOwnerId()
            val next = eligible
                .filter { it.key.ownerId == selected }
                .minByOrNull(ProcessingRequest::sequence)
                ?.takeUnless {
                    lastDispatchedOwnerId == selected &&
                        eligible.any { request -> request.key.ownerId != selected }
                }
                ?: eligible
                    .filter { it.key.ownerId != lastDispatchedOwnerId }
                    .minByOrNull(ProcessingRequest::sequence)
                ?: eligible.minBy(ProcessingRequest::sequence)
            queuedProcessing.remove(next.key)
            activeProcessing[next.key] = next
            activeProcessingByOwner[next.key.ownerId] =
                activeProcessingByOwner.getOrDefault(next.key.ownerId, 0) + 1
            lastDispatchedOwnerId = next.key.ownerId
            next.admitted.complete(Unit)
        }
    }
}
