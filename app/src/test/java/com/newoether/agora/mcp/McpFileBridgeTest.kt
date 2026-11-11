package com.newoether.agora.mcp

import android.content.Context
import com.newoether.agora.sandbox.FileStore
import com.newoether.agora.tool.ToolExecutionResult
import io.mockk.every
import io.mockk.mockk
import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class McpFileBridgeTest {

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
    fun testResolveSandboxFileRejectsTraversal() {
        val result = McpFileBridge.resolveSandboxFile(
            mockContext,
            "file:/home/outputs/../secret.txt",
        )
        assertNull("Path traversal must be rejected", result)
    }

    @Test
    fun testResolveSandboxFileSuccess() {
        val outputsDir = File(sandboxHomeDir, "outputs").apply { mkdirs() }
        val testFile = File(outputsDir, "test.csv").apply { writeText("a,b,c") }

        val resolved = McpFileBridge.resolveSandboxFile(
            mockContext,
            "file:/home/outputs/test.csv",
        )
        assertNotNull(resolved)
        assertEquals(testFile.canonicalPath, resolved!!.first.canonicalPath)
    }

    @Test
    fun testTransformInputArgumentsSubstitutesTextContent() {
        val outputsDir = File(sandboxHomeDir, "outputs").apply { mkdirs() }
        File(outputsDir, "input.json").apply { writeText("""{"key":"value"}""") }

        val rawArgs = """{"data":"file:/home/outputs/input.json","mode":"fast"}"""
        val transformed = McpFileBridge.transformInputArguments(mockContext, rawArgs)

        val json = Json.parseToJsonElement(transformed) as JsonObject
        assertEquals("""{"key":"value"}""", (json["data"] as? JsonPrimitive)?.content)
        assertEquals("fast", (json["mode"] as? JsonPrimitive)?.content)
    }

    @Test
    fun testBridgeOutputFilesCopiesImagesToOutputs() {
        val imageDir = tempFolder.newFolder("tool_images")
        val sampleImage = File(imageDir, "chart.png").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        val attachment = com.newoether.agora.model.ToolImageAttachment(
            path = sampleImage.absolutePath,
            mimeType = "image/png",
            sizeBytes = sampleImage.length(),
            sha256 = "hash123",
        )

        val rawResult = ToolExecutionResult(
            text = "Generated chart",
            images = listOf(attachment),
        )

        val bridged = McpFileBridge.bridgeOutputFiles(
            context = mockContext,
            serverName = "chart-server",
            toolName = "draw_chart",
            result = rawResult,
            conversationId = "chat_abc",
        )

        assertTrue(bridged.text.contains("file:/home/outputs/chat_abc/"))
        val chatOutputs = File(sandboxHomeDir, "outputs/chat_abc")
        assertTrue(chatOutputs.exists())
        val generated = chatOutputs.listFiles()?.filter { it.name.startsWith("mcp_draw_chart_") }
        assertNotNull(generated)
        assertEquals(1, generated!!.size)
        assertEquals(3, generated.first().length())
    }
}
