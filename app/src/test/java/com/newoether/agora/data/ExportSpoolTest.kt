package com.newoether.agora.data
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
class ExportSpoolTest {
    @Test
    fun slicesRoundTripMultibyteRecordsAndWatermarks() {
        val spool = File.createTempFile("agora-spool-test-", ".jsonl")
        try {
            val index = ExportSpoolWriter(spool).use { writer ->
                writer.writeConversation("one", 11L, """{"id":"one","title":"中文 🎨"}""")
                writer.writeRun("""{"id":"run-one","conversationId":"one"}""")
                writer.writeMessage("""{"id":"message-one","text":"带\t制表符与\n转义换行"}""")
                writer.writeLoop("""{"conversationId":"one"}""")
                writer.writeConversation("two", 22L, """{"id":"two"}""")
                writer.writeMessage("""{"id":"message-two"}""")
                writer.writeTask("""{"id":"task-one"}""")
                writer.finish()
            }
            assertEquals(listOf("one", "two"), index.conversations.map { it.id })
            assertEquals(listOf(11L, 22L), index.conversations.map { it.dataChangedAt })
            assertEquals(mapOf("one" to 11L, "two" to 22L), index.watermarks)
            assertEquals(1, index.taskSlices.size)
            val reader = ExportSpoolReader(spool)
            val one = reader.collect(index.conversations.first().slice)
            assertEquals(listOf("C", "R", "M", "L"), one.map { it.first })
            assertEquals("""{"id":"one","title":"中文 🎨"}""", one.first().second)
            assertEquals("""{"id":"message-one","text":"带\t制表符与\n转义换行"}""", one[2].second)
            assertEquals(
                listOf("C", "M"),
                reader.collect(index.conversations[1].slice).map { it.first },
            )
            assertEquals(
                listOf("""{"id":"task-one"}"""),
                index.taskSlices.flatMap { reader.collect(it) }.map { it.second },
            )
            // Slices must be disjoint and ordered; the file holds nothing else.
            val consumed = (index.conversations.map { it.slice } + index.taskSlices)
                .sortedBy { it.startOffset }
            var cursor = 0L
            consumed.forEach { slice ->
                assertEquals(cursor, slice.startOffset)
                assertTrue(slice.endOffset > slice.startOffset)
                cursor = slice.endOffset
            }
            assertEquals(spool.length(), cursor)
        } finally {
            spool.delete()
        }
    }

    private fun ExportSpoolReader.collect(
        slice: ExportSpoolSlice,
    ): List<Pair<String, String>> {
        val records = mutableListOf<Pair<String, String>>()
        readRecords(slice) { type, json -> records += type to json }
        return records
    }
}
