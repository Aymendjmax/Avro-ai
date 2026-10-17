package com.newoether.agora.remote

import com.newoether.agora.model.ChatMessage
import com.newoether.agora.model.Participant
import com.newoether.agora.ui.chat.ConversationSearchMatch
import com.newoether.agora.ui.chat.retainedConversationSearchMatchIndex
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
class RemoteHistorySearchTest {
    private fun group(id: String, revision: String = "1") = projectRemoteTopology(listOf(
        RemoteMessageNode(id, id, null, "user", 1, revision, 10),
    ), null).single()
    private fun state(cursor: String? = "older") = MutableStateFlow(RemoteState(
        deviceId = "device", session = RemoteSession("session", "Task", "", 1),
        hydrationEnabled = true, historyCursor = cursor, messageGroups = listOf(group("current")),
    ))
    private fun message(id: String, text: String = "needle") = ChatMessage(
        id = id, text = text, participant = Participant.USER,
    )

    @Test fun currentMatchesArriveBeforeOlderPageFinishesAndRemainInCanonicalOrder() = runTest {
        val state = state()
        val gate = CompletableDeferred<Unit>()
        val started = CompletableDeferred<Unit>()
        val results = Channel<List<ConversationSearchMatch>>(Channel.UNLIMITED)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            remoteHistorySearch(state, state.value.owner!!, "needle",
                loadMessages = { ids -> ids.map { message(it, "needle needle") } },
                loadEarlier = {
                    started.complete(Unit)
                    gate.await()
                    state.value = state.value.copy(historyCursor = null,
                        messageGroups = listOf(group("older")) + state.value.messageGroups)
                }, failed = { throw it },
            ).collect { results.send(it) }
        }
        val current = results.receive()
        assertEquals(listOf("current", "current"), current.map { it.messageId })
        started.await()
        assertFalse(gate.isCompleted)
        gate.complete(Unit)
        val all = results.receive()
        assertEquals(listOf("older", "older", "current", "current"), all.map { it.messageId })
        assertEquals(3, retainedConversationSearchMatchIndex(current, all, 1))
    }

    @Test fun olderPageFailurePreservesVisibleMatchesAndReportsOnce() = runTest {
        val state = state()
        val results = Channel<List<ConversationSearchMatch>>(Channel.UNLIMITED)
        val errors = Channel<Exception>(Channel.UNLIMITED)
        var reads = 0
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            remoteHistorySearch(state, state.value.owner!!, "needle",
                loadMessages = { ids -> ids.map { message(it) } },
                loadEarlier = { reads++; throw IOException("fixture offline") },
                failed = { errors.trySend(it) },
            ).collect { results.send(it) }
        }
        assertEquals(1, results.receive().size)
        assertTrue(errors.receive() is IOException)
        assertEquals(1, reads)
        assertTrue(results.tryReceive().isFailure)
        state.value = state.value.copy(messageGroups = listOf(group("current", "2")))
        // A new text revision remains searchable even after the older read failed.
        state.value = state.value.copy(messageGroups = listOf(group("new")))
        assertEquals("new", results.receive().single().messageId)
        assertEquals(1, reads)
    }

    @Test fun liveTextRevisionUpdatesCountWithoutReloadingUnchangedMessages() = runTest {
        val state = state(null)
        state.value = state.value.copy(messageGroups = listOf(group("fixed"), group("current")))
        val results = Channel<List<ConversationSearchMatch>>(Channel.UNLIMITED)
        val reads = mutableListOf<List<String>>()
        var tail = "needle"
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            remoteHistorySearch(state, state.value.owner!!, "needle",
                loadMessages = { ids -> reads += ids; ids.map { message(it, if (it == "current") tail else "needle") } },
                loadEarlier = { error("no older page") }, failed = { throw it },
            ).collect { results.send(it) }
        }
        assertEquals(2, results.receive().size)
        tail = "needle needle"
        state.value = state.value.copy(messageGroups = listOf(group("fixed"), group("current", "2")))
        assertEquals(3, results.receive().size)
        assertEquals(listOf(listOf("fixed", "current"), listOf("current")), reads)
    }

    @Test fun changedOwnerCannotPublishLatePayloadOrReadOlderPages() = runTest {
        val state = state()
        val gate = CompletableDeferred<Unit>()
        val started = CompletableDeferred<Unit>()
        val results = Channel<List<ConversationSearchMatch>>(Channel.UNLIMITED)
        var olderReads = 0
        val job = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            remoteHistorySearch(state, state.value.owner!!, "needle",
                loadMessages = { ids -> started.complete(Unit); gate.await(); ids.map { message(it) } },
                loadEarlier = { olderReads++ }, failed = { throw it },
            ).collect { results.send(it) }
        }
        started.await()
        state.value = state.value.copy(session = RemoteSession("other", "Other", "", 1))
        gate.complete(Unit)
        job.join()
        assertTrue(results.tryReceive().isFailure)
        assertEquals(0, olderReads)
    }

    @Test fun cancelledQueryNeverPublishesPendingResults() = runTest {
        val state = state()
        val started = CompletableDeferred<Unit>()
        val results = Channel<List<ConversationSearchMatch>>(Channel.UNLIMITED)
        val job = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            remoteHistorySearch(state, state.value.owner!!, "needle",
                loadMessages = { started.complete(Unit); CompletableDeferred<List<ChatMessage>>().await() },
                loadEarlier = { error("cancelled query paged") }, failed = { throw it },
            ).collect { results.send(it) }
        }
        started.await()
        job.cancel()
        job.join()
        assertTrue(results.tryReceive().isFailure)
    }

    @Test fun evictedOlderBodyCannotDelayVisibleCachedMatches() = runTest {
        val state = state(null)
        state.value = state.value.copy(messageGroups = listOf(group("evicted"), group("current")))
        val started = CompletableDeferred<Unit>()
        val gate = CompletableDeferred<Unit>()
        val results = Channel<List<ConversationSearchMatch>>(Channel.UNLIMITED)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            remoteHistorySearch(state, state.value.owner!!, "needle",
                loadMessages = { ids ->
                    if ("evicted" in ids) { started.complete(Unit); gate.await() }
                    ids.map { message(it) }
                }, loadEarlier = { error("no older page") }, failed = { throw it },
                isCached = { it.stub.id == "current" },
            ).collect { results.send(it) }
        }
        assertEquals("current", results.receive().single().messageId)
        started.await()
        gate.complete(Unit)
        assertEquals(listOf("evicted", "current"), results.receive().map { it.messageId })
    }
}
