package com.newoether.agora.data.repository

import com.newoether.agora.data.ConversationSettings
import com.newoether.agora.data.local.ConversationSettingsImportTransferEntity
import com.newoether.agora.data.local.ConversationSettingsTransferEntity
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConversationSettingsTransferCoordinatorTest {
    @Test
    fun pendingImportCanBeCompletedBeforeAnotherNativeImportStarts() = runTest {
        val fixture = Fixture()
        val imported = mapOf("conversation" to ConversationSettings(temperature = 0.25f))
        fixture.pendingImport = importTransfer("transfer", imported, replace = false)

        assertTrue(fixture.coordinator.completePendingImport())
        assertFalse(fixture.coordinator.completePendingImport())

        assertEquals(listOf(imported to false), fixture.importWrites)
        assertEquals(null, fixture.pendingImport)
    }

    @Test
    fun importMergePersistsMapBeforeDeletingTheOutbox() = runTest {
        val fixture = Fixture()
        val imported = mapOf("conversation" to ConversationSettings(temperature = 0.25f))
        fixture.pendingImport = importTransfer("transfer", imported, replace = false)

        assertTrue(fixture.coordinator.completeImport("transfer"))

        assertEquals(listOf(imported to false), fixture.importWrites)
        assertEquals(
            listOf("await-load", "read-import", "write-import:merge", "delete-import:transfer"),
            fixture.events,
        )
        assertEquals(null, fixture.pendingImport)
    }

    @Test
    fun emptyImportReplacePersistsAnEmptyMap() = runTest {
        val fixture = Fixture()
        fixture.pendingImport = importTransfer("transfer", emptyMap(), replace = true)

        assertTrue(fixture.coordinator.completeImport("transfer"))

        assertEquals(listOf(emptyMap<String, ConversationSettings>() to true), fixture.importWrites)
        assertEquals(null, fixture.pendingImport)
    }

    @Test
    fun importDataStoreFailurePreservesTheOutbox() = runTest {
        val fixture = Fixture()
        fixture.pendingImport = importTransfer(
            "transfer",
            mapOf("conversation" to ConversationSettings()),
            replace = true,
        )
        coEvery {
            fixture.settings.applyConversationSettingsImportAndAwait(any(), true)
        } throws IllegalStateException("DataStore unavailable")

        val failure = runCatching { fixture.coordinator.completeImport("transfer") }.exceptionOrNull()

        assertTrue(failure is IllegalStateException)
        assertEquals("transfer", fixture.pendingImport?.transferId)
        coVerify(exactly = 0) {
            fixture.conversations.deleteConversationSettingsImportTransfer(any())
        }
    }

    @Test
    fun replayCompletesImportBeforeNewChatTransfers() = runTest {
        val fixture = Fixture()
        val imported = mapOf("imported" to ConversationSettings(temperature = 0.4f))
        fixture.pendingImport = importTransfer("transfer", imported, replace = true)
        fixture.pending["new-chat"] = transfer(
            "new-chat",
            ConversationSettings(temperature = 0.8f),
        )

        assertEquals(2, fixture.coordinator.replayPending())

        assertTrue(
            fixture.events.indexOf("delete-import:transfer") <
                fixture.events.indexOf("write:new-chat"),
        )
        assertEquals(null, fixture.pendingImport)
        assertTrue(fixture.pending.isEmpty())
    }

    @Test
    fun staleImportCompletionCannotDeleteANewerOutbox() = runTest {
        val fixture = Fixture()
        fixture.pendingImport = importTransfer("new-transfer", emptyMap(), replace = true)

        assertFalse(fixture.coordinator.completeImport("old-transfer"))

        assertEquals("new-transfer", fixture.pendingImport?.transferId)
        coVerify(exactly = 0) {
            fixture.settings.applyConversationSettingsImportAndAwait(any(), any())
        }
    }

    @Test
    fun completePersistsDataStoreBeforeDeletingTheOutbox() = runTest {
        val fixture = Fixture()
        val settings = ConversationSettings(temperature = 0.25f, maxTokens = 512)
        fixture.pending["conversation"] = transfer("conversation", settings)

        assertTrue(fixture.coordinator.complete("conversation"))

        assertEquals(
            listOf("await-load", "read:conversation", "write:conversation", "delete:conversation"),
            fixture.events,
        )
        assertEquals(settings, fixture.writes.single().second)
    }

    @Test
    fun dataStoreFailurePreservesTheOutbox() = runTest {
        val fixture = Fixture()
        fixture.pending["conversation"] = transfer("conversation", ConversationSettings())
        coEvery {
            fixture.settings.setConversationSettingsAndAwait("conversation", any())
        } throws IllegalStateException("DataStore unavailable")

        val failure = runCatching { fixture.coordinator.complete("conversation") }.exceptionOrNull()

        assertTrue(failure is IllegalStateException)
        assertTrue(fixture.pending.containsKey("conversation"))
        coVerify(exactly = 0) {
            fixture.conversations.deleteConversationSettingsTransfer(any())
        }
    }

    @Test
    fun replayCompletesEveryPendingTransferInOrder() = runTest {
        val fixture = Fixture()
        val first = ConversationSettings(temperature = 0.1f)
        val second = ConversationSettings(temperature = 0.8f)
        fixture.pending["a"] = transfer("a", first)
        fixture.pending["b"] = transfer("b", second)

        assertEquals(2, fixture.coordinator.replayPending())

        assertEquals(listOf("a" to first, "b" to second), fixture.writes)
        assertTrue(fixture.pending.isEmpty())
    }

    @Test
    fun replayIsolatesFailedRowsAndContinuesWithLaterTransfers() = runTest {
        val fixture = Fixture()
        val failed = ConversationSettings(temperature = 0.1f)
        val completed = ConversationSettings(temperature = 0.8f)
        fixture.pending["a"] = transfer("a", failed)
        fixture.pending["b"] = transfer("b", completed)
        coEvery {
            fixture.settings.setConversationSettingsAndAwait("a", any())
        } throws IllegalStateException("DataStore unavailable")

        assertEquals(1, fixture.coordinator.replayPending())

        assertTrue(fixture.pending.containsKey("a"))
        assertFalse(fixture.pending.containsKey("b"))
        assertEquals(listOf("b" to completed), fixture.writes)
        coVerify(exactly = 0) {
            fixture.conversations.deleteConversationSettingsTransfer("a")
        }
    }

    @Test
    fun replayLoadFailureDoesNotBlockProcessStartup() = runTest {
        val fixture = Fixture()
        fixture.pending["conversation"] = transfer("conversation", ConversationSettings())
        coEvery { fixture.settings.awaitInitialLoad() } throws
            IllegalStateException("DataStore unavailable")

        assertEquals(0, fixture.coordinator.replayPending())

        assertTrue(fixture.pending.containsKey("conversation"))
        coVerify(exactly = 0) {
            fixture.conversations.getPendingConversationSettingsTransfers()
        }
    }

    @Test
    fun nullablePayloadTransfersInheritedDefaults() = runTest {
        val fixture = Fixture()
        fixture.pending["conversation"] = ConversationSettingsTransferEntity("conversation", null)

        assertTrue(fixture.coordinator.complete("conversation"))

        assertEquals(listOf("conversation" to null), fixture.writes)
        assertFalse(fixture.pending.containsKey("conversation"))
    }

    @Test
    fun repeatedCompletionIsIdempotentAfterOutboxDeletion() = runTest {
        val fixture = Fixture()
        fixture.pending["conversation"] = transfer("conversation", ConversationSettings())

        assertTrue(fixture.coordinator.complete("conversation"))
        assertFalse(fixture.coordinator.complete("conversation"))

        assertEquals(1, fixture.writes.size)
        coVerify(exactly = 1) {
            fixture.conversations.deleteConversationSettingsTransfer("conversation")
        }
    }

    private class Fixture {
        val conversations = mockk<ConversationRepository>()
        val settings = mockk<SettingsRepository>()
        val pending = linkedMapOf<String, ConversationSettingsTransferEntity>()
        var pendingImport: ConversationSettingsImportTransferEntity? = null
        val writes = mutableListOf<Pair<String, ConversationSettings?>>()
        val importWrites = mutableListOf<Pair<Map<String, ConversationSettings>, Boolean>>()
        val events = mutableListOf<String>()
        val coordinator = ConversationSettingsTransferCoordinator(conversations, settings)

        init {
            coEvery { settings.awaitInitialLoad() } coAnswers {
                events += "await-load"
            }
            coEvery { conversations.getConversationSettingsTransfer(any()) } coAnswers {
                firstArg<String>().let { conversationId ->
                    events += "read:$conversationId"
                    pending[conversationId]
                }
            }
            coEvery { conversations.getPendingConversationSettingsTransfers() } coAnswers {
                pending.values.toList()
            }
            coEvery { conversations.getConversationSettingsImportTransfer() } coAnswers {
                events += "read-import"
                pendingImport
            }
            coEvery {
                settings.applyConversationSettingsImportAndAwait(any(), any())
            } coAnswers {
                val imported = firstArg<Map<String, ConversationSettings>>()
                val replace = secondArg<Boolean>()
                events += "write-import:${if (replace) "replace" else "merge"}"
                importWrites += imported to replace
            }
            coEvery {
                conversations.deleteConversationSettingsImportTransfer(any())
            } coAnswers {
                val transferId = firstArg<String>()
                events += "delete-import:$transferId"
                if (pendingImport?.transferId == transferId) {
                    pendingImport = null
                    true
                } else {
                    false
                }
            }
            coEvery { settings.setConversationSettingsAndAwait(any(), any()) } coAnswers {
                val conversationId = firstArg<String>()
                events += "write:$conversationId"
                writes += conversationId to secondArg<ConversationSettings?>()
            }
            coEvery { conversations.deleteConversationSettingsTransfer(any()) } coAnswers {
                val conversationId = firstArg<String>()
                events += "delete:$conversationId"
                pending.remove(conversationId) != null
            }
        }
    }

    private companion object {
        fun importTransfer(
            transferId: String,
            settings: Map<String, ConversationSettings>,
            replace: Boolean,
        ) = ConversationSettingsImportTransferEntity(
            transferId = transferId,
            settingsJson = Json.encodeToString(settings),
            mode = if (replace) {
                ConversationSettingsImportTransferEntity.MODE_REPLACE
            } else {
                ConversationSettingsImportTransferEntity.MODE_MERGE
            },
        )

        fun transfer(
            conversationId: String,
            settings: ConversationSettings,
        ) = ConversationSettingsTransferEntity(
            conversationId = conversationId,
            settingsJson = Json.encodeToString(settings),
        )
    }
}
