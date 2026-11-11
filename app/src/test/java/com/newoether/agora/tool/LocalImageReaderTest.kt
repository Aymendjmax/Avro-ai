package com.newoether.agora.tool

import android.content.Context
import android.graphics.Bitmap
import androidx.test.core.app.ApplicationProvider
import com.newoether.agora.model.ToolImageAttachment
import com.newoether.agora.sandbox.SandboxManager
import com.newoether.agora.viewmodel.GenerationContext
import java.io.File
import java.io.FileOutputStream
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class LocalImageReaderTest {
    private lateinit var context: Context
    private lateinit var imageStore: ToolImageStore

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        imageStore = ToolImageStore(context)
        LocalImageReader.clearCache()
    }

    @Test
    fun testBuildReaderPromptLayersAndClamping() {
        val longInstructions = "A".repeat(800)
        val promptDoc = LocalImageReader.buildReaderPrompt(
            preset = "document",
            focus = "financial summary table",
            instructions = longInstructions,
        )

        // Layer 1: Fixed system anchor
        assertTrue(promptDoc.contains("You are an automated visual document and image inspection system"))
        // Layer 2: Preset
        assertTrue(promptDoc.contains("Preset [document]"))
        // Layer 3: Focus & Bounded Instructions
        assertTrue(promptDoc.contains("Focus area: financial summary table"))
        val instructionsSection = promptDoc.substringAfter("Instructions: ")
        // Instructions clamped to 500 characters
        assertTrue(instructionsSection.contains("A".repeat(500)))
        assertFalse(instructionsSection.contains("A".repeat(501)))

        val promptUi = LocalImageReader.buildReaderPrompt("ui", null, "check button alignment")
        assertTrue(promptUi.contains("Preset [ui]"))
        assertTrue(promptUi.contains("check button alignment"))

        val promptCode = LocalImageReader.buildReaderPrompt("code", null, "read stack trace")
        assertTrue(promptCode.contains("Preset [code]"))

        val promptDiagram = LocalImageReader.buildReaderPrompt("diagram", null, "trace flow")
        assertTrue(promptDiagram.contains("Preset [diagram]"))

        val promptDefault = LocalImageReader.buildReaderPrompt("unknown", null, null)
        assertTrue(promptDefault.contains("Preset [general]"))
    }

    @Test
    fun testCacheKeyAndStorage() {
        val sha = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"
        val prompt1 = "Describe the text"
        val prompt2 = "Describe the text " // trimmed equivalence check

        LocalImageReader.putCached(sha, prompt1, "Summary of document")
        assertEquals("Summary of document", LocalImageReader.getCached(sha, prompt1))
        assertEquals("Summary of document", LocalImageReader.getCached(sha, prompt2))

        val otherPrompt = "List all buttons"
        assertNull(LocalImageReader.getCached(sha, otherPrompt))
    }

    @Test
    fun testWrapUntrustedOutput() {
        val raw = "The image contains a login screen with an email field."
        val wrapped = LocalImageReader.wrapUntrustedOutput(raw)
        assertTrue(wrapped.startsWith(LocalImageReader.UNTRUSTED_DESCRIPTION_HEADER))
        assertTrue(wrapped.contains(raw))
    }

    @Test
    fun testImageDownscalingAndCrop() {
        // Create an image larger than 1500px (e.g. 2000x1200)
        val testFile = File(context.cacheDir, "large_test.png")
        val bitmap = Bitmap.createBitmap(2000, 1200, Bitmap.Config.ARGB_8888)
        FileOutputStream(testFile).use { out ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
        }
        bitmap.recycle()

        // Process without crop -> max dimension must be <= 1500
        val bytes = LocalImageReader.processImageBytes(testFile, cropSpec = null)
        val decoded = android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        assertNotNull(decoded)
        assertTrue(decoded.width <= 1500)
        assertTrue(decoded.height <= 1500)
        decoded.recycle()

        // Process with normalized crop (top-left 50% box)
        val croppedBytes = LocalImageReader.processImageBytes(testFile, cropSpec = "0.0,0.0,0.5,0.5")
        val decodedCrop = android.graphics.BitmapFactory.decodeByteArray(croppedBytes, 0, croppedBytes.size)
        assertNotNull(decodedCrop)
        assertTrue(decodedCrop.width <= 1000)
        assertTrue(decodedCrop.height <= 600)
        decodedCrop.recycle()

        testFile.delete()
    }

    @Test
    fun testExecuteLocalViewImageDirectVision() = kotlinx.coroutines.test.runTest {
        val testFile = File(context.cacheDir, "sample_direct.png")
        val bitmap = Bitmap.createBitmap(800, 600, Bitmap.Config.ARGB_8888)
        FileOutputStream(testFile).use { out ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
        }
        bitmap.recycle()

        val ctx = GenerationContext(
            imageTranscriptionEnabled = false, // Direct vision
        )

        val result = LocalImageReader.executeLocalViewImage(
            path = testFile.absolutePath,
            serverName = "Local Sandbox",
            focus = null,
            preset = "document",
            instructions = "What is the document title?",
            crop = null,
            sandbox = null,
            imageStore = imageStore,
            ctx = ctx,
        )

        assertFalse(result.isError)
        assertEquals(1, result.images.size)
        assertFalse("Direct vision must not transcribe images", result.transcribeImages)

        val json = Json.parseToJsonElement(result.text).jsonObject
        assertEquals("view_image", json["type"]?.jsonPrimitive?.content)
        assertEquals("Local Sandbox", json["server"]?.jsonPrimitive?.content)
        assertEquals(true, json["ok"]?.jsonPrimitive?.content?.toBoolean())

        testFile.delete()
    }

    @Test
    fun testExecuteLocalViewImageSecondaryReaderWithCache() = kotlinx.coroutines.test.runTest {
        val testFile = File(context.cacheDir, "sample_reader.png")
        val bitmap = Bitmap.createBitmap(400, 300, Bitmap.Config.ARGB_8888)
        FileOutputStream(testFile).use { out ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
        }
        bitmap.recycle()

        val ctx = GenerationContext(
            imageTranscriptionEnabled = true, // Secondary reader
        )

        val sha = LocalImageReader.computeSha256(testFile)
        val prompt = LocalImageReader.buildReaderPrompt("ui", "login button", "check color")
        LocalImageReader.putCached(sha, prompt, "The login button is blue.")

        val result = LocalImageReader.executeLocalViewImage(
            path = testFile.absolutePath,
            serverName = "Local Sandbox",
            focus = "login button",
            preset = "ui",
            instructions = "check color",
            crop = null,
            sandbox = null,
            imageStore = imageStore,
            ctx = ctx,
        )

        assertFalse(result.isError)
        // Cache hit produces no new image attachment and returns unwrapped description
        assertTrue(result.images.isEmpty())
        assertFalse(result.transcribeImages)
        assertNotNull(result.displayText)
        assertTrue(result.displayText!!.contains("The login button is blue."))
        assertTrue(result.displayText!!.startsWith(LocalImageReader.UNTRUSTED_DESCRIPTION_HEADER))

        testFile.delete()
    }
}
