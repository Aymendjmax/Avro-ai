package com.newoether.agora.tool

import android.content.Context
import com.newoether.agora.model.ConversationMode
import com.newoether.agora.sandbox.FileStore
import com.newoether.agora.viewmodel.GenerationContext
import io.mockk.every
import io.mockk.mockk
import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class PresentFilesToolProviderTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var mockContext: Context
    private lateinit var sandboxHomeDir: File

    @Before
    fun setUp() {
        val filesDir = tempFolder.newFolder("files")
        sandboxHomeDir = File(filesDir, FileStore.ROOT_DIR_NAME).apply { mkdirs() }
        mockContext = mockk {
            every { this@mockk.filesDir } returns filesDir
            every { packageName } returns "com.newoether.agora"
        }
    }

    @Test
    fun testToolDisabledInAskModeWithoutSandbox() {
        val provider = PresentFilesToolProvider(mockContext)
        val ctx = GenerationContext(
            conversationMode = ConversationMode.ASK,
            sandboxEnabled = false,
            shellEnabled = false,
        )
        val defs = provider.definitions(ctx)
        assertTrue("present_files should be disabled in pure Ask mode without sandbox", defs.isEmpty())
    }

    @Test
    fun testToolEnabledInWorkMode() {
        val provider = PresentFilesToolProvider(mockContext)
        val ctx = GenerationContext(
            conversationMode = ConversationMode.WORK,
            sandboxEnabled = true,
            shellEnabled = true,
        )
        val defs = provider.definitions(ctx)
        assertEquals(1, defs.size)
        assertEquals(PresentFilesToolProvider.TOOL_NAME, defs.first().function.name)
        val props = defs.first().function.parameters.properties
        assertTrue(props.containsKey("paths"))
        assertTrue(props.containsKey("path"))
    }

    @Test
    fun testPathSecurityValidation() {
        assertFalse(PresentFilesToolProvider.isAllowedPath("/etc/passwd"))
        assertFalse(PresentFilesToolProvider.isAllowedPath("/home/outputs/../secret"))
        assertFalse(PresentFilesToolProvider.isAllowedPath("/root/id_rsa"))
        assertFalse(PresentFilesToolProvider.isAllowedPath("../etc/passwd"))

        assertTrue(PresentFilesToolProvider.isAllowedPath("/home/outputs/chat_1/results.pdf"))
        assertTrue(PresentFilesToolProvider.isAllowedPath("/home/projects/app/main.py"))
        assertTrue(PresentFilesToolProvider.isAllowedPath("/home/uploads/chat_1/data.csv"))
    }

    @Test
    fun testExecuteWithExistingFile() = kotlinx.coroutines.runBlocking {
        val provider = PresentFilesToolProvider(mockContext)
        val outputsDir = File(sandboxHomeDir, "outputs/test_chat").apply { mkdirs() }
        val testFile = File(outputsDir, "report.pdf").apply {
            writeText("Test PDF report content")
        }

        val ctx = GenerationContext(
            conversationMode = ConversationMode.WORK,
            sandboxEnabled = true,
        )

        val args = """{"paths": ["/home/outputs/test_chat/report.pdf"]}"""
        val resultJson = provider.execute(PresentFilesToolProvider.TOOL_NAME, args, ctx)

        val json = Json.parseToJsonElement(resultJson) as JsonObject
        assertEquals("present_files", (json["type"] as? JsonPrimitive)?.content)
        assertEquals(true, (json["success"] as? JsonPrimitive)?.booleanOrNull)
        assertEquals(1, (json["count"] as? JsonPrimitive)?.intOrNull)

        val files = json["files"] as JsonArray
        assertEquals(1, files.size)
        val fileObj = files.first() as JsonObject
        assertEquals("report.pdf", (fileObj["name"] as? JsonPrimitive)?.content)
        assertEquals("application/pdf", (fileObj["mime_type"] as? JsonPrimitive)?.content)
        assertEquals(false, (fileObj["is_image"] as? JsonPrimitive)?.booleanOrNull)
        assertTrue(fileObj.containsKey("size_formatted"))
    }

    @Test
    fun testExecuteWithNonExistentFile() = kotlinx.coroutines.runBlocking {
        val provider = PresentFilesToolProvider(mockContext)
        val ctx = GenerationContext(
            conversationMode = ConversationMode.WORK,
            sandboxEnabled = true,
        )

        val args = """{"path": "/home/outputs/test_chat/nonexistent.png"}"""
        val resultJson = provider.execute(PresentFilesToolProvider.TOOL_NAME, args, ctx)

        val json = Json.parseToJsonElement(resultJson) as JsonObject
        assertEquals(false, (json["success"] as? JsonPrimitive)?.booleanOrNull)
        assertEquals(0, (json["count"] as? JsonPrimitive)?.intOrNull)

        val files = json["files"] as JsonArray
        assertEquals(1, files.size)
        val fileObj = files.first() as JsonObject
        assertNotNull((fileObj["error"] as? JsonPrimitive)?.content)
    }
}
