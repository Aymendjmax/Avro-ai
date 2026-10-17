package com.newoether.agora.viewmodel

import com.newoether.agora.api.util.ContextTokenEstimator
import com.newoether.agora.data.local.MessageEntity
import com.newoether.agora.model.AttachmentItem
import com.newoether.agora.model.AttachmentMeta
import com.newoether.agora.model.AttachmentStorage
import com.newoether.agora.model.CitationRecord
import com.newoether.agora.model.MessageSegment
import com.newoether.agora.model.MessageStatus
import com.newoether.agora.model.Participant
import com.newoether.agora.model.toMessageSegment
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProviderMessageProjectorTest {
    @Test
    fun `sandbox attachment projects path mime size and read instruction for every provider`() {
        val item = AttachmentItem(
            type = "file",
            fileName = "archive.apk",
            mimeType = "application/vnd.android.package-archive",
            storage = AttachmentStorage.LOCAL_SANDBOX_RUNTIME,
            sandboxPath = "/home/agora/attachments/id/archive.apk",
            fileSize = 4096L,
        )
        val entity = MessageEntity(
            id = "user",
            conversationId = "conversation",
            text = "inspect this",
            attachmentMeta = Json.encodeToString(AttachmentMeta(listOf(item))),
            status = MessageStatus.SUCCESS,
            participant = Participant.USER,
            timestamp = 1L,
            runId = "run",
        )

        val projected = projectProviderMessages(listOf(entity), false).single()

        assertEquals(
            "inspect this" + sandboxAttachmentInstruction(item),
            projected.text,
        )
        assertTrue(projected.text.contains(item.sandboxPath!!))
        assertTrue(projected.text.contains("4096 bytes"))
        assertTrue(projected.text.contains("file_read"))
    }

    @Test
    fun `stored transcription removes only its source image and keeps fresh tool images`() {
        val source = MessageEntity(
            id = "user",
            conversationId = "conversation",
            text = "describe",
            images = listOf("/private/source.png"),
            attachmentMeta = Json.encodeToString(
                AttachmentMeta(
                    items = listOf(
                        AttachmentItem(
                            type = "image",
                            imageIndex = 0,
                            transcription = "stored visual description",
                        ),
                    ),
                ),
            ),
            status = MessageStatus.SUCCESS,
            participant = Participant.USER,
            timestamp = 1L,
            runId = "run",
        )
        val toolResult = MessageEntity(
            id = "result_view_image",
            conversationId = "conversation",
            text = """{"ok":true}""",
            images = listOf("/private/tool-result.png"),
            status = MessageStatus.SUCCESS,
            participant = Participant.USER,
            timestamp = 2L,
            runId = "run",
        )

        val projected = projectProviderMessages(
            entities = listOf(source, toolResult),
            includeStoredTranscriptions = true,
        )

        assertEquals(emptyList<String>(), projected.first().images)
        assertEquals(listOf("/private/tool-result.png"), projected.last().images)
        assertTrue(projected.first().text.contains("stored visual description"))
    }

    @Test
    fun `provider history excludes citation metadata without changing the answer`() {
        val retained = MessageSegment(type = "answer", content = "answer")
        val citation = CitationRecord(
            sourceId = "citation_source",
            provider = "openai",
            kind = "url",
            title = "Private source",
            url = "https://example.com/source",
            providerSourceId = "turn0search0",
        ).toMessageSegment()
        val entity = MessageEntity(
            id = "model",
            conversationId = "conversation",
            text = "answer",
            status = MessageStatus.SUCCESS,
            participant = Participant.MODEL,
            timestamp = 1L,
            toolCallJson = Json.encodeToString(listOf(retained, citation)),
            runId = "run",
        )

        val projected = projectProviderMessages(
            entities = listOf(entity),
            includeStoredTranscriptions = false,
        ).single()

        assertEquals("answer", projected.text)
        assertEquals(listOf(retained), projected.segments)
        assertNull(projected.toolCall)
    }
    @Test
    fun `provider history receives answer recovered from malformed thought segment`() {
        val segments = listOf(
            MessageSegment(type = "thought", content = "reason</thinking>answer"),
            MessageSegment(type = "error", content = "truncated"),
        )
        val entity = MessageEntity(
            id = "model",
            conversationId = "conversation",
            text = "",
            thoughts = "reason</thinking>answer",
            status = MessageStatus.ERROR,
            participant = Participant.MODEL,
            timestamp = 1L,
            toolCallJson = Json.encodeToString(segments),
            runId = "run",
        )

        val projected = projectProviderMessages(
            entities = listOf(entity),
            includeStoredTranscriptions = false,
        ).single()

        assertEquals("answer", projected.text)
        assertEquals("reason", projected.thoughts)
        assertEquals(
            listOf("thought", "answer", "error"),
            projected.segments?.map { it.type },
        )
    }

    @Test
    fun `file text and every media page remain visible to token accounting`() {
        val entity = MessageEntity(
            id = "user",
            conversationId = "conversation",
            text = "inspect attachments",
            images = listOf("page-1.jpg", "page-2.jpg", "video-frame.jpg"),
            attachmentMeta = Json.encodeToString(
                AttachmentMeta(
                    items = listOf(
                        AttachmentItem(
                            type = "file",
                            fileName = "notes.txt",
                            textContent = "important file content ".repeat(20),
                        ),
                        AttachmentItem(
                            type = "pdf",
                            fileName = "document.pdf",
                            imageIndex = 0,
                            pageCount = 2,
                        ),
                        AttachmentItem(
                            type = "video",
                            fileName = "clip.mp4",
                            imageIndex = 2,
                            pageCount = 1,
                        ),
                    ),
                ),
            ),
            status = MessageStatus.SUCCESS,
            participant = Participant.USER,
            timestamp = 1L,
            runId = "run",
        )

        val projected = projectProviderMessages(
            entities = listOf(entity),
            includeStoredTranscriptions = false,
        ).single()
        val withoutAttachments = projected.copy(
            text = "inspect attachments",
            images = emptyList(),
        )

        assertTrue(projected.text.contains("important file content"))
        assertEquals(3, projected.images.size)
        assertTrue(
            ContextTokenEstimator.estimate(listOf(projected)) >
                ContextTokenEstimator.estimate(listOf(withoutAttachments)),
        )
    }
}
