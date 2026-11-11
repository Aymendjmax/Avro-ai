package com.newoether.agora.sandbox

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import java.io.ByteArrayInputStream
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class FileStoreTest {
    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        File(context.filesDir, FileStore.ROOT_DIR_NAME).deleteRecursively()
    }

    @Test
    fun testSanitizeFileNamePreservesArabicAndUnicodeCharacters() {
        val arabicName = "تقرير_الحسابات_2026.pdf"
        val sanitized = FileStore.sanitizeFileName(arabicName)
        assertEquals("تقرير_الحسابات_2026.pdf", sanitized)

        val mixedName = "مشروع_Agora_v2.txt"
        assertEquals("مشروع_Agora_v2.txt", FileStore.sanitizeFileName(mixedName))

        val chineseName = "开发文档.md"
        assertEquals("开发文档.md", FileStore.sanitizeFileName(chineseName))
    }

    @Test
    fun testSanitizeFileNameStripsPathSeparatorsAndForbiddenChars() {
        val dangerous = "../../etc/passwd"
        val sanitized = FileStore.sanitizeFileName(dangerous)
        assertFalse(sanitized.contains("/"))
        assertFalse(sanitized.contains("\\"))
        assertEquals("passwd", sanitized)

        val forbidden = "invalid:file*name?.txt"
        val sanitizedForbidden = FileStore.sanitizeFileName(forbidden)
        assertFalse(sanitizedForbidden.contains(":"))
        assertFalse(sanitizedForbidden.contains("*"))
        assertFalse(sanitizedForbidden.contains("?"))
    }

    @Test
    fun testStoreUploadEnforcesSizeLimitAndDeletesPartialFile() {
        val content = "A".repeat(5000)
        val maxAllowed = 2000L // 2KB limit

        try {
            FileStore.storeUpload(
                context = context,
                chatId = "chat-size-test",
                originalFileName = "large.txt",
                inputStream = ByteArrayInputStream(content.toByteArray()),
                mimeType = "text/plain",
                maxBytes = maxAllowed,
            )
            fail("Expected IllegalStateException due to size limit")
        } catch (e: IllegalStateException) {
            assertTrue(e.message?.contains("exceeds FileStore upload limit") == true)
        }

        val home = FileStore.getSandboxHomeDir(context)
        val target = File(home, "uploads/chat-size-test/large.txt")
        assertFalse("Partial target file must be deleted upon size limit error", target.exists())
    }

    @Test
    fun testAllocateUniqueFileAvoidsCollisions() {
        val home = FileStore.getSandboxHomeDir(context)
        val testDir = File(home, "uploads/test-collisions").apply { mkdirs() }

        val f1 = FileStore.allocateUniqueFile(testDir, "test.txt").apply { writeText("one") }
        assertEquals("test.txt", f1.name)

        val f2 = FileStore.allocateUniqueFile(testDir, "test.txt").apply { writeText("two") }
        assertEquals("test_1.txt", f2.name)

        val f3 = FileStore.allocateUniqueFile(testDir, "test.txt").apply { writeText("three") }
        assertEquals("test_2.txt", f3.name)
    }

    @Test
    fun testDeleteChatFoldersRecursivelyCleansUploadsAndMetadata() {
        val meta = FileStore.storeUpload(
            context = context,
            chatId = "chat-delete-test",
            originalFileName = "test.txt",
            inputStream = ByteArrayInputStream("content".toByteArray()),
            mimeType = "text/plain",
        )

        val home = FileStore.getSandboxHomeDir(context)
        val uploadDir = File(home, "uploads/chat-delete-test")
        val metaDir = File(home, ".avro/metadata/chat-delete-test")
        assertTrue(uploadDir.exists())
        assertTrue(metaDir.exists())

        FileStore.deleteChatFolders(context, "chat-delete-test")
        assertFalse(uploadDir.exists())
        assertFalse(metaDir.exists())
    }
}
