package com.newoether.agora.remote

import com.newoether.agora.model.MessageStatus
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class RemoteTopologyTest {
    @Test fun olderPagesPreserveCanonicalLazyItemsEvenWhenNativeRepliesCrossEveryPage() {
        fun record(id: String, role: String = "assistant") = RemoteMessage(id, "same-turn", null, role, id, 1)
        var nodes = admitRemotePage(emptyList(), bodyPage(listOf(record("visible")), "older", emptyList()))
        val cache = com.newoether.agora.ui.chat.MessageListTurnCache()
        val before = cache.update(projectRemoteTopology(nodes, null).map { it.stub }).single()
        repeat(200) { index ->
            val page = bodyPage(listOf(record("user-$index", "user"), record("older-$index")), "next", emptyList())
            nodes = admitRemotePage(nodes, page, older = true)
            val turns = cache.update(projectRemoteTopology(nodes, null).map { it.stub })
            assertSame(before, turns.first { it.key == before.key })
            assertEquals(listOf("visible"), turns.last().messages.map { it.id })
            assertNull(com.newoether.agora.ui.chat.messageListTailAnchorKey(turns))
        }
        assertEquals(401, nodes.size)
        assertEquals(nodes.size, nodes.map { it.id }.distinct().size)
    }

    @Test fun overlappingStreamUpdatesCannotReassignSealedPageKeysOrDropItsNativePrefix() {
        fun record(index: Int) = RemoteMessage("a$index", "turn", null, "assistant", "text $index", 1)
        var nodes = admitRemotePage(emptyList(), bodyPage((0..127).map(::record), null, emptyList()))
        val firstPage = projectRemoteTopology(nodes, null).single()
        nodes = admitRemotePage(nodes, bodyPage(listOf(record(127), record(128)), "older", emptyList()))
        val after = projectRemoteTopology(nodes, null)
        assertEquals(firstPage, after.first())
        assertNotEquals(after.first().stub.displayPageId, after.last().stub.displayPageId)
        assertEquals((0..128).map { "a$it" }, nodes.map { it.id })
        val snapshot = nodes
        nodes = admitRemotePage(nodes, bodyPage(listOf(record(127), record(128)), "older", emptyList()))
        assertEquals(snapshot, nodes)
    }

    @Test fun continuousThoughtAndToolGroupsRespectDisplayPageLimitAndPreserveSealedFragments() {
        fun tool(index: Int) = RemoteMessage("tool-$index", "turn", null, "assistant", "", 1,
            activity = RemoteActivity(if (index % 2 == 0) "thought" else "tool", state = "succeeded"))
        val packet = bodyPage((0..349).map(::tool), "older", emptyList())
        val nodes = admitRemotePage(emptyList(), packet)
        val groups = projectRemoteTopology(nodes, null)
        assertEquals(listOf(128, 128, 94), groups.map { it.nodes.size })
        val cache = com.newoether.agora.ui.chat.MessageListTurnCache()
        val before = cache.update(groups.map { it.stub })
        val answer = RemoteMessage("earlier-answer", "turn", null, "assistant", "Earlier", 1)
        val prepended = admitRemotePage(nodes, bodyPage(listOf(answer), null, emptyList()), older = true)
        val turns = cache.update(projectRemoteTopology(prepended, null).map { it.stub })
        before.forEach { original -> assertSame(original, turns.first { it.key == original.key }) }
        assertEquals(nodes, prepended.drop(1))
        val extended = admitRemotePage(nodes, bodyPage(listOf(tool(349), tool(350)), "older", emptyList()))
        assertEquals(listOf(128, 128, 95), projectRemoteTopology(extended, null).map { it.nodes.size })
        val complete = admitRemotePage(extended, bodyPage(listOf(tool(350), answer.copy(id = "final")), null, emptyList()))
        assertEquals(listOf(128, 128, 96), projectRemoteTopology(complete, null).map { it.nodes.size })
    }

    private val revision = "a".repeat(64)
    private fun node(id: String, role: String = "assistant", turn: String = "turn") =
        RemoteMessageNode(id, turn, null, role, 1, revision, 100, groupId = if (role == "assistant") "group-$turn" else null)

    @Test fun largeTopologyHasStableBubbleKeysAndNoBodiesBeforeOrAfterPayloadRevisionChanges() {
        val nodes = (0..1000).flatMap { index ->
            listOf(node("u$index", "user", "t$index"), node("a$index", turn = "t$index"))
        }
        val before = projectRemoteTopology(nodes, RemoteRuntime("idle"))
        val changed = nodes.dropLast(1) + nodes.last().copy(revision = "b".repeat(64), textLength = 10000)
        val after = projectRemoteTopology(changed, RemoteRuntime("idle"))
        assertEquals(before.map { it.stub }, after.map { it.stub })
        assertEquals(2002, after.size)
        assertTrue(after.all { it.stub.text.isEmpty() && it.stub.segments == null })
        assertNotEquals(before.last().revision, after.last().revision)
    }

    @Test fun whitespaceOnlyNativeBodiesDoNotCreateEmptyStructuralCards() {
        assertTrue(projectRemoteTopology(listOf(node("blank").copy(hasContent = false)), null).isEmpty())
        assertEquals(1, projectRemoteTopology(listOf(node("u", "user").copy(hasContent = true)), null).size)
    }

    @Test fun transportPartsDoNotBecomeExtraBubblesOrAlterNativeToolCount() {
        val user = node("u", "user")
        val first = node("answer").copy(nativeId = "answer", textContinues = true)
        val part = first.copy(id = "filo-part:answer:16384", textOffset = 16384, textContinues = false)
        val tool = node("tool").copy(textLength = 0, activity = RemoteNodeActivity("tool", "succeeded"))
        val groups = projectRemoteTopology(listOf(user, first, part, tool), RemoteRuntime("idle"))
        assertEquals(listOf("u", "group-turn"), groups.map { it.stub.id })
        assertEquals(1, groups.last().nodes.count { it.activity?.type == "tool" })
        assertEquals(3, groups.last().requests.size)
    }

    @Test fun onlyTheNativeAcknowledgedActiveTailReceivesGenerationStatus() {
        val active = RemoteRuntime("active", "turn")
        assertTrue(projectRemoteTopology(emptyList(), active).isEmpty())
        val nodes = listOf(node("old", turn = "old"), node("u", "user"), node("thought").copy(activity = RemoteNodeActivity("thought")))
        val result = projectRemoteTopology(nodes, active)
        assertEquals(MessageStatus.SUCCESS, result.first().stub.status)
        assertEquals(MessageStatus.THINKING, result.last().stub.status)
        val answer = node("answer")
        assertEquals(MessageStatus.SENDING, projectRemoteTopology(nodes + answer, active).last().stub.status)
    }

    @Test fun pageAndStreamIncludeBodiesAndMetadataWithoutPerMessageRequests() = runBlocking {
        val session = "00000000-0000-0000-0000-000000000001"
        val message = RemoteMessage("a", "turn", null, "assistant", "body", 1)
        val page = bodyPage(listOf(message), "older", emptyList(), RemoteRuntime("idle"))
        val paths = mutableListOf<String>()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            assertEquals("Bearer " + "a".repeat(64), exchange.requestHeaders.getFirst("Authorization"))
            paths += exchange.requestURI.toString()
            val json = Json.encodeToString(page)
            val text = if (exchange.requestURI.path.endsWith("/events")) "data: $json\n\n" else json
            val bytes = text.toByteArray()
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
        try {
            val client = applicationFixtureClient("http://127.0.0.1:" + server.address.port + "/", "a".repeat(64))
            assertEquals(page, client.conversation(session))
            assertEquals(1, paths.size)
            assertTrue(paths.single().endsWith("includeActivity=true&includeMetadata=true"))
            assertEquals(page, client.events(session).first())
            assertEquals(2, paths.size)
            assertTrue(paths.last().endsWith("events?view=paged"))
        } finally { server.stop(0) }
    }
}
