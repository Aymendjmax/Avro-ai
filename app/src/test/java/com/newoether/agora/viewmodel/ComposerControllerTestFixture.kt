package com.newoether.agora.viewmodel

import com.newoether.agora.data.repository.ConversationRepository
import com.newoether.agora.model.AttachmentImportState
import com.newoether.agora.model.SelectedAttachment
import io.mockk.mockk
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.test.TestScope
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Rule
import org.junit.rules.TemporaryFolder

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
internal abstract class ComposerControllerTestFixture {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    protected fun TestScope.fixture(
        processor: AttachmentImportProcessor,
        initial: Map<String, ConversationWorkspaceDraft> = emptyMap(),
    ): Fixture {
        val persistence = MemoryDraftPersistence(initial)
        val repository = repository()
        return Fixture(
            controller = controller(
                processor = processor,
                persistence = persistence,
                repository = repository,
            ),
            persistence = persistence,
            repository = repository,
        )
    }

    protected fun TestScope.controller(
        processor: AttachmentImportProcessor,
        persistence: MemoryDraftPersistence,
        repository: ConversationRepository,
    ): ConversationComposerController {
        val drafts = ComposerDraftController(
            persistence = persistence,
            conversations = repository,
        )
        return ConversationComposerController(
            scope = backgroundScope,
            drafts = drafts,
            processor = processor,
        )
    }

    protected fun repository(): ConversationRepository =
        mockk(relaxed = true)

    protected suspend fun Fixture.state(ownerId: String, attachmentId: String): SelectedAttachment =
        controller.state(ownerId).value.attachments.single { it.localId == attachmentId }

    protected fun ConversationComposerSnapshot.single(): SelectedAttachment = attachments.single()

    protected fun ConversationComposerSnapshot.ids(): List<String> = attachments.map { it.localId }

    protected fun attachment(id: String) = SelectedAttachment(
        localId = id,
        uri = "content://source/$id",
        type = "image",
        fileName = "$id.jpg",
        importState = AttachmentImportState.READY,
    )

    protected fun SelectedAttachment.processing(path: String) = copy(
        localPath = path,
        importState = AttachmentImportState.PROCESSING,
    )

    protected fun SelectedAttachment.ready(path: String = localPath.orEmpty()) = copy(
        localPath = path,
        importState = AttachmentImportState.READY,
    )

    protected fun draft(
        text: String = "",
        vararg attachments: SelectedAttachment,
    ) = ConversationWorkspaceDraft(
        text = text,
        attachmentsJson = attachments.takeIf { it.isNotEmpty() }
            ?.let { Json.encodeToString(it.toList()) },
    )

    protected data class Fixture(
        val controller: ConversationComposerController,
        val persistence: MemoryDraftPersistence,
        val repository: ConversationRepository,
    )

    protected class MemoryDraftPersistence(
        initial: Map<String, ConversationWorkspaceDraft>,
    ) : ComposerDraftPersistence {
        private val drafts = ConcurrentHashMap(initial)
        private val loads = ConcurrentHashMap<String, AtomicInteger>()
        private val updates = mutableListOf<Pair<String, ConversationWorkspaceDraft>>()
        var failLoads = false
        var failWrites = false

        override suspend fun loadDraft(ownerId: String): ConversationWorkspaceDraft {
            loads.computeIfAbsent(ownerId) { AtomicInteger() }.incrementAndGet()
            if (failLoads) throw IllegalStateException("draft read failed")
            return drafts[ownerId] ?: ConversationWorkspaceDraft("", null)
        }

        override suspend fun updateDraft(
            ownerId: String,
            text: String,
            attachmentsJson: String?,
        ) {
            if (failWrites) throw IllegalStateException("draft write failed")
            val value = ConversationWorkspaceDraft(text, attachmentsJson)
            drafts[ownerId] = value
            synchronized(updates) { updates += ownerId to value }
        }

        override suspend fun clearAcceptedDraft(ownerId: String) {
            drafts[ownerId] = ConversationWorkspaceDraft("", null)
        }

        fun attachments(ownerId: String): List<SelectedAttachment> = drafts[ownerId]
            ?.attachmentsJson
            ?.let { Json.decodeFromString(it) }
            ?: emptyList()

        fun text(ownerId: String): String = drafts[ownerId]?.text.orEmpty()

        fun loadCount(ownerId: String): Int = loads[ownerId]?.get() ?: 0

        fun setDraft(ownerId: String, draft: ConversationWorkspaceDraft) {
            drafts[ownerId] = draft
        }

        fun attachment(ownerId: String): SelectedAttachment = attachments(ownerId).single()

        fun updatedStates(ownerId: String): List<AttachmentImportState> = synchronized(updates) {
            updates.filter { it.first == ownerId }.mapNotNull { (_, draft) ->
                draft.attachmentsJson
                    ?.let { Json.decodeFromString<List<SelectedAttachment>>(it) }
                    ?.singleOrNull()
                    ?.importState
            }
        }
    }

    protected companion object {
        const val OWNER_A = "conversation-a"
        const val OWNER_B = "conversation-b"
    }
}
