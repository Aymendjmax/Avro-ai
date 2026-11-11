package com.newoether.agora.tool

import com.newoether.agora.data.ShellDeviceConfig
import com.newoether.agora.viewmodel.GenerationContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OperatingModeToolIsolationTest {

    @Test
    fun testShellToolsDisabledWhenShellNotEnabled() {
        val ctx = GenerationContext(
            shellEnabled = false,
            sandboxEnabled = true,
        )
        val tools = ShellToolDefinitions.build(ctx)
        assertTrue("All shell tools must be isolated/empty when shellEnabled=false", tools.isEmpty())
    }

    @Test
    fun testShellToolsDisabledWhenNoSandboxAndNoRemoteDevices() {
        val ctx = GenerationContext(
            shellEnabled = true,
            sandboxEnabled = false,
            shellDevices = emptyList(),
        )
        val tools = ShellToolDefinitions.build(ctx)
        assertTrue("Shell tools must be empty when no sandbox and no remote servers exist", tools.isEmpty())
    }

    @Test
    fun testLocalSandboxProvidesViewImageAndFileTools() {
        val ctx = GenerationContext(
            shellEnabled = true,
            sandboxEnabled = true,
            shellDevices = emptyList(),
        )
        val tools = ShellToolDefinitions.build(ctx)
        val toolNames = tools.map { it.function.name }.toSet()

        assertTrue(toolNames.contains("list_shells"))
        assertTrue(toolNames.contains("execute_shell_command"))
        assertTrue(toolNames.contains("file_read"))
        assertTrue(toolNames.contains("file_write"))
        assertTrue(toolNames.contains("file_edit"))
        assertTrue(toolNames.contains("file_glob"))
        assertTrue(toolNames.contains("file_grep"))
        assertTrue("view_image must be available for local sandbox (Phase 3)", toolNames.contains("view_image"))

        // Check view_image parameters
        val viewImageTool = tools.find { it.function.name == "view_image" }
        assertNotNull(viewImageTool)
        val props = viewImageTool!!.function.parameters.properties
        assertTrue(props.containsKey("path"))
        assertTrue(props.containsKey("server"))
        assertTrue(props.containsKey("focus"))
        assertTrue(props.containsKey("preset"))
        assertTrue(props.containsKey("instructions"))
        assertTrue(props.containsKey("crop"))

        // Only path is required when only 1 server (Local Sandbox) exists
        assertEquals(listOf("path"), viewImageTool.function.parameters.required)
    }

    @Test
    fun testViewImageWithMultipleServersRequiresServer() {
        val ctx = GenerationContext(
            shellEnabled = true,
            sandboxEnabled = true,
            shellDevices = listOf(
                ShellDeviceConfig(id = "conch-1", name = "MacBook", type = "conch", serverUrl = "https://conch.local"),
            ),
        )
        val tools = ShellToolDefinitions.build(ctx)
        val viewImageTool = tools.find { it.function.name == "view_image" }
        assertNotNull(viewImageTool)
        assertTrue(viewImageTool!!.function.parameters.required.contains("path"))
        assertTrue(viewImageTool.function.parameters.required.contains("server"))
    }
}
