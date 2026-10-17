package com.newoether.agora.viewmodel

import com.newoether.agora.model.AttachmentStorage
import com.newoether.agora.model.SelectedAttachment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SandboxAttachmentPayloadTest {
    @Test
    fun sandboxFilePersistsRuntimeMetadataWithoutDevicePathOrTextRead() {
        val source = SelectedAttachment(
            uri = "content://picker/archive.apk",
            type = "file",
            fileName = "archive.apk",
            mimeType = "application/vnd.android.package-archive",
            fileSize = 8192L,
            localPath = "/physical/sandbox-home/attachments/id/archive.apk",
            storage = AttachmentStorage.LOCAL_SANDBOX_RUNTIME,
            sandboxPath = "/home/agora/attachments/id/archive.apk",
        )

        val payload = MessagePayloadBuilder().buildComposerPayload(listOf(source))
        val item = requireNotNull(payload.attachmentMeta).items.single()

        assertEquals(emptyList<String>(), payload.allImages)
        assertNull(item.originalUri)
        assertNull(item.textContent)
        assertEquals(AttachmentStorage.LOCAL_SANDBOX_RUNTIME, item.storage)
        assertEquals(source.sandboxPath, item.sandboxPath)
        assertEquals(source.mimeType, item.mimeType)
        assertEquals(source.fileSize, item.fileSize)
    }
}
