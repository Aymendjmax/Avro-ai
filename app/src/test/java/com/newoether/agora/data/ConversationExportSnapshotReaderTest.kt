package com.newoether.agora.data

import android.content.Context
import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
import com.newoether.agora.data.local.ChatDatabase
import com.newoether.agora.data.local.ChatEntity
import com.newoether.agora.data.local.MessageEntity
import com.newoether.agora.data.local.RunEntity
import com.newoether.agora.model.MessageStatus
import com.newoether.agora.model.Participant
import com.newoether.agora.model.RunStatus
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ConversationExportSnapshotReaderTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var processDatabase: ChatDatabase

    @Before
    fun setUp() {
        context.deleteDatabase(ChatDatabase.DB_NAME)
        processDatabase = ChatDatabase.build(context)
    }

    @After
    fun tearDown() {
        processDatabase.close()
        context.deleteDatabase(ChatDatabase.DB_NAME)
    }

    @Test
    fun activeCheckpointCanCommitWithoutEnteringTheExportSnapshot() = runBlocking {
        val initialRun = activeRun(lastCheckpointAt = 100L)
        val initialMessage = checkpointMessage(text = "checkpoint-before")
        withContext(Dispatchers.IO) {
            processDatabase.withTransaction {
                processDatabase.chatDao().upsertConversation(conversation())
                processDatabase.chatDao().upsertRun(initialRun)
                processDatabase.chatDao().upsertMessage(initialMessage)
            }
        }
        assertTrue(processDatabase.openHelper.writableDatabase.isWriteAheadLoggingEnabled)

        val snapshotEstablished = CompletableDeferred<Unit>()
        val continueSnapshot = CompletableDeferred<Unit>()
        val exportedRuns = mutableListOf<RunEntity>()
        val exportedMessages = mutableListOf<MessageEntity>()
        val export = async(Dispatchers.IO) {
            ConversationExportSnapshotReader(context).readSnapshot { record ->
                when (record) {
                    is SnapshotRecord.Run -> {
                        snapshotEstablished.complete(Unit)
                        continueSnapshot.await()
                        exportedRuns += record.entity
                    }
                    is SnapshotRecord.Message -> exportedMessages += record.entity
                    else -> Unit
                }
            }
        }

        snapshotEstablished.await()
        withTimeout(5_000L) {
            withContext(Dispatchers.IO) {
                processDatabase.withTransaction {
                    processDatabase.chatDao().upsertRun(initialRun.copy(lastCheckpointAt = 200L))
                    processDatabase.chatDao().upsertMessage(
                        initialMessage.copy(text = "checkpoint-after"),
                    )
                }
            }
        }
        continueSnapshot.complete(Unit)
        export.await()

        assertEquals(100L, exportedRuns.single().lastCheckpointAt)
        assertEquals("checkpoint-before", exportedMessages.single().text)
        assertEquals(
            "checkpoint-after",
            withContext(Dispatchers.IO) {
                processDatabase.chatDao().getMessagesByIds(listOf(MESSAGE_ID)).single().text
            },
        )
    }

    @Test
    fun skippedConversationEmitsOnlyItsHeader() = runBlocking {
        withContext(Dispatchers.IO) {
            processDatabase.withTransaction {
                processDatabase.chatDao().upsertConversation(conversation())
                processDatabase.chatDao().upsertRun(activeRun(lastCheckpointAt = 100L))
                processDatabase.chatDao().upsertMessage(checkpointMessage(text = "body"))
            }
        }
        val records = mutableListOf<SnapshotRecord>()
        withContext(Dispatchers.IO) {
            ConversationExportSnapshotReader(context).readSnapshot(
                includeBody = { it.id != CONVERSATION_ID },
            ) { records += it }
        }
        assertEquals(listOf(CONVERSATION_ID), records.map { (it as SnapshotRecord.Conversation).entity.id })
    }
    private fun conversation() = ChatEntity(
        id = CONVERSATION_ID,
        title = "Conversation",
    )

    private fun activeRun(lastCheckpointAt: Long) = RunEntity(
        id = RUN_ID,
        conversationId = CONVERSATION_ID,
        parentRunId = null,
        status = RunStatus.ACTIVE,
        activeSlot = 1,
        startedAt = 50L,
        lastCheckpointAt = lastCheckpointAt,
    )

    private fun checkpointMessage(text: String) = MessageEntity(
        id = MESSAGE_ID,
        conversationId = CONVERSATION_ID,
        text = text,
        status = MessageStatus.SENDING,
        participant = Participant.MODEL,
        timestamp = 50L,
        runId = RUN_ID,
        runSequence = 0L,
    )

    private companion object {
        const val CONVERSATION_ID = "conversation"
        const val RUN_ID = "run"
        const val MESSAGE_ID = "message"
    }
}
