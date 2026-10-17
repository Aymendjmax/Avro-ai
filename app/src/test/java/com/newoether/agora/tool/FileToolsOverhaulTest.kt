package com.newoether.agora.tool

import com.newoether.agora.util.ShellClient
import com.newoether.agora.util.ShellFileEdit
import com.newoether.agora.util.ShellFileReadResult
import com.newoether.agora.util.decodeUtf8FileSlice
import com.newoether.agora.util.editShellFileContent
import com.newoether.agora.util.grepShellFileText
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FileToolsOverhaulTest {
    private fun args(raw: String) = parseToolArgs(raw)

    @Test
    fun editArgsAcceptSingleFormWithEmptyNewString() {
        val edits = parseFileEditArgs(args("""{"old_string":"  ","new_string":""}""")).getOrThrow()
        assertEquals(listOf(ShellFileEdit("  ", "", false)), edits)
    }

    @Test
    fun multiEditArgsParseArrayAndJsonEncodedString() {
        val array = parseFileMultiEditArgs(
            args("""{"edits":[{"old_string":"a","new_string":"b"},{"old_string":"c","new_string":"","replace_all":true}]}"""),
        ).getOrThrow()
        assertEquals(listOf(ShellFileEdit("a", "b"), ShellFileEdit("c", "", true)), array)
        val encoded = parseFileMultiEditArgs(
            args("""{"edits":"[{\"old_string\":\"a\",\"new_string\":\"b\"}]"}"""),
        ).getOrThrow()
        assertEquals(listOf(ShellFileEdit("a", "b")), encoded)
    }

    @Test
    fun editArgsRejectInvalidCombinations() {
        assertTrue(parseFileEditArgs(args("""{"old_string":""}""")).isFailure)
        // file_edit never takes a batch, even with a valid single edit next to it.
        val batch = parseFileEditArgs(args("""{"edits":[{"old_string":"a","new_string":"b"}],"old_string":"x","new_string":"y"}"""))
        assertTrue(batch.exceptionOrNull()?.message.orEmpty().contains("file_multi_edit"))
        assertEquals(listOf(ShellFileEdit("x", "y")), parseFileEditArgs(args("""{"old_string":"x","new_string":"y","edits":null}""")).getOrThrow())

        assertEquals("edits is required", parseFileMultiEditArgs(args("""{"old_string":"x","new_string":"y"}""")).exceptionOrNull()?.message)
        assertTrue(parseFileMultiEditArgs(args("""{"edits":[]}""")).isFailure)
        // Top-level single-edit fields are outside file_multi_edit's schema: ignored whatever their value.
        val stray = parseFileMultiEditArgs(
            args("""{"edits":[{"old_string":"a","new_string":"b"}],"old_string":" ","new_string":"z","replace_all":true}"""),
        ).getOrThrow()
        assertEquals(listOf(ShellFileEdit("a", "b")), stray)
        val emptyOld = parseFileMultiEditArgs(args("""{"edits":[{"old_string":"","new_string":"b"}]}"""))
        assertEquals("edits[0]: old_string is required", emptyOld.exceptionOrNull()?.message)
        val nullNew = parseFileMultiEditArgs(args("""{"edits":[{"old_string":"a","new_string":null}]}"""))
        assertEquals("edits[0]: new_string is required", nullNew.exceptionOrNull()?.message)
        val many = (0..100).joinToString(",") { """{"old_string":"a$it","new_string":"b"}""" }
        assertTrue(parseFileMultiEditArgs(args("""{"edits":[$many]}""")).isFailure)
    }

    @Test
    fun batchEditsApplyInOrderAndFailAtomically() {
        val (content, result) = editShellFileContent(
            "one two",
            listOf(ShellFileEdit("one", "three"), ShellFileEdit("three two", "done")),
        )
        assertEquals("done", content)
        assertEquals(2, result.replacements)

        val (failed, failure) = editShellFileContent(
            "one two",
            listOf(ShellFileEdit("one", "x"), ShellFileEdit("missing", "y")),
        )
        assertNull(failed)
        assertEquals(
            "edits[1]: old_string not found in file after applying earlier edits; no changes written",
            failure.error,
        )
        // Single-edit wording stays identical to the legacy server.
        assertEquals("old_string not found in file", editShellFileContent("a", "b", "c", false).second.error)
    }

    @Test
    fun utf8SliceTrimsOnlyCutRunesAndReportsConsumedBytes() {
        val bytes = "a中b".toByteArray(Charsets.UTF_8) // 61 e4 b8 ad 62
        // Ends inside 中: the cut prefix is dropped and not counted.
        assertEquals("a" to 1, decodeUtf8FileSlice(bytes, 3, reachesEndOfFile = false))
        // Starts inside 中: leading continuation bytes are consumed but not returned.
        val tail = bytes.copyOfRange(2, 5)
        assertEquals("b" to 3, decodeUtf8FileSlice(tail, 3, reachesEndOfFile = true))
        // Invalid bytes are never trimmed, so paging always advances.
        val invalid = byteArrayOf(0xff.toByte(), 0x41)
        assertEquals(2, decodeUtf8FileSlice(invalid, 2, reachesEndOfFile = false).second)
    }

    @Test
    fun grepTextMatchesConchLineNumbersAndContext() {
        val matches = mutableListOf<ShellClient.GrepMatch>()
        grepShellFileText("/f", "a\r\nneedle\r\nb\r\n", Regex("needle"), 1, 10, matches)
        assertEquals(1, matches.size)
        assertEquals(2, matches[0].line)
        assertEquals("needle", matches[0].content)
        assertEquals(listOf("a"), matches[0].before)
        assertEquals(listOf("b"), matches[0].after)
    }

    private fun fakeReader(text: String, chunk: Int): suspend (Long, Long) -> ShellFileReadResult {
        val bytes = text.toByteArray(Charsets.UTF_8)
        return { offset, _ ->
            val start = offset.toInt().coerceAtMost(bytes.size)
            val end = minOf(bytes.size, start + chunk)
            val (content, consumed) = decodeUtf8FileSlice(
                bytes.copyOfRange(start, end),
                end - start,
                reachesEndOfFile = end >= bytes.size,
            )
            ShellFileReadResult(
                content = content,
                lines = 0,
                totalLines = text.count { it == '\n' },
                totalBytes = bytes.size.toLong(),
                returnedBytes = content.toByteArray(Charsets.UTF_8).size.toLong(),
                offset = start.toLong(),
                limit = chunk.toLong(),
                truncated = start + consumed < bytes.size,
                bytesRead = consumed.toLong(),
            )
        }
    }

    @Test
    fun lineModePagesAcrossSlicesAndMultibyteBoundaries() = runBlocking {
        val text = (1..50).joinToString("") { "行$it\r\n" }
        val lines = collectFileReadLines(
            offset = 0,
            startLine = 10,
            maxLines = 5,
            read = fakeReader(text, chunk = 7),
            onError = { error(it) },
        )!!
        assertEquals((10..14).toList(), lines.lines.map { it.number })
        assertEquals("行10", lines.lines.first().text)
        assertTrue(lines.hasMore)
        // endOffset of the last line points at the first byte of line 15.
        val prefix = (1..14).joinToString("") { "行$it\r\n" }.toByteArray(Charsets.UTF_8).size.toLong()
        assertEquals(prefix, lines.lines.last().endOffset)

        val json = Json.parseToJsonElement(
            boundedFileLinesJson("s", "/f", 0, 10, lines),
        ).jsonObject
        assertEquals("15", json.getValue("next_start_line").jsonPrimitive.content)
        assertEquals(prefix.toString(), json.getValue("next_offset").jsonPrimitive.content)
        assertTrue(json.getValue("content").jsonPrimitive.content.startsWith("    10\t行10\n"))
    }

    @Test
    fun lineModeEndOfFileAndPastEnd() = runBlocking {
        val tail = collectFileReadLines(0, 2, 10, read = fakeReader("a\nb", 4), onError = { error(it) })!!
        assertEquals(listOf("b"), tail.lines.map { it.text })
        assertFalse(tail.hasMore)

        val past = collectFileReadLines(0, 9, 10, read = fakeReader("a\nb\n", 4), onError = { error(it) })!!
        assertTrue(past.notReached)
        val json = Json.parseToJsonElement(boundedFileLinesJson("s", "/f", 0, 9, past)).jsonObject
        assertTrue(json.getValue("message").jsonPrimitive.content.contains("past the end"))
    }

    @Test
    fun byteModeReportsNextOffsetFromBytesRead() {
        val raw = boundedFileReadJson(
            server = "s",
            path = "/f",
            result = ShellFileReadResult(
                content = "ab",
                lines = 1,
                totalLines = 1,
                totalBytes = 10,
                returnedBytes = 2,
                offset = 4,
                limit = 3,
                truncated = true,
                bytesRead = 3,
            ),
        )
        val json = Json.parseToJsonElement(raw).jsonObject
        assertEquals("7", json.getValue("next_offset").jsonPrimitive.content)
    }
}
