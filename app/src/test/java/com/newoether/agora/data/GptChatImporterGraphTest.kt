package com.newoether.agora.data

import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GptChatImporterGraphTest {
    private val importer = GptChatImporter()

    @Test
    fun importsEveryForkAndKeepsCurrentBranchSelected() {
        val conversation = conversation(
            currentNode = "answer-b-node",
            mapping = linkedMapOf(
                "answer-b-node" to node("answer-b-node", "answer-b", "question-node", "assistant", "B"),
                "question-node" to node("question-node", "question", "root-node", "user", "Question"),
                "answer-a-node" to node("answer-a-node", "answer-a", "question-node", "assistant", "A"),
                "root-node" to node("root-node", "root", null, "system", "Root"),
            ),
        )

        val preview = importer.preview(listOf(conversation))
        val imported = importer.toImportFormat(listOf(conversation))
        val messages = imported.messages.associateBy { it.id }

        assertEquals(4, preview.totalMessageCount)
        assertEquals(1, preview.userMessageCount)
        assertEquals(2, preview.assistantMessageCount)
        assertEquals(4, preview.conversations.single().messageCount)
        assertEquals(setOf("root", "question", "answer-a", "answer-b"), messages.keys)
        assertEquals("question", messages.getValue("answer-a").parentId)
        assertEquals("question", messages.getValue("answer-b").parentId)
        assertEquals(
            mapOf<String?, String>(
                null to "root",
                "root" to "question",
                "question" to "answer-b",
            ),
            decodeSelections(imported.conversations.single().selectedBranchesJson),
        )
    }

    @Test
    fun ephemeralContentCollapsesOnlyAlongItsOwnBranch() {
        val conversation = conversation(
            currentNode = "answer-b-node",
            mapping = linkedMapOf(
                "root-node" to node("root-node", "root", null, "user", "Question"),
                "thought-a-node" to node(
                    nodeId = "thought-a-node",
                    messageId = "thought-a",
                    parent = "root-node",
                    role = "assistant",
                    text = "",
                    contentType = "thoughts",
                    thoughts = listOf(GptChatImporter.GptThought("Thought A", "A title")),
                ),
                "answer-a-node" to node("answer-a-node", "answer-a", "thought-a-node", "assistant", "Answer A"),
                "blank-node" to node("blank-node", "blank", "root-node", "assistant", ""),
                "thought-b-node" to node(
                    nodeId = "thought-b-node",
                    messageId = "thought-b",
                    parent = "blank-node",
                    role = "assistant",
                    text = "",
                    contentType = "thoughts",
                    thoughts = listOf(GptChatImporter.GptThought("Thought B", "B title")),
                ),
                "answer-b-node" to node("answer-b-node", "answer-b", "thought-b-node", "assistant", "Answer B"),
                "thought-c-node" to node(
                    nodeId = "thought-c-node",
                    messageId = "thought-c",
                    parent = "root-node",
                    role = "assistant",
                    text = "",
                    contentType = "thoughts",
                    thoughts = listOf(GptChatImporter.GptThought("Thought C", "C title")),
                ),
                "blank-answer-c-node" to node(
                    "blank-answer-c-node",
                    "blank-answer-c",
                    "thought-c-node",
                    "assistant",
                    "",
                ),
                "output-d-node" to node(
                    nodeId = "output-d-node",
                    messageId = "output-d",
                    parent = "root-node",
                    role = "tool",
                    text = "",
                    contentType = "execution_output",
                    contentText = "Tool output",
                ),
                "blank-answer-d-node" to node(
                    "blank-answer-d-node",
                    "blank-answer-d",
                    "output-d-node",
                    "assistant",
                    "",
                ),
            ),
        )

        val imported = importer.toImportFormat(listOf(conversation))
        val messages = imported.messages.associateBy { it.id }

        assertEquals(
            setOf("root", "answer-a", "answer-b", "blank-answer-c", "blank-answer-d"),
            messages.keys,
        )
        assertEquals("Thought A", messages.getValue("answer-a").thoughts)
        assertEquals("A title", messages.getValue("answer-a").thoughtTitle)
        assertEquals("Thought B", messages.getValue("answer-b").thoughts)
        assertEquals("B title", messages.getValue("answer-b").thoughtTitle)
        assertEquals("Thought C", messages.getValue("blank-answer-c").thoughts)
        assertEquals("C title", messages.getValue("blank-answer-c").thoughtTitle)
        assertEquals("", messages.getValue("blank-answer-c").text)
        assertEquals("Tool output", messages.getValue("blank-answer-d").text)
        assertFalse(messages.getValue("answer-a").thoughts!!.contains("Thought B"))
        assertFalse(messages.getValue("answer-b").thoughts!!.contains("Thought A"))
        assertEquals("root", messages.getValue("answer-a").parentId)
        assertEquals("root", messages.getValue("answer-b").parentId)
        assertEquals(
            mapOf<String?, String>(null to "root", "root" to "answer-b"),
            decodeSelections(imported.conversations.single().selectedBranchesJson),
        )
    }

    @Test
    fun excludesBrokenAncestryAndImportsDuplicateMessageIdentityOnce() {
        val conversation = conversation(
            currentNode = "valid-child-node",
            mapping = linkedMapOf(
                "valid-root-node" to node("valid-root-node", "valid-root", null, "user", "Valid"),
                "valid-child-node" to node("valid-child-node", "shared", "valid-root-node", "assistant", "Current"),
                "duplicate-node" to node("duplicate-node", "shared", "valid-root-node", "assistant", "Duplicate"),
                "duplicate-child-node" to node(
                    "duplicate-child-node",
                    "duplicate-child",
                    "duplicate-node",
                    "assistant",
                    "Duplicate child",
                ),
                "missing-parent-node" to node("missing-parent-node", "missing", "absent-node", "assistant", "Missing"),
                "missing-child-node" to node("missing-child-node", "missing-child", "missing-parent-node", "assistant", "Missing child"),
                "cycle-a-node" to node("cycle-a-node", "cycle-a", "cycle-b-node", "assistant", "Cycle A"),
                "cycle-b-node" to node("cycle-b-node", "cycle-b", "cycle-a-node", "assistant", "Cycle B"),
            ),
        )

        val preview = importer.preview(listOf(conversation))
        val imported = importer.toImportFormat(listOf(conversation))

        assertEquals(3, preview.totalMessageCount)
        assertEquals(listOf("valid-root", "shared", "duplicate-child"), imported.messages.map { it.id })
        assertEquals("Current", imported.messages.single { it.id == "shared" }.text)
        assertNull(imported.messages.single { it.id == "valid-root" }.parentId)
        assertEquals("valid-root", imported.messages.single { it.id == "duplicate-child" }.parentId)
        assertEquals(
            mapOf<String?, String>(null to "valid-root", "valid-root" to "shared"),
            decodeSelections(imported.conversations.single().selectedBranchesJson),
        )
        assertTrue(imported.messages.none { it.id.startsWith("missing") || it.id.startsWith("cycle") })
    }

    private fun conversation(
        currentNode: String?,
        mapping: Map<String, GptChatImporter.GptMappingNode>,
    ) = GptChatImporter.GptConversation(
        conversationId = "conversation",
        title = "Imported",
        updateTime = 10.0,
        currentNode = currentNode,
        mapping = mapping,
    )

    private fun node(
        nodeId: String,
        messageId: String,
        parent: String?,
        role: String,
        text: String,
        contentType: String = "text",
        thoughts: List<GptChatImporter.GptThought>? = null,
        contentText: String? = null,
    ) = GptChatImporter.GptMappingNode(
        id = nodeId,
        parent = parent,
        message = GptChatImporter.GptMessage(
            id = messageId,
            author = GptChatImporter.GptAuthor(role = role),
            content = GptChatImporter.GptContent(
                contentType = contentType,
                parts = listOf(JsonPrimitive(text)),
                text = contentText,
                thoughts = thoughts,
            ),
            createTime = 1.0,
        ),
    )

    private fun decodeSelections(raw: String?): Map<String?, String> =
        Json.decodeFromString<Map<String, String>>(checkNotNull(raw))
            .mapKeys { if (it.key == "null") null else it.key }
}
