package com.newoether.agora.viewmodel

import com.newoether.agora.model.AttachmentItem
import com.newoether.agora.model.AttachmentMeta
import com.newoether.agora.model.MessageSource
import kotlinx.serialization.SerializationException
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class QueuedGuidanceMergeTest {
    @Test
    fun drainMergesFifoTextAndAttachmentOwnershipIntoOneBubble() {
        val firstMeta = Json.encodeToString(
            AttachmentMeta(
                listOf(AttachmentItem(type = "image", fileName = "one.png", imageIndex = 0)),
            )
        )
        val secondMeta = Json.encodeToString(
            AttachmentMeta(
                listOf(AttachmentItem(type = "image", fileName = "two.png", imageIndex = 0)),
            )
        )
        val olderSnapshot = testGenerationAdmissionSnapshot(
            conversationId = "conversation",
            runId = "older-run",
            selectedModelId = "older-model",
        )
        val latestSnapshot = testGenerationAdmissionSnapshot(
            conversationId = "conversation",
            runId = "latest-run",
            selectedModelId = "latest-model",
        )
        val merged = mergeQueuedGuidance(
            listOf(
                queued("one", "first").copy(
                    modelId = "older-model",
                    generationSnapshot = olderSnapshot,
                    preparedImages = listOf("one.png"),
                    preparedAttachmentMetaJson = firstMeta,
                ),
                queued("two", "second").copy(
                    modelId = "latest-model",
                    generationSnapshot = latestSnapshot,
                    preparedImages = listOf("two.png"),
                    preparedAttachmentMetaJson = secondMeta,
                ),
            )
        ).single()

        assertEquals("one", merged.id)
        assertEquals("first\n\nsecond", merged.text)
        assertEquals("latest-model", merged.modelId)
        assertEquals(latestSnapshot, merged.generationSnapshot)
        assertEquals(listOf("one.png", "two.png"), merged.preparedImages)
        val items = Json.decodeFromString<AttachmentMeta>(
            checkNotNull(merged.preparedAttachmentMetaJson),
        ).items
        assertEquals(listOf("one.png", "two.png"), items.map(AttachmentItem::fileName))
        assertEquals(listOf(0, 1), items.map(AttachmentItem::imageIndex))
    }

    @Test
    fun mergeFailureRestoresTheExactOriginalLeaseBatch() {
        val store = GuidanceLeaseStore({ com.newoether.agora.util.AttachmentFiles.deleteBacking(it) }) { "lease" }
        val first = queued("one", "first").copy(preparedAttachmentMetaJson = "{")
        val second = queued("two", "second")
        store.enqueue(first)
        store.enqueue(second)
        val lease = checkNotNull(store.claim())

        assertThrows(SerializationException::class.java) {
            mergeQueuedGuidance(lease.batch)
        }
        assertTrue(store.settle(lease.id, durable = false))
        assertEquals(listOf(first, second), store.queuedSends.value)
    }

    @Test
    fun drainMergesOnlyAdjacentSendsOfTheSameKind() {
        fun answer(id: String, question: String, reply: String?) = queued(id, "ignored").copy(
            source = MessageSource.askUser(listOf(MessageSource.AskUserItem(question, reply))),
        )
        val bubbles = mergeQueuedGuidance(
            listOf(
                queued("t1", "typed one"),
                queued("t2", "typed two"),
                answer("a1", "Port?", "8080"),
                answer("a2", "Restart?", null),
                queued("t3", "typed three"),
            ),
        )

        assertEquals(listOf("t1", "a1", "t3"), bubbles.map(QueuedSend::id))
        assertEquals("typed one\n\ntyped two", bubbles[0].text)
        assertNull(bubbles[0].source)
        assertEquals(
            listOf(
                MessageSource.AskUserItem("Port?", "8080"),
                MessageSource.AskUserItem("Restart?", null),
            ),
            checkNotNull(bubbles[1].source).askUser,
        )
        assertEquals("Port?\n8080\n\nRestart?\n${MessageSource.NO_ANSWER}", bubbles[1].text)
        assertEquals("typed three", bubbles[2].text)
        assertNull(bubbles[2].source)
    }

    @Test
    fun automationRequestKindsMapToTheirSource() {
        assertEquals(MessageSource.TASK, MessageSource.forAutomationRequestKind("task"))
        assertEquals(MessageSource.LOOP, MessageSource.forAutomationRequestKind("loop"))
        assertNull(MessageSource.forAutomationRequestKind("chat"))
        assertNull(MessageSource.forAutomationRequestKind("queued_guidance"))
    }

    private fun queued(id: String, text: String) = QueuedSend(
        id = id,
        text = text,
        modelId = "model",
        attachments = emptyList(),
        runId = "old-run",
    )
}
