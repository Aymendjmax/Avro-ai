package com.newoether.agora.data

import com.newoether.agora.model.MessageSegment
import com.newoether.agora.model.citationRecords
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ExternalCitationImporterTest {
    @Test
    fun chatGptPrivateMarkerResolvesThroughExplicitContentReference() {
        val marker = "\uE200cite\uE202turn0search0\uE201"
        val reference = buildJsonObject {
            put("ref_id", buildJsonObject {
                put("turn_index", 0)
                put("ref_type", "search")
                put("ref_index", 0)
            })
            put("title", "Source title")
            put("url", "https://example.com/source")
            put("matched_text", "Claim")
        }
        val importer = GptChatImporter()
        val imported = importer.toImportFormat(
            listOf(
                GptChatImporter.GptConversation(
                    conversationId = "conversation",
                    title = "Imported",
                    currentNode = "node",
                    mapping = mapOf(
                        "node" to GptChatImporter.GptMappingNode(
                            id = "node",
                            message = GptChatImporter.GptMessage(
                                id = "message",
                                author = GptChatImporter.GptAuthor(role = "assistant"),
                                content = GptChatImporter.GptContent(
                                    contentType = "text",
                                    parts = listOf(JsonPrimitive("Claim$marker [docs](https://docs.example)")),
                                ),
                                metadata = GptChatImporter.GptMetadata(
                                    contentReferences = listOf(reference),
                                ),
                            ),
                        ),
                    ),
                ),
            ),
        )

        val message = imported.messages.single()
        assertEquals("Claim [docs](https://docs.example)", message.text)
        val citation = decodeCitations(message).single()
        assertEquals("openai", citation.provider)
        assertEquals("Source title", citation.title)
        assertEquals("https://example.com/source", citation.url)
        assertEquals(0, citation.anchors.single().startIndex)
        assertEquals(5, citation.anchors.single().endIndex)
        assertEquals("Claim", citation.anchors.single().citedText)
    }

    @Test
    fun unresolvedChatGptMarkersDisappearWithoutChangingMarkdownLinks() {
        val text = "Keep [docs](https://docs.example)\uE200cite\uE202turn0search0\uE201【turn0search1】"
        val importer = GptChatImporter()
        val imported = importer.toImportFormat(
            listOf(
                GptChatImporter.GptConversation(
                    conversationId = "conversation",
                    currentNode = "node",
                    mapping = mapOf(
                        "node" to GptChatImporter.GptMappingNode(
                            id = "node",
                            message = GptChatImporter.GptMessage(
                                id = "message",
                                author = GptChatImporter.GptAuthor(role = "assistant"),
                                content = GptChatImporter.GptContent(
                                    contentType = "text",
                                    parts = listOf(JsonPrimitive(text)),
                                ),
                            ),
                        ),
                    ),
                ),
            ),
        )

        val message = imported.messages.single()
        assertEquals("Keep [docs](https://docs.example)", message.text)
        assertNull(message.toolCallJson)
    }

    @Test
    fun claudeContentBlockCitationPersistsWithAnswerRelativeAnchor() {
        val citation = buildJsonObject {
            put("uuid", "document-1")
            put("title", "Imported document")
            put("file_name", "report.pdf")
            put("location", "Page 2")
            put("cited_text", "source excerpt")
        }
        val importer = ClaudeChatImporter()
        val imported = importer.toImportFormat(
            ClaudeChatImporter.ClaudeConversations(
                conversations = listOf(
                    ClaudeChatImporter.ClaudeConversation(
                        uuid = "conversation",
                        chatMessages = listOf(
                            ClaudeChatImporter.ClaudeMessage(
                                uuid = "message",
                                sender = "assistant",
                                content = listOf(
                                    ClaudeChatImporter.ClaudeContent(type = "thinking", thinking = "hidden"),
                                    ClaudeChatImporter.ClaudeContent(type = "text", text = "Intro "),
                                    ClaudeChatImporter.ClaudeContent(
                                        type = "text",
                                        text = "Claim",
                                        citations = listOf(citation, JsonPrimitive(5)),
                                    ),
                                ),
                            ),
                        ),
                    ),
                ),
            ),
        )

        val message = imported.messages.single()
        assertEquals("Intro Claim", message.text)
        val importedCitation = decodeCitations(message).single()
        assertEquals("anthropic", importedCitation.provider)
        assertEquals("file", importedCitation.kind)
        assertEquals("Imported document", importedCitation.title)
        assertEquals("report.pdf", importedCitation.fileName)
        assertEquals("Page 2", importedCitation.location)
        assertEquals("source excerpt", importedCitation.excerpt)
        assertEquals(6, importedCitation.anchors.single().startIndex)
        assertEquals(11, importedCitation.anchors.single().endIndex)
        assertEquals("Claim", importedCitation.anchors.single().citedText)
        assertTrue(message.thoughts == "hidden")
    }

    @Test
    fun claudeIsoTimestampsPreserveStandardAndMicrosecondPrecision() {
        val importer = ClaudeChatImporter()
        val imported = importer.toImportFormat(
            ClaudeChatImporter.ClaudeConversations(
                conversations = listOf(
                    ClaudeChatImporter.ClaudeConversation(
                        uuid = "conversation",
                        updatedAt = "2024-01-02T03:04:05.123456Z",
                        chatMessages = listOf(
                            ClaudeChatImporter.ClaudeMessage(
                                uuid = "standard",
                                sender = "human",
                                createdAt = "2024-01-02T03:04:05Z",
                            ),
                            ClaudeChatImporter.ClaudeMessage(
                                uuid = "microseconds",
                                sender = "assistant",
                                createdAt = "2024-01-02T03:04:05.123456Z",
                            ),
                        ),
                    ),
                ),
            ),
        )

        assertEquals(1_704_164_645_123L, imported.conversations.single().lastUpdated)
        assertEquals(1_704_164_645_000L, imported.messages[0].timestamp)
        assertEquals(1_704_164_645_123L, imported.messages[1].timestamp)
    }

    private fun decodeCitations(
        message: ClaudeChatImporter.ImportMessageEntity,
    ) = Json.decodeFromString<List<MessageSegment>>(checkNotNull(message.toolCallJson))
        .citationRecords(message.text)
}
