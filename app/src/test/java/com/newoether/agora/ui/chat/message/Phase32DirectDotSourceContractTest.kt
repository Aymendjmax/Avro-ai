package com.newoether.agora.ui.chat.message

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class Phase32DirectDotSourceContractTest {
    @Test
    fun `generation dots render directly without an overlay follower`() {
        val list = chatSource("MessageList.kt")
        val assistant = messageSource("AssistantMessageContent.kt")
        val retry = messageSource("RetryActivityIndicator.kt")
        val tail = chatSource("StreamingTailIndicator.kt")
        val follower = messageSourceOrEmpty("InlineActivityDotFollower.kt")

        assertTrue(follower.isEmpty())
        listOf(
            "InlineActivityDotFollower",
            "LocalInlineActivityDotOverlayState",
            "rememberInlineActivityDotOverlayState",
            "dotOverlayState",
        ).forEach { symbol -> assertFalse("MessageList retains $symbol", list.contains(symbol)) }
        assertFalse(list.contains("StreamingTailIndicator("))

        val activity = messageSource("AssistantInlineActivity.kt")
        val assistantActivity = activity
            .substringAfter("internal fun AssistantInlineActivity(")
            .substringBefore("/**")
        assertTrue(assistant.contains("import com.newoether.agora.ui.chat.GenerationActivityDot"))
        assertTrue(assistantActivity.contains("GenerationActivityDot()"))
        assertTrue(assistantActivity.contains("visibilityTransition.targetState ||"))
        assertTrue(assistantActivity.contains(
            "visibilityTransition.targetState || retainExitLayout"
        ))
        assertTrue(assistant.contains("retainExitLayout = inlineActivityPresentation.retainLayout"))
        assertTrue(assistantActivity.contains(
            "alpha = if (terminalText == null) activityOpacity else 1f"
        ))
        assertTrue(activity.contains(".heightIn(min = AssistantInlineActivityHeight)"))
        assertTrue(activity.contains("import androidx.compose.ui.graphics.CompositingStrategy"))
        assertTrue(assistantActivity.contains(
            "compositingStrategy = CompositingStrategy.ModulateAlpha"
        ))
        assertFalse(assistantActivity.contains("CompositingStrategy.Offscreen"))
        assertTrue(assistantActivity.contains("clip = false"))
        assertFalse((assistant + activity).contains("InlineActivityDotMarker"))
        assertFalse((assistant + activity).contains("InlineActivityDotSource"))

        assertTrue(retry.contains("GenerationActivityDot("))
        assertTrue(retry.contains("translationX = dotTranslationPx"))
        assertTrue(retry.contains("translationY = dotVerticalOffsetPx"))
        assertTrue(retry.contains("clip = false"))
        assertFalse(retry.contains("InlineActivityDotMarker"))
        assertFalse(retry.contains("InlineActivityDotSource"))

        val tailIndicator = tail
            .substringAfter("internal fun StreamingTailIndicator(")
            .substringBefore("/** One breathing-scale sample")
        assertTrue(tailIndicator.contains("updateTransition("))
        assertTrue(tailIndicator.contains(
            "visibilityTransition.currentState || visibilityTransition.targetState"
        ))
        assertTrue(tailIndicator.contains("GenerationActivityDot("))
        assertTrue(tailIndicator.contains("alpha = opacity"))
        assertTrue(tail.contains("import androidx.compose.ui.graphics.CompositingStrategy"))
        assertTrue(tailIndicator.contains(
            "compositingStrategy = CompositingStrategy.ModulateAlpha"
        ))
        assertFalse(tailIndicator.contains("CompositingStrategy.Offscreen"))
        assertTrue(tailIndicator.contains("scaleX = appearanceScale"))
        assertTrue(tailIndicator.contains("scaleY = appearanceScale"))
        assertTrue(tailIndicator.contains("clip = false"))
        assertTrue(tailIndicator.contains("durationMillis = if (targetState) 400 else 320"))
        assertFalse(tailIndicator.contains("AnimatedVisibility("))
        assertFalse(tailIndicator.contains("InlineActivityDotMarker"))
        assertFalse(tailIndicator.contains("InlineActivityDotSource"))
        assertFalse(tailIndicator.contains("targetInContent"))

        val directDot = tail.substringAfter("internal fun GenerationActivityDot(")
        assertTrue(directDot.contains(".size(GenerationActivityDotSize)"))
        assertTrue(directDot.contains("clip = false"))
        assertFalse(directDot.contains("animateContentSize"))
    }

    private fun messageSource(name: String): String =
        File(mainSourceRoot(), "com/newoether/agora/ui/chat/message/$name").readText()

    private fun messageSourceOrEmpty(name: String): String =
        File(mainSourceRoot(), "com/newoether/agora/ui/chat/message/$name")
            .takeIf(File::isFile)
            ?.readText()
            .orEmpty()

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
