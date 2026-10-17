package com.newoether.agora.viewmodel

import com.newoether.agora.data.repository.ConversationRepository
import com.newoether.agora.model.AttachmentImportState
import com.newoether.agora.model.SelectedAttachment
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class ConversationComposerConcurrencyTest {
    @Test
    fun `processing obeys global and per owner limits`() = runTest {
        val release = CompletableDeferred<Unit>()
        val globalActive = AtomicInteger()
        val maxGlobal = AtomicInteger()
        val ownerActive = ConcurrentHashMap<String, AtomicInteger>()
        val maxOwner = ConcurrentHashMap<String, AtomicInteger>()
        val processor = mockk<AttachmentImportProcessor>()
        coEvery { processor.process(any(), any(), any()) } coAnswers {
            val attachment = firstArg<SelectedAttachment>()
            val ownerId = attachment.localId.substringBefore('-')
            val global = globalActive.incrementAndGet()
            val owner = ownerActive.computeIfAbsent(ownerId) { AtomicInteger() }.incrementAndGet()
            maxGlobal.updateMax(global)
            maxOwner.computeIfAbsent(ownerId) { AtomicInteger() }.updateMax(owner)
            try {
                release.await()
                AttachmentImportProcessor.ProcessResult.Ready(attachment.ready())
            } finally {
                globalActive.decrementAndGet()
                ownerActive.getValue(ownerId).decrementAndGet()
            }
        }
        val fixture = fixture(
            processor = processor,
            initial = mapOf(
                OWNER_A to draft(processing("a-1"), processing("a-2")),
                OWNER_B to draft(processing("b-1"), processing("b-2")),
                OWNER_C to draft(processing("c-1")),
            ),
        )

        fixture.controller.load(OWNER_A)
        fixture.controller.load(OWNER_B)
        fixture.controller.load(OWNER_C)
        runCurrent()

        assertEquals(2, globalActive.get())
        assertEquals(2, maxGlobal.get())
        assertTrue(maxOwner.values.all { it.get() == 1 })

        release.complete(Unit)
        fixture.controller.awaitProcessing(OWNER_A)
        fixture.controller.awaitProcessing(OWNER_B)
        fixture.controller.awaitProcessing(OWNER_C)

        assertEquals(2, maxGlobal.get())
        assertTrue(maxOwner.values.all { it.get() == 1 })
        assertEquals(5, fixture.persistence.readyCount())
        fixture.controller.release(OWNER_A)
        fixture.controller.release(OWNER_B)
        fixture.controller.release(OWNER_C)
    }

    @Test
    fun `selected owner wins the next permit without starving older work`() = runTest {
        val ids = listOf("a", "b-1", "b-2", "c", "d")
        val gates = ids.associateWith { CompletableDeferred<Unit>() }
        val starts = ids.associateWith { CompletableDeferred<Unit>() }
        val processor = mockk<AttachmentImportProcessor>()
        coEvery { processor.process(any(), any(), any()) } coAnswers {
            val attachment = firstArg<SelectedAttachment>()
            starts.getValue(attachment.localId).complete(Unit)
            gates.getValue(attachment.localId).await()
            AttachmentImportProcessor.ProcessResult.Ready(attachment.ready())
        }
        val fixture = fixture(
            processor = processor,
            initial = mapOf(
                OWNER_A to draft(processing("a")),
                OWNER_B to draft(processing("b-1"), processing("b-2")),
                OWNER_C to draft(processing("c")),
                OWNER_D to draft(processing("d")),
            ),
        )

        fixture.controller.load(OWNER_A)
        starts.getValue("a").await()
        fixture.controller.load(OWNER_C)
        starts.getValue("c").await()
        fixture.controller.load(OWNER_D)
        fixture.controller.loadSelected(OWNER_B)
        runCurrent()

        gates.getValue("a").complete(Unit)
        starts.getValue("b-1").await()
        assertFalse(starts.getValue("d").isCompleted)

        gates.getValue("b-1").complete(Unit)
        starts.getValue("d").await()
        assertFalse(starts.getValue("b-2").isCompleted)

        gates.getValue("c").complete(Unit)
        starts.getValue("b-2").await()
        gates.getValue("b-2").complete(Unit)
        gates.getValue("d").complete(Unit)
        fixture.controller.awaitProcessing(OWNER_A)
        fixture.controller.awaitProcessing(OWNER_B)
        fixture.controller.awaitProcessing(OWNER_C)
        fixture.controller.awaitProcessing(OWNER_D)

        assertTrue(starts.values.all { it.isCompleted })
        fixture.controller.release(OWNER_A)
        fixture.controller.releaseSelected(OWNER_B)
        fixture.controller.release(OWNER_C)
        fixture.controller.release(OWNER_D)
    }

    @Test
    fun `stable attachment replacement runs only the latest queued generation`() = runTest {
        val blockerGate = CompletableDeferred<Unit>()
        val blockerStarted = CompletableDeferred<Unit>()
        val processedPdfPages = mutableListOf<Set<Int>?>()
        val processor = mockk<AttachmentImportProcessor>()
        coEvery { processor.process(any(), any(), any()) } coAnswers {
            val attachment = firstArg<SelectedAttachment>()
            if (attachment.localId == "blocker") {
                blockerStarted.complete(Unit)
                blockerGate.await()
            } else {
                synchronized(processedPdfPages) { processedPdfPages += attachment.selectedPages }
            }
            AttachmentImportProcessor.ProcessResult.Ready(attachment.ready())
        }
        val pdf = processing("pdf").copy(
            type = "pdf",
            fileName = "document.pdf",
            selectedPages = setOf(0),
            preRenderedPaths = listOf("/preview/page-1.jpg"),
        )
        val fixture = fixture(
            processor = processor,
            initial = mapOf(OWNER_A to draft(processing("blocker"), pdf)),
        )

        fixture.controller.load(OWNER_A)
        blockerStarted.await()
        assertTrue(fixture.controller.configurePdf(OWNER_A, "pdf", setOf(1)))
        assertTrue(fixture.controller.configurePdf(OWNER_A, "pdf", setOf(2)))

        blockerGate.complete(Unit)
        fixture.controller.awaitProcessing(OWNER_A)

        assertEquals(listOf(setOf(2)), synchronized(processedPdfPages) { processedPdfPages.toList() })
        assertEquals(setOf(2), fixture.persistence.attachment(OWNER_A, "pdf").selectedPages)
        coVerify(exactly = 1) {
            processor.process(match { it.localId == "pdf" }, any(), any())
        }
        fixture.controller.release(OWNER_A)
    }

    @Test
    fun `owner switch does not cancel active work from the prior selection`() = runTest {
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val cancelled = CompletableDeferred<Unit>()
        val processor = mockk<AttachmentImportProcessor>()
        coEvery { processor.process(match { it.localId == "active" }, any(), any()) } coAnswers {
            started.complete(Unit)
            try {
                release.await()
                AttachmentImportProcessor.ProcessResult.Ready(
                    firstArg<SelectedAttachment>().ready(),
                )
            } catch (failure: CancellationException) {
                cancelled.complete(Unit)
                throw failure
            }
        }
        val fixture = fixture(
            processor = processor,
            initial = mapOf(OWNER_A to draft(processing("active"))),
        )

        fixture.controller.loadSelected(OWNER_A)
        started.await()
        val waiting = launch { fixture.controller.awaitProcessing(OWNER_A) }
        runCurrent()

        fixture.controller.loadSelected(OWNER_B)
        fixture.controller.releaseSelected(OWNER_A)
        runCurrent()
        assertFalse(cancelled.isCompleted)

        release.complete(Unit)
        waiting.join()
        assertEquals(AttachmentImportState.READY, fixture.persistence.attachment(OWNER_A, "active").importState)
        assertFalse(cancelled.isCompleted)
        fixture.controller.releaseSelected(OWNER_B)
    }

    @Test
    fun `released owner keeps one session through queued work then evicts`() = runTest {
        val release = CompletableDeferred<Unit>()
        val firstStarted = CompletableDeferred<Unit>()
        val processCalls = AtomicInteger()
        val allProcessingStarted = CompletableDeferred<Unit>()
        val processor = mockk<AttachmentImportProcessor>()
        coEvery { processor.process(any(), any(), any()) } coAnswers {
            val callCount = processCalls.incrementAndGet()
            firstStarted.complete(Unit)
            if (callCount == 2) allProcessingStarted.complete(Unit)
            release.await()
            AttachmentImportProcessor.ProcessResult.Ready(
                firstArg<SelectedAttachment>().ready(),
            )
        }
        val fixture = fixture(
            processor = processor,
            initial = mapOf(OWNER_A to draft(processing("first"), processing("second"))),
        )

        fixture.controller.loadSelected(OWNER_A)
        firstStarted.await()
        fixture.controller.releaseSelected(OWNER_A)

        fixture.controller.load(OWNER_A)
        assertEquals(1, fixture.persistence.loadCount(OWNER_A))
        fixture.controller.release(OWNER_A)

        release.complete(Unit)
        allProcessingStarted.await()
        withContext(Dispatchers.Default) {
            withTimeout(5_000L) {
                while (runCatching { fixture.controller.state(OWNER_A) }.isSuccess) {
                    delay(10L)
                }
            }
        }
        assertEquals(2, processCalls.get())

        val reloaded = fixture.controller.load(OWNER_A)
        assertEquals(2, fixture.persistence.loadCount(OWNER_A))
        assertTrue(reloaded.attachments.all { it.importState == AttachmentImportState.READY })
        fixture.controller.release(OWNER_A)
    }

    @Test
    fun `frozen owner keeps attachment mutations locked but accepts text edits`() = runTest {
        val processor = mockk<AttachmentImportProcessor>()
        val kept = processing("kept").ready()
        val failed = processing("failed").copy(importState = AttachmentImportState.FAILED)
        val fixture = fixture(
            processor = processor,
            initial = mapOf(OWNER_A to draft(kept, failed)),
        )

        fixture.controller.load(OWNER_A)
        val frozen = fixture.controller.freezeSubmission(
            OWNER_A,
            requestId = 42L,
            text = "frozen text",
            attachmentIds = listOf("kept", "failed"),
        )

        assertEquals("frozen text", frozen?.text)
        // The tap text is request-owned. A lagging draft observer must not overwrite the
        // controller's current text merely to freeze it; the visible field remains authoritative.
        assertEquals("", fixture.persistence.text(OWNER_A))
        assertFalse(fixture.controller.importAttachment(OWNER_A, processing("late")))
        fixture.controller.updateText(OWNER_A, "mutated")
        assertTrue(fixture.controller.persistText(OWNER_A, "mutated"))
        assertFalse(fixture.controller.remove(OWNER_A, "kept"))
        assertFalse(fixture.controller.retry(OWNER_A, "failed"))
        assertFalse(
            fixture.controller.clearAccepted(
                OWNER_A,
                reclaimAttachments = false,
                submissionId = 41L,
            ).succeeded,
        )
        assertEquals("mutated", fixture.controller.state(OWNER_A).value.text)
        assertEquals(
            listOf("kept", "failed"),
            fixture.controller.state(OWNER_A).value.attachments.map { it.localId },
        )

        val acceptedClear = fixture.controller.clearAccepted(
            OWNER_A,
            reclaimAttachments = false,
            submissionId = 42L,
            acceptedRevision = checkNotNull(frozen).revision,
            acceptedText = "frozen text",
            acceptedAttachmentIds = setOf("kept", "failed"),
        )
        assertTrue(acceptedClear.succeeded)
        assertEquals(listOf("kept", "failed"), acceptedClear.attachments.map { it.localId })
        assertTrue(fixture.controller.state(OWNER_A).value.attachments.isEmpty())
        assertEquals("mutated", fixture.controller.state(OWNER_A).value.text)
        assertFalse(fixture.controller.importAttachment(OWNER_A, processing("still-frozen")))
        fixture.controller.updateText(OWNER_A, "still editable")
        assertTrue(fixture.controller.persistText(OWNER_A, "still editable"))
        assertEquals("still editable", fixture.persistence.text(OWNER_A))

        assertTrue(fixture.controller.releaseSubmission(OWNER_A, 42L))
        fixture.controller.updateText(OWNER_A, "editable")
        assertTrue(fixture.controller.persistText(OWNER_A, "editable"))
        assertEquals("editable", fixture.persistence.text(OWNER_A))
        fixture.controller.release(OWNER_A)
    }

    @Test
    fun `freeze waits for an admitted mutation and rejects a stale tap membership`() = runTest {
        val processor = mockk<AttachmentImportProcessor>()
        val fixture = fixture(
            processor = processor,
            initial = mapOf(OWNER_A to draft(processing("kept").ready())),
        )
        fixture.controller.load(OWNER_A)
        val writeStarted = CompletableDeferred<Unit>()
        val releaseWrite = CompletableDeferred<Unit>()
        fixture.persistence.blockNextWrite(writeStarted, releaseWrite)

        val removal = async { fixture.controller.remove(OWNER_A, "kept") }
        writeStarted.await()
        val freezing = async {
            fixture.controller.freezeSubmission(
                OWNER_A,
                requestId = 7L,
                text = "send",
                attachmentIds = listOf("kept"),
            )
        }
        runCurrent()
        assertFalse(freezing.isCompleted)

        releaseWrite.complete(Unit)
        assertTrue(removal.await())
        assertNull(freezing.await())
        assertTrue(fixture.controller.state(OWNER_A).value.attachments.isEmpty())
        assertFalse(fixture.controller.releaseSubmission(OWNER_A, 7L))
        fixture.controller.release(OWNER_A)
    }

    private fun TestScope.fixture(
        processor: AttachmentImportProcessor,
        initial: Map<String, ConversationWorkspaceDraft>,
    ): Fixture {
        val persistence = MemoryDraftPersistence(initial)
        val repository = mockk<ConversationRepository>(relaxed = true)
        val drafts = ComposerDraftController(persistence, repository)
        return Fixture(
            controller = ConversationComposerController(
                scope = backgroundScope,
                drafts = drafts,
                processor = processor,
            ),
            persistence = persistence,
        )
    }

    private fun processing(id: String) = SelectedAttachment(
        localId = id,
        uri = "content://source/$id",
        localPath = "/stage/$id",
        type = "image",
        fileName = "$id.jpg",
        importState = AttachmentImportState.PROCESSING,
    )

    private fun SelectedAttachment.ready() = copy(importState = AttachmentImportState.READY)

    private fun draft(vararg attachments: SelectedAttachment) = ConversationWorkspaceDraft(
        text = "",
        attachmentsJson = Json.encodeToString(attachments.toList()),
    )

    private data class Fixture(
        val controller: ConversationComposerController,
        val persistence: MemoryDraftPersistence,
    )

    private class MemoryDraftPersistence(
        initial: Map<String, ConversationWorkspaceDraft>,
    ) : ComposerDraftPersistence {
        private val drafts = ConcurrentHashMap(initial)
        private val loads = ConcurrentHashMap<String, AtomicInteger>()
        @Volatile private var nextWriteStarted: CompletableDeferred<Unit>? = null
        @Volatile private var nextWriteRelease: CompletableDeferred<Unit>? = null

        override suspend fun loadDraft(ownerId: String): ConversationWorkspaceDraft {
            loads.computeIfAbsent(ownerId) { AtomicInteger() }.incrementAndGet()
            return drafts[ownerId] ?: ConversationWorkspaceDraft("", null)
        }

        override suspend fun updateDraft(
            ownerId: String,
            text: String,
            attachmentsJson: String?,
        ) {
            val started = nextWriteStarted
            val release = nextWriteRelease
            if (started != null && release != null) {
                nextWriteStarted = null
                nextWriteRelease = null
                started.complete(Unit)
                release.await()
            }
            drafts[ownerId] = ConversationWorkspaceDraft(text, attachmentsJson)
        }

        override suspend fun clearAcceptedDraft(ownerId: String) {
            drafts[ownerId] = ConversationWorkspaceDraft("", null)
        }

        fun blockNextWrite(
            started: CompletableDeferred<Unit>,
            release: CompletableDeferred<Unit>,
        ) {
            nextWriteStarted = started
            nextWriteRelease = release
        }

        fun text(ownerId: String): String = drafts[ownerId]?.text.orEmpty()

        fun attachment(ownerId: String, attachmentId: String): SelectedAttachment =
            attachments(ownerId).single { it.localId == attachmentId }

        fun readyCount(): Int = drafts.keys.sumOf { ownerId ->
            attachments(ownerId).count { it.importState == AttachmentImportState.READY }
        }

        fun loadCount(ownerId: String): Int = loads[ownerId]?.get() ?: 0

        private fun attachments(ownerId: String): List<SelectedAttachment> = drafts[ownerId]
            ?.attachmentsJson
            ?.let { Json.decodeFromString(it) }
            ?: emptyList()
    }

    private fun AtomicInteger.updateMax(candidate: Int) {
        updateAndGet { current -> maxOf(current, candidate) }
    }

    private companion object {
        const val OWNER_A = "conversation-a"
        const val OWNER_B = "conversation-b"
        const val OWNER_C = "conversation-c"
        const val OWNER_D = "conversation-d"
    }
}
