package com.newoether.agora.remote

import io.mockk.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class RemoteGroupPaginationTest {
    private val dispatcher = StandardTestDispatcher()
    private val client = mockk<FiloClient>()
    private val connections = mockk<RemoteConnectionStore>(relaxed = true)
    private val session = RemoteSession("history", "History", "/workspace", 1)
    private fun tool(index: Int) = RemoteMessage("tool-$index", "turn", null, "assistant", "", 1,
        activity = RemoteActivity("tool", "shell", result = "done", state = "succeeded"))
    private fun packet(range: IntRange, next: String?, continuation: String?, bookmark: String) =
        bodyPage(range.map(::tool), next, emptyList()).copy(continuationCursor = continuation, pageCursor = bookmark)

    @Before fun setup() {
        Dispatchers.setMain(dispatcher)
        coEvery { connections.load() } returns emptyList()
        every { client.address } returns "http://computer/"
        coEvery { client.connect() } returns "Computer"
        coEvery { client.sessions(any()) } returns RemoteSessionPage(listOf(session), null)
        coEvery { client.models() } returns emptyList()
        every { client.events(any()) } answers {
            val id = firstArg<String>()
            flow { emit(client.conversation(id)); awaitCancellation() }
        }
    }
    @After fun tearDown() { Dispatchers.resetMain() }

    private fun TestScope.open(): RemoteViewModel {
        val vm = RemoteViewModel(connections, projectionDispatcher = dispatcher) { _, _ -> client }
        runCurrent()
        vm.setVisible(true)
        vm.saveDevice("http://computer/", "token"); runCurrent()
        vm.selectDevice("http://computer/"); runCurrent()
        vm.selectSession(session); runCurrent()
        return vm
    }

    @Test fun initialPacketDisplaysImmediatelyAndIgnoresLegacyGroupContinuationUntilPaging() = runTest(dispatcher) {
        val prefix = CompletableDeferred<RemoteConversationPage>()
        coEvery { client.conversation("history", null) } returns packet(128..255, "prefix", "prefix", "tail-bookmark")
        coEvery { client.conversation("history", "prefix") } coAnswers { prefix.await() }
        val vm = open()
        assertFalse(vm.state.value.loading)
        assertEquals((128..255).map { "tool-$it" }, vm.state.value.nodes.map { it.id })
        val tail = vm.state.value.messageGroups.single()
        assertEquals("prefix", vm.state.value.historyCursor)
        coVerify(exactly = 0) { client.conversation("history", "prefix") }
        vm.loadMore(); runCurrent()
        assertTrue(vm.state.value.loadingMore)
        assertEquals(listOf(tail), vm.state.value.messageGroups)
        prefix.complete(packet(0..127, "earlier-answer", null, "prefix-bookmark")); runCurrent()
        assertFalse(vm.state.value.loadingMore)
        assertEquals((0..255).map { "tool-$it" }, vm.state.value.nodes.map { it.id })
        assertEquals("earlier-answer", vm.state.value.historyCursor)
        assertEquals(tail, vm.state.value.messageGroups.last())
        assertEquals(128, vm.cachedMessage(vm.state.value.owner!!, tail.stub.id)!!.segments!!.size)
        assertTrue(vm.state.value.nodes.take(128).all { it.pageCursor == "prefix-bookmark" })
        assertTrue(vm.state.value.nodes.drop(128).all { it.pageCursor == "tail-bookmark" })
        coVerify(exactly = 0) { client.conversation("history", "earlier-answer") }
        vm.setVisible(false)
    }

    @Test fun shortToolOnlyLatestPageDoesNotFetchAnyOlderPageBeforeUpwardPaging() = runTest(dispatcher) {
        coEvery { client.conversation("history", null) } returns packet(80..95, "older", null, "tail")
        coEvery { client.conversation("history", "older") } returns packet(64..79, "earlier", null, "older")
        val vm = open()
        assertFalse(vm.state.value.loading)
        assertEquals((80..95).map { "tool-$it" }, vm.state.value.nodes.map { it.id })
        assertEquals("older", vm.state.value.historyCursor)
        coVerify(exactly = 0) { client.conversation("history", "older") }
        vm.loadMore(); runCurrent()
        assertEquals((64..95).map { "tool-$it" }, vm.state.value.nodes.map { it.id })
        coVerify(exactly = 1) { client.conversation("history", "older") }
        coVerify(exactly = 0) { client.conversation("history", "earlier") }
        vm.setVisible(false)
    }

    @Test fun olderPacketsPublishSeparatelyAndPreserveTheExistingAnswerDuringADeferredLoad() = runTest(dispatcher) {
        val answer = RemoteMessage("answer", "turn", null, "assistant", "Answer", 2)
        coEvery { client.conversation("history", null) } returns bodyPage(listOf(answer), "older", emptyList())
        coEvery { client.conversation("history", "older") } returns packet(128..255, "prefix", "prefix", "tail-bookmark")
        val prefix = CompletableDeferred<RemoteConversationPage>()
        coEvery { client.conversation("history", "prefix") } coAnswers { prefix.await() }
        val vm = open()
        vm.state.first { it.messageGroups.isNotEmpty() }
        val existing = vm.state.value.messageGroups.single()
        vm.loadMore(); runCurrent()
        assertFalse(vm.state.value.loadingMore)
        assertEquals(existing, vm.state.value.messageGroups.last())
        assertEquals(128, vm.state.value.messageGroups.first().nodes.size)
        assertEquals("prefix", vm.state.value.historyCursor)
        coVerify(exactly = 0) { client.conversation("history", "prefix") }
        val published = vm.state.value.messageGroups
        vm.loadMore(); runCurrent()
        assertTrue(vm.state.value.loadingMore)
        assertEquals(published, vm.state.value.messageGroups)
        prefix.complete(packet(0..127, null, null, "prefix-bookmark")); runCurrent()
        assertFalse(vm.state.value.loadingMore)
        assertNull(vm.state.value.historyCursor)
        assertEquals(existing, vm.state.value.messageGroups.last())
        assertEquals(128, vm.state.value.messageGroups.first().nodes.size)
        assertEquals(3, vm.state.value.messageGroups.size)
        vm.setVisible(false)
    }
}
