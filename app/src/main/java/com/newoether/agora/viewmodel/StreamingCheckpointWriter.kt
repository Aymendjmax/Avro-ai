package com.newoether.agora.viewmodel

import com.newoether.agora.model.ChatMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Single-writer, latest-value-wins persistence lane for streaming checkpoints.
 *
 * Ordinary token updates only replace [requested]; they never wait for Room and cannot build an
 * unbounded queue. A lifecycle boundary calls [flush], which waits until that snapshot or a newer
 * one is durable. [cancelAndJoin] is the terminal-write fence: no stale checkpoint can complete
 * after the final SUCCESS/ERROR/STOPPED transaction.
 */
internal class StreamingCheckpointWriter(
    scope: CoroutineScope,
    private val persist: suspend (ChatMessage) -> Boolean,
    private val onFailure: (Exception) -> Unit,
) {
    private data class Request(val sequence: Long, val message: ChatMessage)
    private data class Completion(val sequence: Long, val targetExists: Boolean)
    private data class BarrierCompletion(
        val targetExists: Boolean,
        val failure: Exception? = null,
    )

    private val accepting = AtomicBoolean(true)
    private val nextSequence = AtomicLong(0L)
    private val requested = MutableStateFlow<Request?>(null)
    private val completed = MutableStateFlow(Completion(0L, targetExists = true))
    private val barrierLock = Any()
    private val barriers = linkedMapOf<Long, CompletableDeferred<BarrierCompletion>>()

    private val writerJob = scope.launch(Dispatchers.IO) {
        requested.filterNotNull().collect { request ->
            val previous = completed.value
            if (request.sequence <= previous.sequence) return@collect
            if (!previous.targetExists) {
                completed.value = Completion(request.sequence, targetExists = false)
                completeBarriers(request.sequence, targetExists = false)
                return@collect
            }
            var failure: Exception? = null
            val targetExists = try {
                persist(request.message)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Ordinary checkpoints remain best-effort, but a flush waiting on this sequence
                // receives the original failure and can stop the guarded lifecycle transition.
                onFailure(e)
                failure = e
                true
            }
            completed.value = Completion(request.sequence, targetExists)
            completeBarriers(request.sequence, targetExists, failure)
        }
    }

    fun enqueue(message: ChatMessage): Long? = enqueue(message, barrier = null)

    suspend fun flush(message: ChatMessage): Boolean {
        val barrier = CompletableDeferred<BarrierCompletion>()
        val sequence = enqueue(message, barrier) ?: return false
        return try {
            val completion = barrier.await()
            completion.failure?.let { throw it }
            completion.targetExists
        } finally {
            synchronized(barrierLock) {
                if (barriers[sequence] === barrier) barriers.remove(sequence)
            }
        }
    }

    suspend fun cancelAndJoin() {
        accepting.set(false)
        writerJob.cancelAndJoin()
        val pending = synchronized(barrierLock) {
            barriers.values.toList().also { barriers.clear() }
        }
        pending.forEach { barrier ->
            barrier.completeExceptionally(
                CancellationException("Streaming checkpoint writer closed before flush completed"),
            )
        }
    }

    private fun enqueue(
        message: ChatMessage,
        barrier: CompletableDeferred<BarrierCompletion>?,
    ): Long? {
        if (!accepting.get() || !completed.value.targetExists) return null
        val sequence = nextSequence.incrementAndGet()
        if (barrier != null) {
            synchronized(barrierLock) { barriers[sequence] = barrier }
        }
        if (!accepting.get() || !completed.value.targetExists) {
            if (barrier != null) {
                synchronized(barrierLock) {
                    if (barriers[sequence] === barrier) barriers.remove(sequence)
                }
            }
            return null
        }
        requested.value = Request(sequence, message)
        return sequence
    }

    private fun completeBarriers(
        sequence: Long,
        targetExists: Boolean,
        failure: Exception? = null,
    ) {
        val ready = synchronized(barrierLock) {
            buildList {
                val iterator = barriers.entries.iterator()
                while (iterator.hasNext()) {
                    val entry = iterator.next()
                    if (entry.key <= sequence) {
                        add(entry.value)
                        iterator.remove()
                    }
                }
            }
        }
        ready.forEach { barrier ->
            barrier.complete(
                BarrierCompletion(
                    targetExists = targetExists,
                    failure = failure,
                ),
            )
        }
    }
}

/** Call-scoped owner of checkpoint throttling and the single durable writer lane. */
internal class StreamingMessageCheckpoints(
    scope: CoroutineScope,
    private val isLatestPersist: () -> Boolean,
    persist: suspend (ChatMessage) -> Boolean,
    onFailure: (Exception) -> Unit,
    private val nowMs: () -> Long = System::currentTimeMillis,
) {
    private val gate = StreamingCheckpointGate()
    private val writer = StreamingCheckpointWriter(
        scope = scope,
        persist = { message -> isLatestPersist() && persist(message) },
        onFailure = onFailure,
    )

    suspend fun persist(message: ChatMessage, force: Boolean = false) {
        persistLazy(force) { message }
    }

    /** Avoids building a complete growing text snapshot until the shared checkpoint gate opens. */
    suspend fun persistLazy(force: Boolean = false, snapshot: () -> ChatMessage) {
        if (!isLatestPersist()) return
        if (!gate.shouldCheckpoint(nowMs(), force)) return
        val message = snapshot()
        if (force) writer.flush(message) else writer.enqueue(message)
    }

    suspend fun close() = writer.cancelAndJoin()
}
