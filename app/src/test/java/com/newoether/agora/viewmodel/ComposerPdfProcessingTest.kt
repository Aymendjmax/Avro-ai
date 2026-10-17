package com.newoether.agora.viewmodel

import com.newoether.agora.model.AttachmentImportState
import com.newoether.agora.model.SelectedAttachment
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
internal class ComposerPdfProcessingTest : ComposerControllerTestFixture() {
    @Test
    fun `restore keeps completed pdf preview and unconfigured video without starting jobs`() = runTest {
        val pdf = attachment("pdf").copy(
            type = "pdf",
            fileName = "document.pdf",
            localPath = "/stage/document.pdf",
            pageCount = 2,
            preRenderedPaths = listOf("/preview/page-1.jpg", "/preview/page-2.jpg"),
            importState = AttachmentImportState.PROCESSING,
        )
        val video = attachment("video").copy(
            type = "video",
            fileName = "clip.mp4",
            localPath = "/stage/clip.mp4",
            importState = AttachmentImportState.PROCESSING,
        )
        val processor = mockk<AttachmentImportProcessor>()
        val fixture = fixture(
            processor = processor,
            initial = mapOf(OWNER_A to draft(attachments = arrayOf(pdf, video))),
        )

        fixture.controller.load(OWNER_A)
        runCurrent()
        fixture.controller.awaitProcessing(OWNER_A)

        assertEquals(listOf(pdf, video), fixture.controller.state(OWNER_A).value.attachments)
        coVerify(exactly = 0) { processor.preparePdfPreview(any(), any(), any()) }
        coVerify(exactly = 0) { processor.process(any(), any(), any()) }
    }

    @Test
    fun `restore prepares missing pdf preview and projects progress`() = runTest {
        val pdf = attachment("pdf-preview").copy(
            type = "pdf",
            fileName = "document.pdf",
            localPath = "/stage/document.pdf",
            pageCount = 2,
            importState = AttachmentImportState.PROCESSING,
        )
        val previewReady = pdf.copy(
            preRenderedPaths = listOf("/preview/page-1.jpg", "/preview/page-2.jpg"),
        )
        val releasePreview = CompletableDeferred<Unit>()
        val processor = mockk<AttachmentImportProcessor>()
        coEvery { processor.preparePdfPreview(pdf, any(), any()) } coAnswers {
            thirdArg<suspend (Int, Int) -> Unit>().invoke(1, 2)
            releasePreview.await()
            AttachmentImportProcessor.ProcessResult.Ready(previewReady)
        }
        val fixture = fixture(
            processor = processor,
            initial = mapOf(OWNER_A to draft(attachments = arrayOf(pdf))),
        )

        fixture.controller.load(OWNER_A)
        runCurrent()

        assertEquals(1 to 2, fixture.controller.state(OWNER_A).value.pdfPreviewProgress[pdf.localId])
        releasePreview.complete(Unit)
        fixture.controller.awaitProcessing(OWNER_A)

        assertEquals(previewReady, fixture.persistence.attachment(OWNER_A))
        assertTrue(fixture.controller.state(OWNER_A).value.pdfPreviewProgress.isEmpty())
        coVerify(exactly = 1) { processor.preparePdfPreview(pdf, any(), any()) }
        coVerify(exactly = 0) { processor.process(any(), any(), any()) }
    }

    @Test
    fun `configuring pdf cancels stale preview job before final processing`() = runTest {
        val pdf = attachment("pdf-preview-race").copy(
            type = "pdf",
            fileName = "document.pdf",
            localPath = "/stage/document.pdf",
            pageCount = 2,
            importState = AttachmentImportState.PROCESSING,
        )
        val previewStarted = CompletableDeferred<Unit>()
        val previewCancelled = CompletableDeferred<Unit>()
        val processor = mockk<AttachmentImportProcessor>()
        coEvery { processor.preparePdfPreview(pdf, any(), any()) } coAnswers {
            previewStarted.complete(Unit)
            try {
                awaitCancellation()
            } finally {
                previewCancelled.complete(Unit)
            }
        }
        coEvery { processor.process(any(), any(), any()) } coAnswers {
            AttachmentImportProcessor.ProcessResult.Ready(
                firstArg<SelectedAttachment>().copy(
                    selectedPages = setOf(0),
                    preRenderedPaths = listOf("/rendered/page-1.jpg"),
                    importState = AttachmentImportState.READY,
                ),
            )
        }
        val fixture = fixture(
            processor = processor,
            initial = mapOf(OWNER_A to draft(attachments = arrayOf(pdf))),
        )

        fixture.controller.load(OWNER_A)
        previewStarted.await()

        assertTrue(fixture.controller.configurePdf(OWNER_A, pdf.localId, setOf(1)))
        previewCancelled.await()
        fixture.controller.awaitProcessing(OWNER_A)

        assertEquals(AttachmentImportState.READY, fixture.state(OWNER_A, pdf.localId).importState)
        coVerify(exactly = 1) { processor.preparePdfPreview(pdf, any(), any()) }
        coVerify(exactly = 1) {
            processor.process(match { it.selectedPages == setOf(1) }, any(), any())
        }
    }

    @Test
    fun `configuring durable pdf persists choice and starts processing once`() = runTest {
        val pdf = attachment("pdf-configured").copy(
            type = "pdf",
            fileName = "document.pdf",
            localPath = "/stage/document.pdf",
            pageCount = 4,
            preRenderedPaths = listOf(
                "/preview/page-1.jpg",
                "/preview/page-2.jpg",
                "/preview/page-3.jpg",
                "/preview/page-4.jpg",
            ),
            importState = AttachmentImportState.PROCESSING,
        )
        val processor = mockk<AttachmentImportProcessor>()
        coEvery { processor.process(any(), any(), any()) } coAnswers {
            AttachmentImportProcessor.ProcessResult.Ready(
                firstArg<SelectedAttachment>().copy(
                    selectedPages = setOf(0, 1),
                    preRenderedPaths = listOf("/rendered/page-1.jpg", "/rendered/page-2.jpg"),
                    importState = AttachmentImportState.READY,
                ),
            )
        }
        val fixture = fixture(
            processor = processor,
            initial = mapOf(OWNER_A to draft(attachments = arrayOf(pdf))),
        )
        fixture.controller.load(OWNER_A)

        assertTrue(fixture.controller.configurePdf(OWNER_A, pdf.localId, setOf(1, 3)))
        fixture.controller.awaitProcessing(OWNER_A)

        assertEquals(setOf(0, 1), fixture.persistence.attachment(OWNER_A).selectedPages)
        assertEquals(AttachmentImportState.READY, fixture.state(OWNER_A, pdf.localId).importState)
        coVerify(exactly = 1) {
            processor.process(match { it.selectedPages == setOf(1, 3) }, any(), any())
        }
    }

    @Test
    fun `configuration selected during staging is merged before processing`() = runTest {
        val source = attachment("pdf-staging").copy(
            type = "pdf",
            fileName = "document.pdf",
        )
        val stageStarted = CompletableDeferred<Unit>()
        val releaseStage = CompletableDeferred<Unit>()
        val processor = mockk<AttachmentImportProcessor>()
        coEvery { processor.stage(match { it.localId == source.localId }, any()) } coAnswers {
            stageStarted.complete(Unit)
            releaseStage.await()
            AttachmentImportProcessor.StageResult.Success(
                attachment = source.processing("/stage/document.pdf"),
                createdPaths = emptyList(),
            )
        }
        coEvery { processor.process(any(), any(), any()) } coAnswers {
            AttachmentImportProcessor.ProcessResult.Ready(
                firstArg<SelectedAttachment>().copy(
                    preRenderedPaths = listOf("/rendered/page.jpg"),
                    importState = AttachmentImportState.READY,
                ),
            )
        }
        val fixture = fixture(processor)
        fixture.controller.load(OWNER_A)
        fixture.controller.importAttachment(OWNER_A, source)
        stageStarted.await()

        assertTrue(fixture.controller.configurePdf(OWNER_A, source.localId, setOf(2)))
        releaseStage.complete(Unit)
        fixture.controller.awaitProcessing(OWNER_A)

        assertEquals(setOf(2), fixture.persistence.attachment(OWNER_A).selectedPages)
        coVerify(exactly = 1) {
            processor.process(match { it.selectedPages == setOf(2) }, any(), any())
        }
    }
}
