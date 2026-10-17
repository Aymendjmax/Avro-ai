package com.newoether.agora.remote

import com.newoether.agora.model.MessageStatus
import org.junit.Assert.*
import org.junit.Test

class RemoteGenerationProjectionTest {
    private val answer = RemoteMessage("answer", "turn", null, "assistant", "Answer", 1)

    @Test fun nativeUserAuthoritySurvivesPagingButAnUnacknowledgedUserDoesNotStartTheIndicator() {
        val pending = RemoteRuntime("active", "turn")
        assertFalse(pending.hasVisibleGeneration(listOf(answer)))
        val native = pending.copy(activeTurnHasUserMessage = true)
        assertTrue(native.hasVisibleGeneration(listOf(answer)))
        assertEquals(MessageStatus.SENDING, projectRemoteMessages(listOf(answer), native).single().status)
        assertFalse(native.copy(status = "idle").hasVisibleGeneration(listOf(answer)))
        assertTrue(pending.hasVisibleGeneration(listOf(answer.copy(role = "user"))))
    }

    @Test fun completedImageViewWithoutTextOrDownloadedBytesUsesTheOriginalCompletedPresentation() {
        val image = answer.copy(text = "", activity = RemoteActivity("tool", "view_image",
            arguments = """{"path":"C:/image.png"}""", state = "succeeded", imagePath = "C:/image.png"))
        val segment = projectRemoteMessages(listOf(image)).single().segments!!.single()
        val presentation = com.newoether.agora.ui.chat.message.ToolPresentationResolver.resolve(segment)
        assertEquals(com.newoether.agora.ui.chat.message.ToolKind.IMAGE_VIEW, presentation.kind)
        assertEquals(com.newoether.agora.ui.chat.message.ToolPresentationState.COMPLETED, presentation.state)
        assertFalse(presentation.isActive)
        assertNull(segment.toolResult)
        assertTrue(segment.toolImages.isEmpty())
    }

    @Test fun oldNativeImageViewIsCompleteButItsNameAloneNeverInventsCompletion() {
        fun presentation(state: String? = null, path: String? = "C:/image.png") =
            com.newoether.agora.ui.chat.message.ToolPresentationResolver.resolve(
                projectRemoteMessages(listOf(answer.copy(text = "", activity =
                    RemoteActivity("tool", "view_image", state = state, imagePath = path))))
                    .single().segments!!.single())
        assertEquals(com.newoether.agora.ui.chat.message.ToolPresentationState.COMPLETED, presentation().state)
        assertEquals(com.newoether.agora.ui.chat.message.ToolPresentationState.RUNNING, presentation("running").state)
        assertEquals(com.newoether.agora.ui.chat.message.ToolPresentationState.FAILED, presentation("failed").state)
        assertEquals(com.newoether.agora.ui.chat.message.ToolPresentationState.CALLING, presentation(path = null).state)
    }

    @Test fun pagingPreservesTheAssistantBubbleIdentity() {
        val newer = answer.copy(id = "newer", groupId = "native-group")
        val older = answer.copy(id = "older", groupId = "native-group")
        assertEquals("native-group", projectRemoteMessages(listOf(newer)).single().id)
        assertEquals("native-group", projectRemoteMessages(listOf(older, newer)).single().id)
    }
}
