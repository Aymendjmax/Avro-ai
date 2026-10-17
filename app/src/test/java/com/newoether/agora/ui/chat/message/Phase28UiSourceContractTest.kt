package com.newoether.agora.ui.chat.message

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class Phase28UiSourceContractTest {
    @Test
    fun `direct dots require no LazyColumn or window coordinate owner`() {
        val list = source("com/newoether/agora/ui/chat/MessageList.kt")
        val assistant = messageSource("AssistantMessageContent.kt") + messageSource("AssistantInlineActivity.kt")
        val retry = messageSource("RetryActivityIndicator.kt")
        val follower = File(
            mainSourceRoot(),
            "com/newoether/agora/ui/chat/message/InlineActivityDotFollower.kt",
        )

        assertFalse(follower.exists())
        assertFalse(list.contains("dotOverlayState"))
        assertFalse(list.contains("LocalInlineActivityDotOverlayState"))
        assertFalse(list.contains("InlineActivityDotFollower("))
        assertFalse(assistant.contains("localPositionOf(markerCoordinates"))
        assertFalse(assistant.contains("positionInWindow"))
        assertTrue(assistant.contains("GenerationActivityDot()"))
        assertTrue(assistant.contains("clip = false"))
        assertTrue(retry.contains("GenerationActivityDot("))
        assertTrue(retry.contains("clip = false"))
    }

    @Test
    fun `user bubble and Select Text share exact 1_1x user-body line height`() {
        val type = source("com/newoether/agora/ui/theme/Type.kt")
        val userBubble = messageSource("UserMessageBubble.kt")
        val detail = messageSource("SegmentDetailSheet.kt")

        assertTrue(type.contains(
            "fontSize = 15.sp, lineHeight = 24.2.sp"
        ))
        assertTrue(userBubble.contains("style = ChatType.userBody"))
        assertTrue(detail.contains(
            "ChatType.userBody.copy(fontSize = 14.sp)"
        ))
        assertFalse(userBubble.contains("lineHeight ="))
        assertFalse(detail.contains(
            "ChatType.userBody.copy(fontSize = 14.sp, lineHeight ="
        ))
    }

    private fun messageSource(name: String): String =
        source("com/newoether/agora/ui/chat/message/$name")

    private fun source(relative: String): String =
        File(mainSourceRoot(), relative).readText()

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
