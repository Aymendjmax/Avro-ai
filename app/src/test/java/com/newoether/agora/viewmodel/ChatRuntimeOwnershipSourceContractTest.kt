package com.newoether.agora.viewmodel

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatRuntimeOwnershipSourceContractTest {
    @Test
    fun `generation core and RAG indexing are owned by the process-scoped ChatRuntime`() {
        val runtime = sourceFile("app/src/main/java/com/newoether/agora/viewmodel/ChatRuntime.kt")
        val viewModel = sourceFile("app/src/main/java/com/newoether/agora/viewmodel/ChatViewModel.kt")
        val container = sourceFile("app/src/main/java/com/newoether/agora/di/AppContainer.kt")

        // One construction site each, inside the runtime.
        assertEquals(1, Regex("""GenerationManager\(\n""").findAll(runtime).count())
        assertEquals(1, Regex("""RagManager\(\n""").findAll(runtime).count())
        assertFalse(viewModel.contains("GenerationManager(\n"))
        assertFalse(viewModel.contains("RagManager(\n"))
        assertTrue(viewModel.contains("val ragManager: RagManager = chatRuntime.ragManager"))
        assertTrue(
            viewModel.contains(
                "private val generationManager: GenerationManager get() = chatRuntime.generationManager",
            ),
        )

        // The runtime lives on the app scope, not on any ViewModel scope.
        assertFalse(runtime.contains("viewModelScope"))
        val construction = container
            .substringAfter("val chatRuntime: ChatRuntime by lazy {")
            .substringBefore("\n    }\n")
        assertTrue(construction.contains("scope = appScope,"))

        // Runtime-wide messages reach the phone UI through the existing snackbar stream.
        assertTrue(viewModel.contains("merge(_snackbarMessage, chatRuntime.snackbarEvents)"))
    }

    @Test
    fun `registry callbacks, queue drain and the Loop bridge are bound by the runtime`() {
        val runtime = sourceFile("app/src/main/java/com/newoether/agora/viewmodel/ChatRuntime.kt")
        val viewModel = sourceFile("app/src/main/java/com/newoether/agora/viewmodel/ChatViewModel.kt")
        assertTrue(runtime.contains("registry.attachUiCallbacks(this)"))
        assertTrue(runtime.contains("messageGeneration.drainQueuedAfterGeneration(settledState)"))
        assertTrue(runtime.contains("ForegroundAutomationBridgeController("))
        assertTrue(runtime.contains("foregroundAutomationBridge.start()"))
        // The phone UI no longer owns them, so closing it cannot stop queue drain or Loop delegation.
        assertFalse(viewModel.contains("attachUiCallbacks"))
        assertFalse(viewModel.contains("detachUiCallbacks"))
        assertFalse(viewModel.contains("ForegroundAutomationBridgeController("))
    }

    private fun sourceFile(relativePath: String): String {
        var directory = File(requireNotNull(System.getProperty("user.dir"))).absoluteFile
        repeat(8) {
            val candidate = File(directory, relativePath)
            if (candidate.isFile) return candidate.readText().replace("\r\n", "\n")
            directory = directory.parentFile ?: error("Reached filesystem root")
        }
        error("Unable to locate $relativePath")
    }
}
