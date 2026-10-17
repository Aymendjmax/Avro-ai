package com.newoether.agora.diagnostics

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DeveloperDiagnosticsStreamingContractTest {
    @Test
    fun `wire observation occurs after the existing single stream read`() {
        val sourceRoot = locateMainSourceRoot()
        val httpSource = File(
            sourceRoot,
            "com/newoether/agora/api/HttpClient.kt",
        ).readText()
        val readLineBody = httpSource
            .substringAfter("fun readLine(): String?")
            .substringBefore("fun setReadTimeoutMillis")

        assertEquals(1, Regex("""readUtf8Line\(""").findAll(readLineBody).count())
        assertTrue(
            "Diagnostics must observe the value returned by the provider's existing read",
            readLineBody.indexOf("readUtf8Line()") <
                readLineBody.indexOf("DeveloperDiagnostics.recordWireLine"),
        )
    }

    @Test
    fun `parsed semantic observation stays at the authorized provider consumer boundary`() {
        val sourceRoot = locateMainSourceRoot()
        val executorSource = File(
            sourceRoot,
            "com/newoether/agora/viewmodel/ProviderPassEffectExecutor.kt",
        ).readText()
        val consumer = executorSource
            .substringAfter("runner.run(")
            .substringBefore("            }\n        } catch")

        assertEquals(1, Regex("""recordParsedEvent\(event\)""").findAll(consumer).count())
        assertTrue(
            consumer.indexOf("recordParsedEvent(event)") <
                consumer.indexOf("callbacks.onEvent(event)"),
        )
    }

    private fun locateMainSourceRoot(): File {
        var directory = File(requireNotNull(System.getProperty("user.dir"))).absoluteFile
        repeat(8) {
            listOf(
                File(directory, "app/src/main/java"),
                File(directory, "src/main/java"),
            ).firstOrNull(File::isDirectory)?.let { return it }
            directory = directory.parentFile ?: error("Reached filesystem root")
        }
        error("Unable to locate the main Java source directory")
    }
}
