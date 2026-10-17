package com.newoether.agora.ui.chat

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class CompactScrollLifecycleSourceContractTest {
    @Test
    fun compactScrollPreservesAttachmentBeforeWaitingForThePlaceholder() {
        val root = locateMainSourceRoot()
        val generationController = File(
            root,
            "com/newoether/agora/viewmodel/MessageGenerationController.kt",
        ).readText()
        val scrollCoordinator = File(
            root,
            "com/newoether/agora/ui/chat/ChatScrollCoordinator.kt",
        ).readText()

        assertTrue(
            "Compact startup must retain attached-only scroll semantics",
            generationController.replace("\r\n", "\n").contains(
                "onCompactStarted = { conversationId, messageId ->\n" +
                    "            requestScroll(conversationId, messageId, attachedOnly = true, origin = null)",
            ),
        )

        val effectStart = scrollCoordinator.indexOf(
            "LaunchedEffect(animatedScrollRequest?.id, currentConversationId)",
        )
        val bottomBranch = scrollCoordinator.indexOf(
            "AnimatedScrollDestination.ABSOLUTE_BOTTOM ->",
            effectStart,
        )
        val attachmentReceipt = scrollCoordinator.indexOf(
            "val attachedAtRequest",
            bottomBranch,
        )
        val targetWait = scrollCoordinator.indexOf(
            "awaitScrollTargetCommitted(messages, request.targetMessageId)",
            bottomBranch,
        )
        val attachmentDecision = scrollCoordinator.indexOf(
            "shouldHonorAttachedBottomRequest(",
            targetWait,
        )

        assertTrue("animated-scroll effect must exist", effectStart >= 0)
        assertTrue("absolute-bottom branch must exist", bottomBranch > effectStart)
        assertTrue(
            "attachment eligibility must be captured before target commit can reflow the list",
            attachmentReceipt > bottomBranch && attachmentReceipt < targetWait,
        )
        assertTrue(
            "the captured attachment must include streaming-tail ownership",
            scrollCoordinator.substring(attachmentReceipt, targetWait)
                .contains("streamingTailController.isAttached"),
        )
        assertTrue(
            "the post-commit decision must consume the captured attachment",
            attachmentDecision > targetWait,
        )
    }

    private fun locateMainSourceRoot(): File {
        var directory = File(requireNotNull(System.getProperty("user.dir"))).absoluteFile
        repeat(8) {
            listOf(
                File(directory, "app/src/main/java"),
                File(directory, "src/main/java"),
            ).firstOrNull(File::isDirectory)?.let { return it }
            directory = directory.parentFile ?: error("Reached filesystem root")
        }
        error("Unable to locate the main Java source directory")
    }
}
