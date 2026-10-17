package com.newoether.agora.remote

import com.newoether.agora.model.ChatMessage
import com.newoether.agora.ui.chat.ConversationSearchMatch
import com.newoether.agora.ui.chat.findConversationSearchMatches
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext

/** Retain match metadata only; bodies continue to belong to the bounded hydration cache. */
internal fun remoteHistorySearch(
    state: StateFlow<RemoteState>,
    owner: String,
    query: String,
    loadMessages: suspend (List<String>) -> List<ChatMessage>,
    loadEarlier: suspend (String) -> Unit,
    failed: (Exception) -> Unit,
    isCached: (RemoteMessageGroup) -> Boolean = { false },
) = flow<List<ConversationSearchMatch>> {
    if (query.isBlank()) { emit(emptyList()); return@flow }
    data class Indexed(val revision: List<String>, val matches: List<ConversationSearchMatch>)
    val indexed = mutableMapOf<String, Indexed>()
    var failedCursor: String? = null
    var lastPublished: List<ConversationSearchMatch>? = null
    fun checkOwner() {
        if (state.value.owner != owner || !state.value.hydrationEnabled) throw CancellationException()
    }
    while (true) {
        currentCoroutineContext().ensureActive()
        checkOwner()
        val snapshot = state.value
        val groups = snapshot.messageGroups
        indexed.keys.retainAll(groups.map { it.stub.id }.toSet())
        suspend fun publish() {
            checkOwner()
            val matches = groups.flatMap { indexed[it.stub.id]?.matches.orEmpty() }
            if (matches != lastPublished) { emit(matches); lastPublished = matches }
        }
        val changed = groups.filter { indexed[it.stub.id]?.revision != it.revision }
        val (cached, pending) = changed.partition(isCached)
        for (batch in cached.chunked(64) + pending.chunked(64)) {
            try {
                val messages = loadMessages(batch.map { it.stub.id }).associateBy { it.id }
                checkOwner()
                for (group in batch) {
                    val message = messages[group.stub.id] ?: continue
                    val matches = withContext(Dispatchers.Default) {
                        findConversationSearchMatches(listOf(message), query)
                    }
                    indexed[group.stub.id] = Indexed(group.revision, matches)
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { checkOwner(); failed(error) }
            publish()
        }
        publish()
        // Current-page matches reach the UI before any older network request can suspend/fail.
        val cursor = state.value.historyCursor
        if (cursor != null && cursor != failedCursor) {
            try { loadEarlier(cursor) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { checkOwner(); failedCursor = cursor; failed(error) }
            continue
        }
        state.first {
            it.owner != owner || !it.hydrationEnabled ||
                it.messageGroups != snapshot.messageGroups ||
                it.hydrationRevision != snapshot.hydrationRevision ||
                it.historyCursor != snapshot.historyCursor
        }
        if (state.value.hydrationRevision != snapshot.hydrationRevision) failedCursor = null
    }
}
