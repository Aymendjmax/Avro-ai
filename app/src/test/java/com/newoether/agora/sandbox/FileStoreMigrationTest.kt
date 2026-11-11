package com.newoether.agora.sandbox

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.newoether.agora.model.AttachmentImportState
import com.newoether.agora.model.SelectedAttachment
import com.newoether.agora.viewmodel.MessagePayloadBuilder
import java.io.ByteArrayInputStream
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class FileStoreMigrationTest {
    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        File(context.filesDir, FileStore.ROOT_DIR_NAME).deleteRecursively()
    }

    @Test
    fun newChatUploadsMigrateToRealConversationFolderOnFirstSend() {
        val newChatId = "agora:new-chat"
        val realConvId = "conv-uuid-8899"
        val content = "print('Hello from new chat')"
        val meta = FileStore.storeUpload(
            context = context,
            chatId = newChatId,
            originalFileName = "script.py",
            inputStream = ByteArrayInputStream(content.toByteArray()),
            mimeType = "text/x-python",
        )

        assertEquals("/home/uploads/agora_new-chat/script.py", meta.sandboxPath)
        val home = FileStore.getSandboxHomeDir(context)
        val oldFile = File(home, "uploads/agora_new-chat/script.py")
        val oldMeta = File(home, ".avro/metadata/agora_new-chat/script.py.json")
        assertTrue(oldFile.exists())
        assertTrue(oldMeta.exists())

        val attachment = SelectedAttachment(
            localId = "att-1",
            uri = "content://dummy/1",
            fileName = "script.py",
            mimeType = "text/x-python",
            type = "file",
            fileSize = content.length.toLong(),
            sandboxPath = meta.sandboxPath,
            sha256 = meta.sha256,
            localPath = oldFile.absolutePath,
            importState = AttachmentImportState.READY,
        )

        val migrated = FileStore.migrateChatUploads(
            context = context,
            fromChatId = newChatId,
            toChatId = realConvId,
            attachments = listOf(attachment),
        )

        assertEquals(1, migrated.size)
        val migratedAtt = migrated.first()
        assertEquals("/home/uploads/conv-uuid-8899/script.py", migratedAtt.sandboxPath)

        val newFile = File(home, "uploads/conv-uuid-8899/script.py")
        val newMeta = File(home, ".avro/metadata/conv-uuid-8899/script.py.json")
        assertTrue("Migrated physical file must exist", newFile.exists())
        assertTrue("Migrated metadata file must exist", newMeta.exists())
        assertEquals(content, newFile.readText())

        assertFalse("Old physical file must not exist in agora_new-chat", oldFile.exists())
        assertFalse("Old metadata must not exist in agora_new-chat", oldMeta.exists())

        // Verify AttachmentItem produced from migrated attachment contains the new sandboxPath
        val payload = MessagePayloadBuilder().buildComposerPayload(migrated)
        val item = payload.attachmentMeta?.items?.firstOrNull()
        assertNotNull(item)
        assertEquals("/home/uploads/conv-uuid-8899/script.py", item?.sandboxPath)
    }
}
