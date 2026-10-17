package com.newoether.agora.tool

import com.newoether.agora.sandbox.SandboxManager
import com.newoether.agora.util.SHELL_COMMAND_OUTPUT_MAX_BYTES
import com.newoether.agora.util.readBoundedShellOutput
import io.mockk.coEvery
import io.mockk.mockk
import java.io.ByteArrayInputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class ShellBackendOutputTest {
    @Test
    fun sandboxResultKeepsOutputAndExitCodeWithoutSuccessState() = runTest {
        val manager = mockk<SandboxManager>()
        coEvery { manager.isAvailable() } returns true
        coEvery { manager.executeCommand("command", "/work", 5_000) } returns
            SandboxManager.SandboxResult(
                stdout = "stdout",
                stderr = "stderr",
                exitCode = 7,
                warning = "output truncated",
            )

        val raw = SandboxBackend(manager).executeCommand("command", "/work", 5_000)
        val result = Json.parseToJsonElement(raw).jsonObject

        assertEquals("7", (result["exit_code"] as JsonPrimitive).content)
        assertEquals("stdout\nstderr", (result["output"] as JsonPrimitive).content)
        assertEquals("output truncated", (result["warning"] as JsonPrimitive).content)
        assertFalse(result.containsKey("success"))
    }

    @Test
    fun sandboxCancellationIsNeverConvertedIntoJson() = runTest {
        val manager = mockk<SandboxManager>()
        coEvery { manager.isAvailable() } returns true
        coEvery { manager.executeCommand("command", "/work", 5_000) } throws
            CancellationException("stop")

        try {
            SandboxBackend(manager).executeCommand("command", "/work", 5_000)
            fail("Expected CancellationException")
        } catch (_: CancellationException) {
        }
    }

    @Test
    fun sandboxFileReadCancellationIsNeverConvertedIntoResult() = runTest {
        val manager = mockk<SandboxManager>()
        coEvery { manager.fileRead("/tmp/file", 0, 1) } throws CancellationException("stop")

        try {
            SandboxBackend(manager).fileRead("/tmp/file", 0, 1)
            fail("Expected CancellationException")
        } catch (_: CancellationException) {
        }
    }

    @Test
    fun boundedOutputKeepsACompleteUtf8PrefixAndDrainsTheStream() {
        val expected = "a".repeat(SHELL_COMMAND_OUTPUT_MAX_BYTES - 1)
        val input = ByteArrayInputStream((expected + "😀tail").toByteArray(Charsets.UTF_8))

        val (output, truncated) = input.readBoundedShellOutput()

        assertEquals(expected, output)
        assertTrue(truncated)
        assertEquals(0, input.available())
    }
}
