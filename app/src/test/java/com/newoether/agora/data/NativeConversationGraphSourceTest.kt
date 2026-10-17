package com.newoether.agora.data
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
@RunWith(RobolectricTestRunner::class)
class NativeConversationGraphSourceTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun legacyArchiveStreamsConversationEntryWithoutSpool() {
        val cacheDir = tempFolder.newFolder("cache")
        val legacy = """{"conversations":[{"id":"one"}],"tasks":[],"loops":[]}"""
        val source = NativeConversationGraphSource.open(
            archive = FakeEntrySource(mapOf(NativeBackupFormat.CONVERSATIONS_ENTRY to legacy)),
            version = 4,
            cacheDir = cacheDir,
        )
        try {
            assertEquals(legacy, source.open().use { it.readBytes() }.decodeToString())
            assertEquals(emptyList<String>(), spoolNames(cacheDir))
        } finally {
            source.close()
        }
        assertEquals(emptyList<String>(), spoolNames(cacheDir))
    }

    @Test
    fun v5MergesItemsAndTasksIntoReopenableSpool() {
        val cacheDir = tempFolder.newFolder("cache")
        val one = NativeBackupFormat.conversationEntry("one")
        val two = NativeBackupFormat.conversationEntry("two")
        val archive = FakeEntrySource(
            mapOf(
                NativeBackupFormat.CONVERSATION_INDEX_ENTRY to indexJson(
                    "one" to 11L to listOf("media/images/x.png"),
                    "two" to 22L to emptyList(),
                ),
                one to """{"conversations":[{"id":"one","dataChangedAt":11}],
                    "runs":[{"id":"run-one","conversationId":"one"}],
                    "messages":[{"id":"message-one","conversationId":"one"}],
                    "loops":[]}""",
                two to """{"conversations":[{"id":"two","dataChangedAt":22}],"runs":[],
                    "messages":[],"loops":[{"conversationId":"two"}]}""",
                NativeBackupFormat.TASKS_ENTRY to """{"tasks":[{"id":"task-one"}]}""",
            ),
        )
        val source = NativeConversationGraphSource.open(archive, version = 5, cacheDir = cacheDir)
        try {
            assertEquals(1, spoolNames(cacheDir).size)
            // Each item is decompressed once, not once per graph field.
            assertEquals(1, archive.streamCount(one))
            assertEquals(1, archive.streamCount(two))
            val first = source.open().use { it.readBytes() }.decodeToString()
            val second = source.open().use { it.readBytes() }.decodeToString()
            assertEquals(first, second)
            val merged = Json.parseToJsonElement(first).jsonObject
            assertEquals(
                listOf("one", "two"),
                merged.getValue("conversations").jsonArray
                    .map { it.jsonObject.getValue("id").jsonPrimitive.content },
            )
            assertEquals(
                "11",
                merged.getValue("conversations").jsonArray
                    .first().jsonObject.getValue("dataChangedAt").jsonPrimitive.content,
            )
            assertEquals(
                listOf("run-one"),
                merged.getValue("runs").jsonArray
                    .map { it.jsonObject.getValue("id").jsonPrimitive.content },
            )
            assertEquals(
                listOf("message-one"),
                merged.getValue("messages").jsonArray
                    .map { it.jsonObject.getValue("id").jsonPrimitive.content },
            )
            assertEquals(1, merged.getValue("loops").jsonArray.size)
            assertEquals(
                listOf("task-one"),
                merged.getValue("tasks").jsonArray
                    .map { it.jsonObject.getValue("id").jsonPrimitive.content },
            )
        } finally {
            source.close()
        }
        assertEquals(emptyList<String>(), spoolNames(cacheDir))
    }

    @Test
    fun v5RejectsDuplicateConversationIdsAndCleansSpool() {
        val cacheDir = tempFolder.newFolder("cache")
        val entry = NativeBackupFormat.conversationEntry("one")
        val archive = FakeEntrySource(
            mapOf(
                NativeBackupFormat.CONVERSATION_INDEX_ENTRY to
                    """{"conversations":[
                        {"id":"one","dataChangedAt":1,"entry":"$entry"},
                        {"id":"one","dataChangedAt":2,"entry":"$entry"}
                    ]}""",
                entry to """{"conversations":[{"id":"one"}],"runs":[],"messages":[],"loops":[]}""",
                NativeBackupFormat.TASKS_ENTRY to """{"tasks":[]}""",
            ),
        )
        expectFailure(IllegalArgumentException::class.java, cacheDir) {
            NativeConversationGraphSource.open(archive, version = 5, cacheDir = cacheDir)
        }
    }

    @Test
    fun v5RejectsEntryPathMismatchAndCleansSpool() {
        val cacheDir = tempFolder.newFolder("cache")
        val archive = FakeEntrySource(
            mapOf(
                NativeBackupFormat.CONVERSATION_INDEX_ENTRY to
                    """{"conversations":[
                        {"id":"one","dataChangedAt":1,"entry":"conv/items/deadbeef.json"}
                    ]}""",
                NativeBackupFormat.TASKS_ENTRY to """{"tasks":[]}""",
            ),
        )
        expectFailure(IllegalArgumentException::class.java, cacheDir) {
            NativeConversationGraphSource.open(archive, version = 5, cacheDir = cacheDir)
        }
    }

    @Test
    fun v5RejectsMissingItemEntryAndCleansSpool() {
        val cacheDir = tempFolder.newFolder("cache")
        val archive = FakeEntrySource(
            mapOf(
                NativeBackupFormat.CONVERSATION_INDEX_ENTRY to indexJson("one" to 1L to emptyList()),
                NativeBackupFormat.TASKS_ENTRY to """{"tasks":[]}""",
            ),
        )
        expectFailure(IllegalStateException::class.java, cacheDir) {
            NativeConversationGraphSource.open(archive, version = 5, cacheDir = cacheDir)
        }
    }

    @Test
    fun v5RejectsMissingTasksEntryAndCleansSpool() {
        val cacheDir = tempFolder.newFolder("cache")
        val entry = NativeBackupFormat.conversationEntry("one")
        val archive = FakeEntrySource(
            mapOf(
                NativeBackupFormat.CONVERSATION_INDEX_ENTRY to indexJson("one" to 1L to emptyList()),
                entry to """{"conversations":[{"id":"one"}],"runs":[],"messages":[],"loops":[]}""",
            ),
        )
        expectFailure(IllegalStateException::class.java, cacheDir) {
            NativeConversationGraphSource.open(archive, version = 5, cacheDir = cacheDir)
        }
    }

    @Test
    fun v5RejectsMalformedIndexWithoutSpool() {
        val cacheDir = tempFolder.newFolder("cache")
        val archive = FakeEntrySource(
            mapOf(
                NativeBackupFormat.CONVERSATION_INDEX_ENTRY to "not-json",
                NativeBackupFormat.TASKS_ENTRY to """{"tasks":[]}""",
            ),
        )
        expectFailure(Exception::class.java, cacheDir) {
            NativeConversationGraphSource.open(archive, version = 5, cacheDir = cacheDir)
        }
    }

    @Test
    fun legacyArchiveWithoutConversationEntryFailsClosed() {
        val cacheDir = tempFolder.newFolder("cache")
        val archive = FakeEntrySource(mapOf(NativeBackupFormat.TASKS_ENTRY to """{"tasks":[]}"""))
        expectFailure(IllegalArgumentException::class.java, cacheDir) {
            NativeConversationGraphSource.open(archive, version = 3, cacheDir = cacheDir)
        }
    }

    private class FakeEntrySource(entries: Map<String, String>) : NativeGraphEntrySource {
        private val data = entries.mapValues { it.value.encodeToByteArray() }
        private val streams = mutableMapOf<String, Int>()
        fun streamCount(name: String): Int = streams[name] ?: 0
        override fun has(name: String): Boolean = data.containsKey(name)
        override fun bytes(name: String): ByteArray? = data[name]?.copyOf()
        override fun stream(name: String): InputStream? =
            data[name]?.let {
                streams[name] = streamCount(name) + 1
                ByteArrayInputStream(it)
            }
    }

    private fun indexJson(vararg items: Pair<Pair<String, Long>, List<String>>): String {
        val index = NativeConversationIndex(
            items.map { (head, media) ->
                NativeConversationIndexEntry(
                    id = head.first,
                    dataChangedAt = head.second,
                    entry = NativeBackupFormat.conversationEntry(head.first),
                    mediaEntries = media,
                )
            },
        )
        return Json.encodeToString(index)
    }

    private fun expectFailure(
        expected: Class<out Exception>,
        cacheDir: File,
        block: () -> Any?,
    ) {
        val error = try {
            block()
            null
        } catch (thrown: Throwable) {
            thrown
        } ?: throw AssertionError("Expected ${expected.simpleName}")
        assertTrue(
            "Expected ${expected.simpleName} but got ${error::class.java.name}",
            expected.isInstance(error),
        )
        assertEquals(emptyList<String>(), spoolNames(cacheDir))
    }

    private fun spoolNames(cacheDir: File): List<String> =
        (cacheDir.listFiles { file -> file.name.startsWith("agora-import-v5-") })
            .orEmpty().map { it.name }.sorted()
}
