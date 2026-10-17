package com.newoether.agora.ui.chat

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Opening Task history hides the Tasks overlay and selects a conversation at the same time.
 * The switch may settle before the overlay exit finishes, while Chat haptics are still disabled.
 * The completion haptic must then be deferred until Chat is presented, not dropped.
 */
class SwitchCompletionHapticSourceContractTest {
    @Test
    fun coveredSwitchCompletionDefersHapticUntilChatIsPresented() {
        val coordinator = source("ui/chat/ChatScrollCoordinator.kt")
        val completion = coordinator.substringAfter("viewModel.completeSwitchingScroll(request.id)")
            .substringBefore("viewModel.failSwitchingScroll(request.id, \"layout failed to stabilize\")")
        assertTrue(completion.contains("if (latestChatPresented) {"))
        assertTrue(completion.contains("haptics.confirm()"))
        assertTrue(completion.contains("pendingSwitchHapticRequestId = request.id"))

        val deferred = coordinator.substringAfter(
            "LaunchedEffect(pendingSwitchHapticRequestId, chatPresented, switchingScrollRequest?.id) {",
        ).substringBefore("LaunchedEffect(currentConversationId) {")
        // A newer switch drops the stale pending haptic.
        assertTrue(deferred.contains("activeRequestId != null && activeRequestId != pendingId"))
        // The pending haptic is cleared before it fires, so it fires at most once.
        assertTrue(
            deferred.indexOf("pendingSwitchHapticRequestId = null", deferred.indexOf("else if (chatPresented)")) <
                deferred.lastIndexOf("haptics.confirm()"),
        )

        val chatApp = source("ui/chat/ChatApp.kt")
        assertTrue(chatApp.contains("chatPresented = topLevelPresentation == TopLevelPresentation.CHAT,"))
    }

    private fun source(relativePath: String): String =
        File(mainSourceRoot(), "com/newoether/agora/$relativePath").readText()

    private fun mainSourceRoot(): File {
        var directory: File? = File(requireNotNull(System.getProperty("user.dir"))).absoluteFile
        while (directory != null) {
            val candidate = File(directory, "app/src/main/java")
            if (candidate.isDirectory) return candidate
            val local = File(directory, "src/main/java")
            if (local.isDirectory) return local
            directory = directory.parentFile
        }
        error("main source root not found")
    }
}
