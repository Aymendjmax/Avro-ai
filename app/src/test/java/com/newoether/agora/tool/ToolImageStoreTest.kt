package com.newoether.agora.tool

import android.app.Application
import android.graphics.Bitmap
import android.util.Base64
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.security.MessageDigest

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class ToolImageStoreTest {
    @Test
    fun base64ImageStreamsToValidatedFileWithoutChangingContentOrMetadata() {
        val directory = Files.createTempDirectory("agora-tool-images-").toFile()
        try {
            val bitmap = Bitmap.createBitmap(512, 512, Bitmap.Config.ARGB_8888)
            val image = java.io.ByteArrayOutputStream().use { output ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)
                output.toByteArray()
            }
            bitmap.recycle()
            val encoded = Base64.encodeToString(image, Base64.DEFAULT)
            val store = ToolImageStore(RuntimeEnvironment.getApplication(), directory)

            val result = store.persistBase64(encoded, "image/png; charset=utf-8", "conch")

            assertTrue(File(result.path).name.startsWith("conch_"))
            assertArrayEquals(image, File(result.path).readBytes())
            assertEquals(image.size.toLong(), result.sizeBytes)
            assertEquals(512, result.width)
            assertEquals(512, result.height)
            assertEquals("image/png", result.mimeType)
            assertEquals(MessageDigest.getInstance("SHA-256").digest(image)
                .joinToString("") { "%02x".format(it) }, result.sha256)
            assertEquals(1, directory.listFiles()?.size)
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun invalidBase64AndOversizedPayloadLeaveNoPublishedFile() {
        val directory = Files.createTempDirectory("agora-tool-images-").toFile()
        try {
            val store = ToolImageStore(RuntimeEnvironment.getApplication(), directory)
            assertThrows(IllegalArgumentException::class.java) {
                Base64.decode("a", Base64.DEFAULT)
            }
            assertThrows(IOException::class.java) {
                store.persistBase64("a", "image/png")
            }
            assertThrows(IOException::class.java) {
                store.persistBase64("A".repeat(((ToolImageStore.MAX_IMAGE_BYTES + 4) * 4 / 3).toInt()), "image/png")
            }
            assertFalse(directory.listFiles()?.isNotEmpty() == true)
        } finally {
            directory.deleteRecursively()
        }
    }
}
