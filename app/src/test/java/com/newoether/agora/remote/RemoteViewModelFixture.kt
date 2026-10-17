package com.newoether.agora.remote

import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.flow
import org.junit.After
import org.junit.Assert.*
import org.junit.Before

@OptIn(ExperimentalCoroutinesApi::class)
internal abstract class RemoteViewModelFixture {
    protected val dispatcher = StandardTestDispatcher()
    protected val client = mockk<FiloClient>()
    protected val connections = mockk<RemoteConnectionStore>(relaxed = true)
    protected val session = RemoteSession("session", "Existing", "/workspace", 1)
    @Before fun setup() {
        Dispatchers.setMain(dispatcher)
        coEvery { connections.load() } returns emptyList()
        every { client.address } returns "http://computer/"
        coEvery { client.connect() } returns "Computer"
        coEvery { client.sessions(any()) } returns RemoteSessionPage(listOf(session), null)
        coEvery { client.conversation(any(), any()) } returns bodyPage(emptyList(), null, emptyList())
        coEvery { client.models() } returns listOf(RemoteModel("model", "Model", true))
        every { client.events(any()) } answers {
            val id = firstArg<String>()
            flow {
                val page = client.conversation(id)
                emit(page.copy(runtime = page.runtime ?: RemoteRuntime("idle", model = "model")))
                awaitCancellation()
            }
        }
    }
    @After fun tearDown() { Dispatchers.resetMain() }

    protected fun TestScope.saveAndSelect(vm: RemoteViewModel) {
        vm.saveDevice("http://computer/", "token"); runCurrent()
        vm.selectDevice("http://computer/"); runCurrent()
    }
}
