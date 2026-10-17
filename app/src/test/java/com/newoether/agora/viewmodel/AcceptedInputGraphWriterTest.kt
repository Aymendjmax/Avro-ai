package com.newoether.agora.viewmodel

import com.newoether.agora.data.ConversationSettings
import com.newoether.agora.data.local.MessageContextTopology
import com.newoether.agora.data.local.MessageEntity
import com.newoether.agora.data.local.NewChatPersistEntity
import com.newoether.agora.data.local.ProviderContextTopologySnapshot
import com.newoether.agora.data.local.RunEntity
import com.newoether.agora.data.local.RunGraphCommit
import com.newoether.agora.data.repository.ConversationRepository
import com.newoether.agora.model.MessageStatus
import com.newoether.agora.model.Participant
import com.newoether.agora.model.RunEffect
import com.newoether.agora.model.RunEffectIdentity
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AcceptedInputGraphWriterTest {
    @Test
    fun commit_usesTheSelectedLeafAndCreatesTheWholeRunBoundaryAtomically() = runTest {
        val repository = mockk<ConversationRepository>()
        val root = message("root", null, Participant.USER, 1L, "old-run")
        val selected = message("selected", "root", Participant.MODEL, 2L, "selected-run")
        val newerSibling = message("newer", "root", Participant.MODEL, 3L, "other-run")
        coEvery {
            repository.getProviderContextTopologySnapshot("conversation")
        } returns ProviderContextTopologySnapshot(
            selectedBranchesJson = """{"root":"selected"}""",
            messages = listOf(root, selected, newerSibling).map { it.toTopology() },
        )

        lateinit var insertedRun: RunEntity
        lateinit var insertedMessages: List<MessageEntity>
        lateinit var insertedSelections: Map<String?, String>
        lateinit var insertedConversationModelId: String
        var insertedAt = -1L
        var insertedTouchPolicy = true
        coEvery {
            repository.createRunWithMessages(any(), any(), any(), any(), any(), any())
        } coAnswers {
            insertedRun = firstArg()
            insertedMessages = secondArg()
            insertedSelections = thirdArg()
            insertedConversationModelId = arg(3)
            insertedAt = arg(4)
            insertedTouchPolicy = arg(5)
            RunGraphCommit(insertedMessages, insertedSelections, emptyMap())
        }

        var beforeCommitCalled = false
        val result = AcceptedInputGraphWriter(repository).commit(
            request = AcceptedInputGraphWriter.Request(
                inputEffect = inputEffect("conversation", "new-run"),
                userMessageId = "new-user",
                modelMessageId = "new-model",
                userText = "prompt",
                modelId = "OpenAI:model",
                userTimestamp = 100L,
                touchConversationOnAdmission = false,
                source = com.newoether.agora.model.MessageSource.TASK,
            ),
            beforeRoomCommit = { beforeCommitCalled = true },
        )

        assertEquals("""{"kind":"task"}""", result.userMessage.sourceJson)
        assertNull(result.modelMessage.sourceJson)

        assertEquals("selected-run", insertedRun.parentRunId)
        assertEquals("selected", result.userMessage.parentId)
        assertEquals("new-user", result.modelMessage.parentId)
        assertEquals(listOf("new-user", "new-model"), insertedMessages.map { it.id })
        assertEquals("new-user", insertedSelections["selected"])
        assertEquals("new-model", insertedSelections["new-user"])
        assertEquals("OpenAI:model", insertedConversationModelId)
        assertEquals(100L, insertedAt)
        assertEquals(false, insertedTouchPolicy)
        assertEquals(insertedSelections, result.messageSelections)
        assertEquals(true, beforeCommitCalled)
    }

    @Test
    fun newConversation_startsAtTheRootWithoutReadingAStaleGraph() = runTest {
        val repository = mockk<ConversationRepository>()
        val capturedSettings = ConversationSettings(
            temperature = 0.3f,
            maxTokens = 640,
            lowContextModeEnabled = true,
        )
        var insertedSettingsJson: String? = null
        var insertedPersistSnapshot: NewChatPersistEntity? = null
        coEvery {
            repository.createConversationRunWithMessages(
                any(), any(), any(), any(), any(), any(), any(), any(),
            )
        } coAnswers {
            val messages = thirdArg<List<MessageEntity>>()
            val selections = arg<Map<String?, String>>(3)
            insertedSettingsJson = arg(5)
            insertedPersistSnapshot = arg(6)
            RunGraphCommit(messages, selections, emptyMap())
        }

        val persistSnapshot = NewChatPersistEntity(
            modelId = "OpenAI:model",
            draftText = "prompt",
        )
        val result = AcceptedInputGraphWriter(repository).commit(
            AcceptedInputGraphWriter.Request(
                inputEffect = inputEffect("conversation", "run"),
                userMessageId = "user",
                modelMessageId = "model",
                userText = "prompt",
                modelId = "OpenAI:model",
                userTimestamp = 100L,
                touchConversationOnAdmission = true,
                newConversation = com.newoether.agora.data.local.ChatEntity(
                    id = "conversation",
                    title = "New",
                ),
                newConversationSettings = capturedSettings,
                newChatPersistSnapshot = persistSnapshot,
            )
        )

        assertNull(result.userMessage.parentId)
        // Typed input carries no automatic source.
        assertNull(result.userMessage.sourceJson)
        assertEquals("model", result.messageSelections["user"])
        assertEquals(
            capturedSettings,
            Json.decodeFromString<ConversationSettings>(checkNotNull(insertedSettingsJson)),
        )
        assertEquals(persistSnapshot, insertedPersistSnapshot)
    }

    private fun message(
        id: String,
        parentId: String?,
        participant: Participant,
        timestamp: Long,
        runId: String,
    ) = MessageEntity(
        id = id,
        conversationId = "conversation",
        parentId = parentId,
        text = id,
        status = MessageStatus.SUCCESS,
        participant = participant,
        timestamp = timestamp,
        runId = runId,
        runSequence = timestamp,
    )

    private fun MessageEntity.toTopology() = MessageContextTopology(
        id = id,
        conversationId = conversationId,
        parentId = parentId,
        status = status,
        participant = participant,
        timestamp = timestamp,
        modelName = modelName,
        runId = runId,
        runSequence = runSequence,
        consumedAtPass = consumedAtPass,
    )

    private fun inputEffect(conversationId: String, runId: String) =
        RunEffect.PersistAcceptedInput(
            RunEffectIdentity(
                conversationId = conversationId,
                ownerToken = 1L,
                runId = runId,
                pass = 0,
                effectId = "send-$runId",
            )
        )
}
