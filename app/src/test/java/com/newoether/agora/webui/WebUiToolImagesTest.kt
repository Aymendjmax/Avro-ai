package com.newoether.agora.webui

import com.newoether.agora.model.ChatMessage
import com.newoether.agora.model.MessageSegment
import com.newoether.agora.model.MessageStatus
import com.newoether.agora.model.Participant
import com.newoether.agora.model.ToolImageAttachment
import com.newoether.agora.tool.ToolImageStore
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class WebUiToolImagesTest {
    @get:Rule val temporary = TemporaryFolder()
    private val bytes = byteArrayOf(1, 2, 3, 4)
    private fun image(file: File) = ToolImageAttachment(file.absolutePath, "image/png", bytes.size.toLong(), sha256 = "hash")
    private fun message(image: ToolImageAttachment) = ChatMessage(
        id = "m", text = "", participant = Participant.MODEL, status = MessageStatus.SUCCESS,
        segments = listOf(
            MessageSegment(type = "thought", content = "first"),
            MessageSegment(type = "thought", content = "second"),
            MessageSegment(type = "answer", content = "answer"),
            MessageSegment(type = "tool", toolName = "view_image", toolResult = "{}", toolImages = listOf(image)),
        ),
    )

    @Test fun resolvesOnlyExactOwnerMessageAndDisplayedToolImageIndex() = runTest {
        val root = temporary.newFolder("tool-media")
        val file = File(root, "image.png").apply { writeBytes(bytes) }
        var loads = 0
        val resolver = WebUiToolImages(root) { conversation, id ->
            loads++
            if (conversation == "c" && id == "m") message(image(file)) else null
        }
        val opened = resolver.open("c", "m", 1, 0)!!
        opened.file.inputStream().use { assertArrayEquals(bytes, it.readBytes()) }
        assertEquals("image/png", opened.mimeType)
        assertNull(resolver.open("other", "m", 1, 0))
        assertNull(resolver.open("c", "other", 1, 0))
        assertNull(resolver.open("c", "m", 0, 0))
        assertNull(resolver.open("c", "m", 1, 1))
        assertNull(resolver.open("c", "m", -1, 0))
        assertEquals(5, loads)
        file.delete()
        assertNull(resolver.open("c", "m", 1, 0))
    }

    @Test fun rejectsPrivatePathEscapesUnsupportedTypesAndWrongSizes() = runTest {
        val root = temporary.newFolder("tool-media")
        val inside = File(root, "image.png").apply { writeBytes(bytes) }
        val outside = temporary.newFile("outside.png").apply { writeBytes(bytes) }
        val sibling = temporary.newFolder("tool-media-other")
        val siblingImage = File(sibling, "image.png").apply { writeBytes(bytes) }
        for (attachment in listOf(
            image(outside), image(siblingImage), image(File(root, "../outside.png")),
            image(inside).copy(mimeType = "image/svg+xml"),
            image(inside).copy(sizeBytes = 0), image(inside).copy(sizeBytes = 5),
            image(inside).copy(sizeBytes = ToolImageStore.MAX_IMAGE_BYTES + 1),
        )) {
            assertNull(WebUiToolImages(root) { _, _ -> message(attachment) }.open("c", "m", 1, 0))
        }
        assertNull(WebUiToolImages(root) { _, _ -> message(image(inside)).copy(id = "wrong") }.open("c", "m", 1, 0))
    }

    @Test fun symbolicLinkCannotEscapeThePrivateRoot() = runTest {
        val root = temporary.newFolder("tool-media")
        val outside = temporary.newFile("outside.png").apply { writeBytes(bytes) }
        val link = File(root, "link.png")
        Files.createSymbolicLink(link.toPath(), outside.toPath())
        assertNull(WebUiToolImages(root) { _, _ -> message(image(link)) }.open("c", "m", 1, 0))
    }
}
