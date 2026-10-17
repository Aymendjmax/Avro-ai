package com.newoether.agora.viewmodel

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

internal const val MAX_PARALLEL_MESSAGE_PROJECTIONS = 4

internal class MessagePayloadProjector(
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default,
    maxParallelism: Int = MAX_PARALLEL_MESSAGE_PROJECTIONS,
) {
    private val permits = Semaphore(maxParallelism)

    init {
        require(maxParallelism > 0)
    }

    suspend fun <T> project(block: suspend () -> T): T =
        permits.withPermit { withContext(dispatcher) { block() } }

    suspend fun <I, O> projectAll(
        inputs: List<I>,
        transform: (I) -> O,
    ): List<O> = coroutineScope {
        inputs.map { input ->
            async { project { transform(input) } }
        }.awaitAll()
    }
}
