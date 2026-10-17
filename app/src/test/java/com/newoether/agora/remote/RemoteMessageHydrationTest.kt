package com.newoether.agora.remote

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class RemoteMessageHydrationTest {
    @Test fun wholeAdmittedPacketRemainsReadyForItsFirstLayoutWithinTheByteBudget() = runTest {
        val state = MutableStateFlow(snapshot())
        val hydration = RemoteMessageHydration(state, { _, _ -> error("Unexpected network") }, { throw it })
        val records = (0 until 128).map { index ->
            native.copy(id = "record-$index", turnId = "turn-$index", groupId = "record-$index", text = "Payload $index")
        }
        val metadata = records.map { node.copy(id = it.id, turnId = it.turnId, groupId = it.groupId, textLength = it.text.length) }
        val groups = projectRemoteTopology(metadata, RemoteRuntime("idle"))
        assertEquals(128, groups.size)
        val owner = state.value.owner!!
        hydration.accept(owner, page(records, metadata), groups, live = false)
        state.value = state.value.copy(messageGroups = groups)
        groups.forEachIndexed { index, group ->
            val message = hydration.cachedMessage(owner, group)
            assertEquals("Page body was evicted before its first frame", "Payload $index", message?.text)
            assertEquals("Payload $index", message!!.preparedMarkdown.values.single().content)
            assertTrue(message.preparedMarkdownBytes > 0)
        }
        assertTrue(hydration.retainedPayloadBytes <= 8L * 1024 * 1024)
    }

    private val native = RemoteMessage("answer", "turn", null, "assistant", "body", 1, groupId = "group")
    private val node = RemoteMessageNode("answer", "turn", null, "assistant", 1, "a".repeat(64), 4, groupId = "group")
    private fun page(messages: List<RemoteMessage>, nodes: List<RemoteMessageNode> = listOf(node)) =
        RemoteConversationPage(messages, null, emptyList(), nodes = nodes)
    private fun snapshot() = RemoteState(deviceId = "device", session = RemoteSession("session", "Task", "", 1),
        hydrationEnabled = true, messageGroups = projectRemoteTopology(listOf(node), RemoteRuntime("idle")))

    @Test fun preparationPreservesReferenceLinksAndBothMathSettingsWhileStreamingStaysIncremental() = runTest {
        val state = MutableStateFlow(snapshot())
        val hydration = RemoteMessageHydration(state, { _, _ -> error("Unexpected network") }, { throw it })
        val owner = state.value.owner!!
        val text = "# Heading\n\nRead [guide][ref]. Formula: \$x + 1\$.\n\n[ref]: https://example.com"
        hydration.accept(owner, page(listOf(native.copy(text = text))), state.value.messageGroups, live = false)
        val completed = hydration.cachedMessage(owner, state.value.messageGroups.single())!!
        assertEquals(2, completed.preparedMarkdown.size)
        completed.preparedMarkdown.forEach { (content, parsed) ->
            assertEquals(content, parsed.content)
            assertTrue(parsed.linksLookedUp)
            assertEquals("https://example.com", parsed.referenceLinkHandler.find("[ref]"))
        }
        val runtime = RemoteRuntime("active", "turn", activeTurnHasUserMessage = true)
        val updated = node.copy(revision = "b".repeat(64))
        val groups = projectRemoteTopology(listOf(updated), runtime)
        hydration.accept(owner, page(listOf(native.copy(text = "Live")), listOf(updated)).copy(runtime = runtime), groups)
        state.value = state.value.copy(messageGroups = groups, runtime = runtime)
        val streaming = hydration.cachedMessage(owner, groups.single())!!
        assertEquals("Live", streaming.text)
        assertTrue(streaming.preparedMarkdown.isEmpty())
        assertEquals(0L, streaming.preparedMarkdownBytes)
    }

    @Test fun creatingOrUpdatingTopologyDoesNotReadBodiesAndHydrationKeepsAllStubPositions() = runTest {
        val state = MutableStateFlow(snapshot())
        var reads = 0
        val hydration = RemoteMessageHydration(state, { _, _ -> reads++; page(listOf(native)) }, { throw it })
        val before = state.value.messageGroups.map { it.stub }
        assertEquals(0, reads)
        val loaded = hydration.observeMessage(state.value.owner!!, "group").filterNotNull().first()
        assertEquals("body", loaded.text)
        assertEquals(1, reads)
        assertEquals(before, state.value.messageGroups.map { it.stub })
        assertEquals(loaded, hydration.loadMessages(state.value.owner!!, listOf("group")).single())
        assertEquals(1, reads)
    }

    @Test fun pageAdmissionPrimesVisibleBodiesWithoutAdditionalNetworkAndEvictionKeepsPositions() = runTest {
        val state = MutableStateFlow(snapshot())
        var reads = 0
        val hydration = RemoteMessageHydration(state, { _, _ -> reads++; page(listOf(native)) }, { throw it },
            maxRecordBytes = 512)
        val owner = state.value.owner!!
        hydration.accept(owner, page(listOf(native)), state.value.messageGroups)
        val before = state.value.messageGroups
        assertEquals("body", hydration.observeMessage(owner, "group").filterNotNull().first().text)
        assertEquals(0, reads)
        repeat(30) { index ->
            val record = native.copy(id = "other-$index", groupId = "other-$index", text = "x".repeat(100))
            val metadata = node.copy(id = record.id, groupId = record.groupId)
            val groups = projectRemoteTopology(listOf(metadata), null)
            state.value = state.value.copy(messageGroups = state.value.messageGroups + groups)
            hydration.accept(owner, page(listOf(record), listOf(metadata)), groups, live = false)
        }
        assertTrue(hydration.retainedRecordBytes <= 512)
        assertTrue(hydration.retainedPayloadBytes <= 512)
        assertEquals(before, state.value.messageGroups.take(before.size))
        assertEquals("body", hydration.loadMessages(owner, listOf("group")).single().text)
        assertEquals(1, reads)
    }

    @Test fun streamedPageUsesOriginalDeltaBoundariesButReopeningDoesNotAnimateOfflineHistory() = runTest {
        val state = MutableStateFlow(snapshot())
        val hydration = RemoteMessageHydration(state, { _, _ -> error("Unexpected network") }, { throw it })
        val owner = state.value.owner!!
        val runtime = RemoteRuntime("active", "turn", activeTurnHasUserMessage = true)
        suspend fun accept(text: String, revision: Char) {
            val metadata = node.copy(revision = revision.toString().repeat(64))
            val groups = projectRemoteTopology(listOf(metadata), runtime)
            hydration.accept(owner, page(listOf(native.copy(text = text)), listOf(metadata)).copy(runtime = runtime), groups)
            state.value = state.value.copy(messageGroups = groups, runtime = runtime)
        }
        accept("a", 'a')
        assertTrue(hydration.cachedMessage(owner, state.value.messageGroups.single())!!.segments!!.single().streamingTextDeltas.isEmpty())
        accept("ab", 'b')
        assertEquals(1, hydration.cachedMessage(owner, state.value.messageGroups.single())!!.segments!!.single()
            .streamingTextDeltas.single().codePointCount)
        hydration.resetStreaming()
        accept("abcdef", 'c')
        assertTrue(hydration.cachedMessage(owner, state.value.messageGroups.single())!!.segments!!.single().streamingTextDeltas.isEmpty())
    }

    @Test fun changingSelectionDuringPayloadReadCannotPublishOrCacheTheOldBody() = runTest {
        val state = MutableStateFlow(snapshot())
        val gate = CompletableDeferred<RemoteConversationPage>()
        val hydration = RemoteMessageHydration(state, { _, _ -> gate.await() }, { throw it })
        val read = async { hydration.loadMessages(state.value.owner!!, listOf("group")) }
        testScheduler.runCurrent()
        state.value = state.value.copy(session = null, messageGroups = emptyList())
        gate.complete(page(listOf(native)))
        try { read.await(); fail("Old selection must be cancelled") }
        catch (_: kotlinx.coroutines.CancellationException) {}
        assertTrue(state.value.messageGroups.isEmpty())
    }

    @Test fun stalePayloadRevisionIsRehydratedWhileUnchangedVisibleRowsUseOriginalCache() = runTest {
        val state = MutableStateFlow(snapshot())
        var reads = 0
        val hydration = RemoteMessageHydration(state, { _, _ -> reads++; page(listOf(native.copy(text = "body $reads")), state.value.messageGroups.single().nodes) }, { throw it })
        val owner = state.value.owner!!
        assertEquals("body 1", hydration.loadMessages(owner, listOf("group")).single().text)
        assertEquals("body 1", hydration.loadMessages(owner, listOf("group")).single().text)
        state.value = state.value.copy(messageGroups = projectRemoteTopology(
            listOf(node.copy(revision = "b".repeat(64))), RemoteRuntime("idle")))
        assertEquals("body 2", hydration.loadMessages(owner, listOf("group")).single().text)
        assertEquals(2, reads)
    }
}
