package com.newoether.agora.ui.chat.message

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class Phase27UiSourceContractTest {
    @Test
    fun `direct sources own visuals with no clone or coordinate follower`() {
        val list = chatSource("MessageList.kt")
        val assistant = source("AssistantMessageContent.kt") + source("AssistantInlineActivity.kt")
        val retry = source("RetryActivityIndicator.kt")
        val tail = chatSource("StreamingTailIndicator.kt")
        val follower = File(
            mainSourceRoot(),
            "com/newoether/agora/ui/chat/message/InlineActivityDotFollower.kt",
        )

        assertFalse(follower.exists())
        assertFalse(list.contains("LocalInlineActivityDotOverlayState"))
        assertFalse(list.contains("InlineActivityDotFollower("))
        assertTrue(assistant.contains("GenerationActivityDot()"))
        assertTrue(assistant.contains(
            "alpha = if (terminalText == null) activityOpacity else 1f"
        ))
        assertTrue(assistant.contains("clip = false"))
        assertFalse(assistant.contains("InlineActivityDotMarker"))
        assertFalse(assistant.contains("positionInWindow()"))
        assertTrue(retry.contains("GenerationActivityDot("))
        assertTrue(retry.contains("clip = false"))
        val tailIndicator = tail
            .substringAfter("internal fun StreamingTailIndicator(")
            .substringBefore("/** One breathing-scale sample")
        assertTrue(tailIndicator.contains("GenerationActivityDot("))
        assertTrue(tailIndicator.contains("alpha = opacity"))
        assertTrue(tailIndicator.contains("scaleX = appearanceScale"))
        assertTrue(tailIndicator.contains("clip = false"))
        assertFalse(tailIndicator.contains("InlineActivityDotMarker"))
        assertFalse(tailIndicator.contains("AnimatedVisibility("))
    }

    @Test
    fun `terminal background tool cannot keep Thinking header loading`() {
        val timeline = source("MessageItemTimeline.kt") +
            source("TimelineSegmentsContent.kt")
        val presentation = source("ToolPresentation.kt")

        assertTrue(timeline.contains("): Boolean = generationActive && isCurrentCard"))
        assertTrue(timeline.contains("): Boolean = generationActive && isCurrentCard && useLiveStatus"))
        assertTrue(timeline.contains("val cardUsesLiveStatus = compactSegmentUsesLiveStatus("))
        assertTrue(timeline.contains("generationActive = generationActive"))
        // isActive drives the loading indicator and must exclude detached background jobs.
        assertFalse(presentation.contains(
            "state == ToolPresentationState.BACKGROUND_RUNNING"
        ))
        assertTrue(presentation.contains(
            "state == ToolPresentationState.CALLING ||"
        ))
    }

    @Test
    fun `sheet chrome uses twenty five percent neutral surfaces`() {
        val timeline = source("MessageItemTimeline.kt") +
            source("TimelineSegmentsContent.kt")
        val detail = source("SegmentDetailSheet.kt")

        assertTrue(timeline.contains(
            "MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.25f)"
        ))
        assertFalse(timeline.contains(
            "MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.20f)"
        ))
        val backCall = detail
            .substringAfter("CircularBackButton(")
            .substringBefore("Text(")
        assertTrue(backCall.contains(
            "MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.25f)"
        ))
        assertFalse(backCall.contains(
            "MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.20f)"
        ))
    }

    @Test
    fun `Select Text body is fourteen sp while user bubble keeps its token`() {
        val detail = source("SegmentDetailSheet.kt")
        val user = source("UserMessageBubble.kt")

        assertTrue(detail.contains(
            "ChatType.userBody.copy(fontSize = 14.sp)"
        ))
        assertTrue(user.contains("style = ChatType.userBody"))
    }

    private fun source(name: String): String =
        File(mainSourceRoot(), "com/newoether/agora/ui/chat/message/$name").readText()

    private fun chatSource(name: String): String =
        File(mainSourceRoot(), "com/newoether/agora/ui/chat/$name").readText()

    private fun mainSourceRoot(): File = locate("app/src/main/java")

    private fun locate(relative: String): File {
        var directory = File(requireNotNull(System.getProperty("user.dir"))).absoluteFile
        repeat(8) {
            File(directory, relative).takeIf(File::isDirectory)?.let { return it }
            directory = directory.parentFile ?: error("Reached filesystem root")
        }
        error("Unable to locate $relative")
    }
}
