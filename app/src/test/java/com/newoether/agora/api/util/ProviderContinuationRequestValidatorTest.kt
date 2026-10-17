package com.newoether.agora.api.util

import com.newoether.agora.api.OpenAiChatRequest
import com.newoether.agora.api.OpenAiFunctionCall
import com.newoether.agora.api.OpenAiMessage
import com.newoether.agora.api.OpenAiRequestFunction
import com.newoether.agora.api.OpenAiRequestToolCall
import com.newoether.agora.api.OpenAiToolCall
import com.newoether.agora.api.anthropic.AnthropicContentPart
import com.newoether.agora.api.anthropic.AnthropicMessage
import com.newoether.agora.api.anthropic.AnthropicRequest
import com.newoether.agora.api.anthropic.requireValidWireFormat
import com.newoether.agora.api.gemini.ApiGenerateContentRequest
import com.newoether.agora.api.gemini.ApiRequestContent
import com.newoether.agora.api.gemini.ApiRequestPart
import com.newoether.agora.api.gemini.GeminiFunctionCall
import com.newoether.agora.api.gemini.GeminiFunctionResponse
import com.newoether.agora.api.gemini.requireValidWireFormat
import com.newoether.agora.api.ollama.OllamaChatRequest
import com.newoether.agora.api.ollama.OllamaMessage
import com.newoether.agora.api.ollama.requireValidWireFormat
import com.newoether.agora.api.openai.requireValidWireFormat
import com.newoether.agora.model.ChatMessage
import com.newoether.agora.model.Participant
import kotlinx.serialization.json.JsonObject
import org.junit.Test

/** Regression coverage for provider requests that terminate in tool or Compact input. */
class ProviderContinuationRequestValidatorTest {
    private val emptyObject = JsonObject(emptyMap())

    @Test
    fun openAiAcceptsCompleteToolResultAsTerminalInput() {
        OpenAiChatRequest(
            model = "deepseek-chat",
            messages = listOf(
                OpenAiMessage("user", content = listOf(text("start"))),
                OpenAiMessage(
                    "assistant",
                    toolCalls = listOf(
                        OpenAiRequestToolCall(
                            id = "call_1",
                            function = OpenAiRequestFunction("file_read", "{}"),
                        )
                    ),
                ),
                OpenAiMessage("tool", content = listOf(text("result")), toolCallId = "call_1"),
            ),
        ).requireValidWireFormat("DeepSeek")
    }

    @Test
    fun anthropicAcceptsCompleteToolResultAsTerminalInput() {
        AnthropicRequest(
            model = "claude-sonnet-5",
            messages = listOf(
                AnthropicMessage("user", listOf(AnthropicContentPart("text", text = "start"))),
                AnthropicMessage(
                    "assistant",
                    listOf(
                        AnthropicContentPart(
                            type = "tool_use",
                            id = "call_1",
                            name = "file_read",
                            input = emptyObject,
                        )
                    ),
                ),
                AnthropicMessage(
                    "user",
                    listOf(
                        AnthropicContentPart(
                            type = "tool_result",
                            toolUseId = "call_1",
                            content = "result",
                        )
                    ),
                ),
            ),
        ).requireValidWireFormat()
    }

    @Test
    fun geminiAcceptsCompleteFunctionResponseAsTerminalInput() {
        ApiGenerateContentRequest(
            contents = listOf(
                ApiRequestContent("user", listOf(ApiRequestPart(text = "start"))),
                ApiRequestContent(
                    "model",
                    listOf(
                        ApiRequestPart(
                            functionCall = GeminiFunctionCall(
                                id = "call_1",
                                name = "file_read",
                                args = emptyObject,
                            )
                        )
                    ),
                ),
                ApiRequestContent(
                    "user",
                    listOf(
                        ApiRequestPart(
                            functionResponse = GeminiFunctionResponse(
                                id = "call_1",
                                name = "file_read",
                                response = emptyObject,
                            )
                        )
                    ),
                ),
            ),
        ).requireValidWireFormat("gemini-2.5-pro")
    }

    @Test
    fun ollamaAcceptsCompleteToolResultAsTerminalInput() {
        OllamaChatRequest(
            model = "qwen3",
            messages = listOf(
                OllamaMessage("user", content = "start"),
                OllamaMessage(
                    "assistant",
                    toolCalls = listOf(
                        OpenAiToolCall(
                            index = 0,
                            id = "call_1",
                            type = "function",
                            function = OpenAiFunctionCall("file_read", emptyObject),
                        )
                    ),
                ),
                OllamaMessage("tool", content = "result", toolName = "file_read"),
            ),
        ).requireValidWireFormat()
    }

    @Test
    fun compactContinuationIsValidTerminalUserInputForEveryProvider() {
        val content = prepareMessages(
            listOf(
                ChatMessage(
                    id = "compact_boundary",
                    text = "summary",
                    participant = Participant.MODEL,
                )
            ),
            contextTokenBudget = 4_096,
        ).single().text

        OpenAiChatRequest(
            model = "deepseek-chat",
            messages = listOf(OpenAiMessage("user", content = listOf(text(content)))),
        ).requireValidWireFormat("DeepSeek")
        AnthropicRequest(
            model = "claude-sonnet-5",
            messages = listOf(
                AnthropicMessage("user", listOf(AnthropicContentPart("text", text = content)))
            ),
        ).requireValidWireFormat()
        ApiGenerateContentRequest(
            contents = listOf(
                ApiRequestContent("user", listOf(ApiRequestPart(text = content)))
            ),
        ).requireValidWireFormat("gemini-2.5-pro")
        OllamaChatRequest(
            model = "qwen3",
            messages = listOf(OllamaMessage("user", content = content)),
        ).requireValidWireFormat()
    }

    private fun text(value: String) = com.newoether.agora.api.OpenAiContentPart(
        type = "text",
        text = value,
    )
}
