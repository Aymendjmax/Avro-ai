package com.newoether.agora.data

import kotlinx.coroutines.sync.Mutex
import java.util.concurrent.ConcurrentHashMap

/**
 * Process-wide per-model lock for embedding-cache generation and model lifecycle.
 *
 * [com.newoether.agora.service.EmbeddingCacheWorker] is the only embedding generator.
 * Ledger admission, scheduling, model invalidation, settings import, and deletion use the same
 * lock so stale work cannot recreate removed model state. Lock entries remain stable after deletion
 * so existing waiters and later configuration changes cannot become concurrent writers.
 */
object EmbeddingCacheLocks {
    private val mutexes = ConcurrentHashMap<String, Mutex>()

    fun forModel(modelId: String): Mutex = mutexes.computeIfAbsent(modelId) { Mutex() }
}
