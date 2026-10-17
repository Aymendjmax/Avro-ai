package com.newoether.agora.data

import android.content.Context
import android.os.Process
import androidx.room.Transactor
import androidx.room.useReaderConnection
import com.newoether.agora.data.local.ChatDatabase
import com.newoether.agora.data.local.ChatEntity
import com.newoether.agora.data.local.LoopEntity
import com.newoether.agora.data.local.MessageEntity
import com.newoether.agora.data.local.RunEntity
import com.newoether.agora.data.local.TaskEntity
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/** One record of the export snapshot, emitted in archive-relevant order. */
internal sealed interface SnapshotRecord {
    data class Conversation(val entity: ChatEntity) : SnapshotRecord
    data class Run(val entity: RunEntity) : SnapshotRecord
    data class Message(val entity: MessageEntity) : SnapshotRecord
    data class Loop(val entity: LoopEntity) : SnapshotRecord
    data class Task(val entity: TaskEntity) : SnapshotRecord
}

/**
 * Streams one point-in-time conversation graph from an independent Room connection pool.
 * Each conversation emits its header, runs, paged messages, and loops in order, followed by all
 * tasks. Message pages are delivered without accumulating a per-conversation list, so snapshot
 * memory stays bounded by one page instead of the largest conversation.
 */
internal class ConversationExportSnapshotReader(
    private val context: Context,
) {
    companion object {
        private const val MESSAGE_PAGE_SIZE = 64
        private const val SNAPSHOT_THREAD_COUNT = 2
        private val snapshotThreadSequence = AtomicInteger()
    }

    /**
     * [includeBody] decides per conversation whether its runs, messages and loops are read; when it
     * returns false only the conversation header is emitted.
     */
    suspend fun readSnapshot(
        includeBody: suspend (ChatEntity) -> Boolean = { true },
        onRecord: suspend (SnapshotRecord) -> Unit,
    ) {
        val snapshotExecutor = Executors.newFixedThreadPool(SNAPSHOT_THREAD_COUNT) { runnable ->
            Thread(
                {
                    Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND)
                    runnable.run()
                },
                "agora-export-db-${snapshotThreadSequence.incrementAndGet()}",
            )
        }
        val snapshotDatabase = ChatDatabase.build(
            context,
            queryExecutor = snapshotExecutor,
            transactionExecutor = snapshotExecutor,
        )
        try {
            val snapshotDao = snapshotDatabase.chatDao()
            snapshotDatabase.useReaderConnection { connection ->
                connection.withTransaction(Transactor.SQLiteTransactionType.DEFERRED) {
                    for (conversation in snapshotDao.getAllConversationsList()) {
                        currentCoroutineContext().ensureActive()
                        onRecord(SnapshotRecord.Conversation(conversation))
                        if (!includeBody(conversation)) continue
                        for (run in snapshotDao.getRunsForConversationSnapshot(conversation.id)) {
                            currentCoroutineContext().ensureActive()
                            onRecord(SnapshotRecord.Run(run))
                        }
                        var afterMessageId: String? = null
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val page = snapshotDao.getConversationMessagesPage(
                                conversation.id,
                                afterMessageId,
                                MESSAGE_PAGE_SIZE,
                            )
                            if (page.isEmpty()) break
                            for (message in page) {
                                currentCoroutineContext().ensureActive()
                                onRecord(SnapshotRecord.Message(message))
                            }
                            afterMessageId = page.last().id
                            if (page.size < MESSAGE_PAGE_SIZE) break
                        }
                        for (loop in snapshotDao.getLoopsForConversationSnapshot(conversation.id)) {
                            currentCoroutineContext().ensureActive()
                            onRecord(SnapshotRecord.Loop(loop))
                        }
                    }
                    for (task in snapshotDao.getAllTasksList()) {
                        currentCoroutineContext().ensureActive()
                        onRecord(SnapshotRecord.Task(task))
                    }
                }
            }
        } finally {
            snapshotDatabase.close()
            snapshotExecutor.shutdown()
        }
    }
}
