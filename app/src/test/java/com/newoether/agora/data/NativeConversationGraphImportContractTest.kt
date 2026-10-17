package com.newoether.agora.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.newoether.agora.automation.LoopPolicy
import com.newoether.agora.data.DataImporter.ImportStrategy
import com.newoether.agora.data.local.ChatDao
import com.newoether.agora.data.local.ChatDatabase
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import java.io.ByteArrayInputStream
import java.io.InputStream
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class NativeConversationGraphImportContractTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    private val importJson = Json { ignoreUnknownKeys = true }

    private val convOne = """{"id":"c1","title":"One","lastUpdated":11}"""
    private val convTwo = """{"id":"c2","title":"Two","lastUpdated":22}"""
    private val runOne = """{"id":"r1","conversationId":"c1","startedAt":5,""" +
        """"lastCheckpointAt":6,"endedAt":6,"status":"COMPLETED"}"""
    private val messageOne = """{"id":"m1","conversationId":"c1","text":"hello","timestamp":7}"""
    private val messageTwo = """{"id":"m2","conversationId":"c2","text":"deux","timestamp":8}"""
    private val loopTwo = """{"conversationId":"c2","intervalMs":${LoopPolicy.MIN_INTERVAL_MS},""" +
        """"prompt":"tick","cycleCount":0,"maxCycles":3,"active":true,"revision":1}"""
    private val loopGhost = """{"conversationId":"ghost","intervalMs":${LoopPolicy.MIN_INTERVAL_MS},""" +
        """"prompt":"tick","cycleCount":0,"maxCycles":3,"active":true,"revision":1}"""
    private val taskOne = """{"id":"t1","name":"Task","prompt":"P","cronExpr":"","createdAt":1}"""

    private class FakeEntries(entries: Map<String, String>) : NativeGraphEntrySource {
        private val data = entries.mapValues { it.value.toByteArray() }
        override fun has(name: String): Boolean = data.containsKey(name)
        override fun bytes(name: String): ByteArray? = data[name]?.copyOf()
        override fun stream(name: String): InputStream? = data[name]?.let(::ByteArrayInputStream)
    }

    private fun legacySource(): FakeEntries = FakeEntries(
        mapOf(
            NativeBackupFormat.CONVERSATIONS_ENTRY to
                """{"conversations":[$convOne,$convTwo],"runs":[$runOne],""" +
                    """"messages":[$messageOne,$messageTwo],"loops":[$loopTwo,$loopGhost],""" +
                    """"tasks":[$taskOne]}""",
        ),
    )

    private fun v5Source(): FakeEntries {
        fun item(id: String, body: String) =
            """{"conversations":[${if (id == "c1") convOne else convTwo}],$body}"""
        val index = """{"conversations":[""" +
            """{"id":"c1","dataChangedAt":11,"entry":"${NativeBackupFormat.conversationEntry("c1")}","mediaEntries":[]},""" +
            """{"id":"c2","dataChangedAt":22,"entry":"${NativeBackupFormat.conversationEntry("c2")}","mediaEntries":[]}""" +
            """]}"""
        return FakeEntries(
            mapOf(
                NativeBackupFormat.CONVERSATION_INDEX_ENTRY to index,
                NativeBackupFormat.conversationEntry("c1") to
                    item("c1", """"runs":[$runOne],"messages":[$messageOne],"loops":[]"""),
                NativeBackupFormat.conversationEntry("c2") to
                    item("c2", """"runs":[],"messages":[$messageTwo],"loops":[$loopTwo,$loopGhost]"""),
                NativeBackupFormat.TASKS_ENTRY to """{"tasks":[$taskOne]}""",
            ),
        )
    }

    private fun importer(chatDao: ChatDao): NativeConversationGraphImporter {
        val context = ApplicationProvider.getApplicationContext<Context>()
        return NativeConversationGraphImporter(
            database = mockk<ChatDatabase>(),
            chatDao = chatDao,
            importJson = importJson,
            mediaRestorer = NativeConversationMediaRestorer(context, importJson),
            scheduleMaintenance = {},
        )
    }

    private fun emptyRestoredMedia() = NativeConversationMediaRestorer.RestoredMedia(
        archiveFiles = emptyMap(),
        legacyImagesByMessage = emptyMap(),
        legacyVideosByMessage = emptyMap(),
        createdFiles = emptyList(),
    )

    private suspend fun headersFor(
        source: FakeEntries,
        version: Int,
        strategy: ImportStrategy,
        chatDao: ChatDao = mockk(),
        existingConversationIds: List<String> = emptyList(),
        existingTaskIds: List<String> = emptyList(),
    ): NativeConversationGraphImporter.ConversationGraphHeaders {
        coEvery { chatDao.getAllConversationIds() } returns existingConversationIds
        coEvery { chatDao.getAllTaskIds() } returns existingTaskIds
        val cacheDir = tempFolder.newFolder("cache-$version-${System.nanoTime()}")
        val graphImporter = importer(chatDao)
        return NativeConversationGraphSource.open(source, version, cacheDir).use { graphSource ->
            graphSource.open().use { stream ->
                graphImporter.readConversationGraphHeaders(
                    stream = stream,
                    strategy = strategy,
                    restoredMedia = emptyRestoredMedia(),
                    resolveSystemPromptId = { it },
                )
            }
        }
    }

    @Test
    fun previewCountsTasksFromTasksEntryAndLegacyFromConversationsJson() {
        val graphImporter = importer(mockk())
        val tasks = v5Source().stream(NativeBackupFormat.TASKS_ENTRY)!!.use {
            graphImporter.countConversationGraph(it)
        }
        assertEquals(1, tasks.tasks)
        assertEquals(0, tasks.conversations)
        val legacy = legacySource().stream(NativeBackupFormat.CONVERSATIONS_ENTRY)!!.use {
            graphImporter.countConversationGraph(it)
        }
        assertEquals(2, legacy.conversations)
        assertEquals(1, legacy.tasks)
    }
    @Test
    fun v5PreviewCountsMatchMergedGraph() = runTest {
        val cacheDir = tempFolder.newFolder("cache-count")
        NativeConversationGraphSource.open(v5Source(), version = 5, cacheDir = cacheDir).use {
            graphSource ->
            graphSource.open().use { stream ->
                val counts = importer(mockk()).countConversationGraph(stream)
                assertEquals(2, counts.conversations)
                assertEquals(1, counts.tasks)
                // Raw archive counts include the ghost loop; orphan filtering happens at headers.
                assertEquals(2, counts.loops)
            }
        }
        NativeConversationGraphSource.open(legacySource(), version = 4, cacheDir = cacheDir).use {
            graphSource ->
            graphSource.open().use { stream ->
                val legacyCounts = importer(mockk()).countConversationGraph(stream)
                assertEquals(2, legacyCounts.conversations)
                assertEquals(1, legacyCounts.tasks)
                assertEquals(2, legacyCounts.loops)
            }
        }
    }

    @Test
    fun v5HeadersEqualLegacyHeadersForEquivalentGraph() = runTest {
        val legacy = headersFor(legacySource(), version = 4, strategy = ImportStrategy.MERGE)
        val v5 = headersFor(v5Source(), version = 5, strategy = ImportStrategy.MERGE)
        assertEquals(legacy.conversations, v5.conversations)
        assertEquals(listOf("c1", "c2"), v5.conversations.map { it.id })
        assertEquals(legacy.runs, v5.runs)
        assertEquals(legacy.loops, v5.loops)
        assertEquals(listOf("c2"), v5.loops.map { it.conversationId })
        assertEquals(legacy.tasks, v5.tasks)
        assertEquals(legacy.sourceRunIdsWereUnique, v5.sourceRunIdsWereUnique)
        assertEquals(legacy.availableConversationIds, v5.availableConversationIds)
    }

    @Test
    fun pinStateRestoresFromArchiveAndDefaultsToUnpinned() = runTest {
        val pinnedOne = """{"id":"c1","title":"One","lastUpdated":11,"isPinned":true}"""
        val source = FakeEntries(
            mapOf(
                NativeBackupFormat.CONVERSATIONS_ENTRY to
                    """{"conversations":[$pinnedOne,$convTwo],"runs":[],"messages":[],""" +
                        """"loops":[],"tasks":[]}""",
            ),
        )
        val headers = headersFor(source, version = 4, strategy = ImportStrategy.REPLACE)
        assertEquals(
            mapOf("c1" to true, "c2" to false),
            headers.conversations.associate { it.id to it.isPinned },
        )
    }

    @Test
    fun todoRoundTripsThroughNativePayloadAndLegacyArchivesDefaultToEmpty() = runTest {
        val items = listOf(
            com.newoether.agora.model.ConversationTodoItem("Check **literal** text", "completed"),
            com.newoether.agora.model.ConversationTodoItem("Check\nnext step", "in_progress"),
            com.newoether.agora.model.ConversationTodoItem("Pending"),
        )
        val raw = com.newoether.agora.model.ConversationTodo.encode(items)
        val payload = NativeConversationPayload(
            conversations = listOf(NativeExportChatEntity("c1", "One", 11L, todoJson = raw)),
            runs = emptyList(), messages = emptyList(), loops = emptyList(),
        )
        val source = FakeEntries(mapOf(
            NativeBackupFormat.CONVERSATIONS_ENTRY to kotlinx.serialization.json.Json.encodeToString(
                NativeConversationPayload.serializer(), payload,
            ),
        ))
        val restored = headersFor(source, version = 4, strategy = ImportStrategy.REPLACE)
        assertEquals(raw, restored.conversations.single().todoJson)
        assertEquals(items, com.newoether.agora.model.ConversationTodo.decode(restored.conversations.single().todoJson))
        val legacy = headersFor(legacySource(), version = 4, strategy = ImportStrategy.REPLACE)
        assertTrue(legacy.conversations.all { it.todoJson == null })
    }

    @Test
    fun malformedTodoFailsHeadersBeforeAnyGraphWrites() = runTest {
        val chatDao = mockk<ChatDao>()
        val invalid = kotlinx.serialization.json.Json.encodeToString(
            NativeConversationPayload.serializer(),
            NativeConversationPayload(
                listOf(NativeExportChatEntity("c1", "One", 11L,
                    todoJson = """[{"content":"Task","status":"invalid"}]""")),
                emptyList(), emptyList(), emptyList(),
            ),
        )
        val source = FakeEntries(mapOf(NativeBackupFormat.CONVERSATIONS_ENTRY to invalid))
        val error = runCatching { headersFor(source, 4, ImportStrategy.REPLACE, chatDao) }.exceptionOrNull()
        assertTrue(error is IllegalArgumentException)
        coVerify(exactly = 0) { chatDao.upsertConversation(any()) }
    }

    @Test
    fun mergeHeadersIncludeExistingConversations() = runTest {
        val chatDao = mockk<ChatDao>()
        coEvery { chatDao.getAllConversationIds() } returns listOf("old-conv")
        coEvery { chatDao.getAllTaskIds() } returns listOf("old-task")
        val headers = headersFor(
            v5Source(),
            version = 5,
            strategy = ImportStrategy.MERGE,
            chatDao = chatDao,
            existingConversationIds = listOf("old-conv"),
            existingTaskIds = listOf("old-task"),
        )
        assertEquals(setOf("old-conv", "c1", "c2"), headers.availableConversationIds)
    }

    @Test
    fun replaceHeadersIgnoreExistingConversationsAndTasks() = runTest {
        val chatDao = mockk<ChatDao>()
        val headers = headersFor(
            v5Source(),
            version = 5,
            strategy = ImportStrategy.REPLACE,
            chatDao = chatDao,
        )
        assertEquals(setOf("c1", "c2"), headers.availableConversationIds)
        coVerify(exactly = 0) { chatDao.getAllConversationIds() }
        coVerify(exactly = 0) { chatDao.getAllTaskIds() }
    }

    @Test
    fun ghostLoopOnMissingConversationIsDroppedFromBothPaths() = runTest {
        val legacy = headersFor(legacySource(), version = 4, strategy = ImportStrategy.MERGE)
        val v5 = headersFor(v5Source(), version = 5, strategy = ImportStrategy.MERGE)
        assertEquals(1, legacy.loops.size)
        assertEquals(1, v5.loops.size)
        assertTrue(v5.loops.none { it.conversationId == "ghost" })
    }
}
