package com.newoether.agora.remote

import com.newoether.agora.model.SelectedAttachment
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class FiloEncryptedUploadInteropTest {
    @get:Rule val temporary = TemporaryFolder()
    @Test fun originalBytesReachTheSelectedGoStoreBeforeOneNativeSend() = runBlocking {
        val url = System.getenv("FILO_ATTACHMENT_FIXTURE_URL")
        assumeTrue("Opt-in isolated Go Filo server", !url.isNullOrBlank())
        val file = temporary.newFile("original.bin")
        file.writeBytes(ByteArray(600_003) { it.toByte() })
        val client = FiloClient(url!!, "a".repeat(64))
        assertTrue(client.connect().isNotBlank())
        val uploaded = client.upload(SelectedAttachment(uri = file.toURI().toString(), type = "file",
            fileName = "original.bin", mimeType = "application/octet-stream", fileSize = file.length(), localPath = file.path))
        assertEquals(file.length(), uploaded.size)
        assertFalse(uploaded.path.isNullOrBlank())
        val created = client.create("Read the attached file", "11111111-1111-4111-8111-111111111111", listOf(uploaded.id))
        assertTrue(created.id.isNotBlank())
    }
}
