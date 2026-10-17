package com.newoether.agora.tool

import android.app.Application
import com.newoether.agora.util.ShellCrypto
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class ConchEncryptedSseTest {
    private val key = "0123456789abcdef0123456789abcdef".toByteArray()
    private fun frame(event: String, sequence: String, fields: String, innerEvent: String = event): List<String> =
        listOf("event: $event", "data: " + ShellCrypto.encrypt(key,
            """{"_conch_event":"$innerEvent","_conch_sequence":$sequence,$fields}""".toByteArray()), "")

    private suspend fun parse(lines: List<String>, output: StringBuilder = StringBuilder()): ConchSseResult {
        val iterator = lines.iterator()
        return parseConchSseLines(true,
            { if (iterator.hasNext()) iterator.next() else null },
            { ShellCrypto.decrypt(key, it).toString(Charsets.UTF_8) },
            { output.append(it) })
    }

    @Test fun validCiphertextStreamPreservesOutputAndTerminal() = runTest {
        val result = parse(frame("line", "0", """"line":"hello","stream":"stdout"""") +
            frame("result", "1", """"exit_code":0"""))
        assertEquals("hello", result.output)
        assertEquals(0, result.exitCode)
    }

    @Test fun replayReorderDeletionRenamingAndMalformedSequenceAreRejected() = runTest {
        val first = frame("line", "0", """"line":"once","stream":"stdout"""")
        val second = frame("line", "1", """"line":"second","stream":"stdout"""")
        val terminal = frame("result", "2", """"exit_code":0""")
        for (lines in listOf(first + first + second + terminal, second + first + terminal,
            first + terminal, terminal, first + second,
            frame("warning", "0", """"message":"renamed"""", "line"),
            frame("result", "\"0\"", """"exit_code":0"""),
            frame("result", "-1", """"exit_code":0"""),
            frame("result", "0.0", """"exit_code":0"""))) {
            assertNotNull("tampered stream accepted", runCatching { parse(lines) }.exceptionOrNull())
        }
        val emitted = StringBuilder()
        assertNotNull(runCatching { parse(first + first + terminal, emitted) }.exceptionOrNull())
        assertEquals("once\n", emitted.toString())
    }
}
