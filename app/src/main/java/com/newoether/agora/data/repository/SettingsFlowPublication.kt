package com.newoether.agora.data.repository

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach

/** Share the publication-before-readiness rule; the repository owns signals, state, and scope. */
internal fun <T> Flow<T>.publishSetting(
    scope: CoroutineScope,
    loaded: CompletableDeferred<Unit>,
    publish: (T) -> Unit,
) = onEach { value ->
    // Publish first: an awaiter must never resume while `.value` still exposes the
    // eager default for this particular setting.
    publish(value)
    loaded.complete(Unit)
}.catch { error ->
    loaded.completeExceptionally(error)
    throw error
}.launchIn(scope)
