package com.newoether.agora.util

import com.newoether.agora.model.AttachmentImportState
import com.newoether.agora.model.AttachmentItem
import com.newoether.agora.model.AttachmentMeta
import com.newoether.agora.model.AttachmentStorage
import com.newoether.agora.model.SelectedAttachment
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.CancellationException
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class AttachmentFoundationTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()
    @Test
    fun exactLiveOwnersPreserveSharedCanonicalPathUntilBothRelease() {
        val file = temporaryFolder.newFile("shared.jpg")
        val first = Any()
        val second = Any()
        try {
            AttachmentFiles.retainLivePath(first, file.absolutePath)
            AttachmentFiles.setLivePaths(second, listOf("file://${file.parent}/./${file.name}"))
            assertFalse(AttachmentFiles.deleteIfUnowned(file))
            AttachmentFiles.releaseLivePaths(first)
            AttachmentFiles.deleteBacking(SelectedAttachment(uri = "content://source", type = "image", localPath = file.path))
            assertTrue(file.exists())
            assertFalse(AttachmentFiles.deleteIfUnowned(file))
            AttachmentFiles.releaseLivePaths(second)
            assertTrue(AttachmentFiles.deleteIfUnowned(file))
            assertFalse(file.exists())
        } finally {
            AttachmentFiles.releaseLivePaths(first)
            AttachmentFiles.releaseLivePaths(second)
        }
    }

    @Test
    fun legacyMetadataDefaultsToAppPrivateStorage() {
        val decoded = Json.decodeFromString<AttachmentMeta>(
            """{"items":[{"type":"file","file_name":"notes.txt"}]}""",
        ).items.single()

        assertEquals(AttachmentStorage.APP_PRIVATE, decoded.storage)
        assertTrue(decoded.storage.canPreview)
        assertTrue(decoded.storage.reclaimWhenAbandoned)
    }

    @Test
    fun legacySelectedAttachmentDefaultsToReadyImportState() {
        val decoded = Json.decodeFromString<SelectedAttachment>(
            """{"localId":"legacy","uri":"file:///draft","type":"image"}""",
        )

        assertEquals(AttachmentImportState.READY, decoded.importState)
        assertNull(decoded.preparedText)
        assertNull(decoded.pageCount)
        assertNull(decoded.videoDurationMs)
        assertFalse(decoded.unavailable)
    }

    @Test
    fun sandboxMetadataRoundTripsPathSizeAndRuntimeOwnership() {
        val item = AttachmentItem(
            type = "file",
            fileName = "archive.bin",
            mimeType = "application/octet-stream",
            storage = AttachmentStorage.LOCAL_SANDBOX_RUNTIME,
            sandboxPath = "/home/agora/attachments/id/archive.bin",
            fileSize = 42L,
        )

        val decoded = Json.decodeFromString<AttachmentMeta>(
            Json.encodeToString(AttachmentMeta(listOf(item))),
        ).items.single()

        assertEquals(item, decoded)
        assertFalse(decoded.storage.canPreview)
        assertFalse(decoded.storage.reclaimWhenAbandoned)
        assertEquals(
            AttachmentStorage.LOCAL_SANDBOX_RUNTIME,
            AttachmentStorage.LOCAL_SANDBOX_PENDING.transferForSend(),
        )
    }

    @Test
    fun boundedCopyReportsBytesAndWritesCompleteFile() {
        val target = File(temporaryFolder.root, "nested/attachment.bin")
        val progress = mutableListOf<Pair<Long, Long?>>()

        val result = AttachmentFiles.copyBounded(
            input = ByteArrayInputStream(byteArrayOf(1, 2, 3, 4)),
            target = target,
            expectedSize = 4L,
            maxBytes = 4L,
            onProgress = { copied, total -> progress += copied to total },
        )

        assertEquals(AttachmentFiles.CopyResult.Success(4L), result)
        assertTrue(target.isFile)
        assertEquals(listOf(1, 2, 3, 4), target.readBytes().map(Byte::toInt))
        assertEquals(0L to 4L, progress.first())
        assertEquals(4L to 4L, progress.last())
    }

    @Test
    fun boundedCopyEnforcesActualBytesAndDeletesPartialOutput() {
        val target = temporaryFolder.newFile("overflow.bin")

        val result = AttachmentFiles.copyBounded(
            input = ByteArrayInputStream(ByteArray(9)),
            target = target,
            expectedSize = null,
            maxBytes = 8L,
        )

        assertEquals(AttachmentFiles.CopyResult.TooLarge, result)
        assertFalse(target.exists())
    }

    @Test
    fun boundedCopyDeletesOutputAfterReadFailure() {
        val target = temporaryFolder.newFile("failed.bin")
        val input = object : InputStream() {
            private var delivered = false

            override fun read(): Int = throw UnsupportedOperationException()

            override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                if (delivered) throw IOException("provider disconnected")
                delivered = true
                buffer[offset] = 7
                return 1
            }
        }

        val result = AttachmentFiles.copyBounded(input, target, maxBytes = 8L)

        assertTrue(result is AttachmentFiles.CopyResult.Failure)
        assertFalse(target.exists())
    }

    @Test
    fun boundedCopyDeletesOutputAndPreservesCancellation() {
        val target = temporaryFolder.newFile("cancelled.bin")
        val cancellation = CancellationException("removed")
        val input = object : InputStream() {
            override fun read(): Int = throw cancellation

            override fun read(buffer: ByteArray, offset: Int, length: Int): Int = throw cancellation
        }

        val thrown = runCatching {
            AttachmentFiles.copyBounded(input, target, maxBytes = 8L)
        }.exceptionOrNull()

        assertEquals(cancellation, thrown)
        assertFalse(target.exists())
    }

    @Test
    fun pendingSandboxCleanupDeletesFileAndEmptyUuidDirectory() {
        val directory = temporaryFolder.newFolder("attachment-id")
        val payload = File(directory, "payload.bin").apply { writeText("payload") }
        val attachment = SelectedAttachment(
            uri = "content://source",
            type = "file",
            localPath = payload.absolutePath,
            storage = AttachmentStorage.LOCAL_SANDBOX_PENDING,
            sandboxPath = "/home/agora/attachments/attachment-id/payload.bin",
        )

        AttachmentFiles.deleteBacking(attachment)

        assertFalse(payload.exists())
        assertFalse(directory.exists())
    }

    @Test
    fun runtimeSandboxCleanupNeverDeletesPayload() {
        val directory = temporaryFolder.newFolder("runtime-id")
        val payload = File(directory, "payload.bin").apply { writeText("payload") }

        AttachmentFiles.deleteBacking(
            SelectedAttachment(
                uri = "content://source",
                type = "file",
                localPath = payload.absolutePath,
                storage = AttachmentStorage.LOCAL_SANDBOX_RUNTIME,
            ),
        )

        assertTrue(payload.isFile)
        assertTrue(directory.isDirectory)
    }

    @Test
    fun referenceAwareCleanupRemovesOnlyPendingSandboxEmptyDirectory() {
        val pendingDirectory = temporaryFolder.newFolder("pending-reference")
        val pendingPayload = File(pendingDirectory, "payload.bin").apply { writeText("payload") }
        val runtimeDirectory = temporaryFolder.newFolder("runtime-reference")
        pendingPayload.delete()

        AttachmentFiles.deleteEmptySandboxParents(
            listOf(
                SelectedAttachment(
                    uri = "pending",
                    type = "file",
                    localPath = pendingPayload.absolutePath,
                    storage = AttachmentStorage.LOCAL_SANDBOX_PENDING,
                ),
                SelectedAttachment(
                    uri = "runtime",
                    type = "file",
                    localPath = File(runtimeDirectory, "payload.bin").absolutePath,
                    storage = AttachmentStorage.LOCAL_SANDBOX_RUNTIME,
                ),
            ),
        )

        assertFalse(pendingDirectory.exists())
        assertTrue(runtimeDirectory.isDirectory)
    }

    @Test
    fun filenameSanitizationPreventsTraversalAndUsesBlankFallback() {
        assertEquals(".._unsafe_name_.bin", AttachmentFiles.sanitizeFileName("../unsafe:name?.bin"))
        assertEquals("attachment", AttachmentFiles.sanitizeFileName("  "))
        assertEquals("attachment", AttachmentFiles.sanitizeFileName(".."))
        assertNull(AttachmentItem(type = "file").sandboxPath)
    }

    @Test
    fun mimeRoutingUsesMimeOnly() {
        assertEquals(FileValidator.AttachmentRoute.IMAGE, FileValidator.routeForMimeType("image/png"))
        assertEquals(FileValidator.AttachmentRoute.VIDEO, FileValidator.routeForMimeType("video/mp4"))
        assertEquals(FileValidator.AttachmentRoute.PDF, FileValidator.routeForMimeType("application/pdf"))
        assertEquals(FileValidator.AttachmentRoute.TEXT, FileValidator.routeForMimeType("application/json"))
        assertEquals(FileValidator.AttachmentRoute.LOCAL_SANDBOX, FileValidator.routeForMimeType(null))
        assertEquals(
            FileValidator.AttachmentRoute.LOCAL_SANDBOX,
            FileValidator.routeForMimeType("application/octet-stream"),
        )
    }
}
