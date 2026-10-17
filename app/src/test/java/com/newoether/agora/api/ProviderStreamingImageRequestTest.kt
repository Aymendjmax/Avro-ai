package com.newoether.agora.api

import com.newoether.agora.api.anthropic.AnthropicProvider
import com.newoether.agora.api.gemini.GeminiProvider
import com.newoether.agora.api.ollama.OllamaProvider
import com.newoether.agora.api.openai.OpenAiProvider
import com.newoether.agora.model.ChatMessage
import com.newoether.agora.model.Participant
import com.newoether.agora.util.DebugLog
import com.sun.net.httpserver.HttpServer
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockkObject
import io.mockk.unmockkObject
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.net.InetSocketAddress
import java.util.Base64
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

class ProviderStreamingImageRequestTest {
    @Before
    fun disableAndroidLoggingForJvmNetworkTests() {
        mockkObject(DebugLog)
        every { DebugLog.d(any(), any()) } just Runs
        every { DebugLog.e(any(), any()) } just Runs
        every { DebugLog.w(any(), any()) } just Runs
    }

    @After
    fun restoreAndroidLogging() {
        unmockkObject(DebugLog)
    }

    @Test
    fun openAiChatStreamsDataUriImage() = withImage { image, expected ->
        withServer(openAiChatResponse()) { server ->
            collect(
                OpenAiProvider(),
                config(server, model = "gpt-4o"),
                image,
            )
            val request = server.takeRequest()
            assertEquals("/v1/chat/completions", request.path)
            val imageUrl = request.json["messages"]!!.jsonArray
                .flatMap { it.jsonObject["content"]!!.jsonArray }
                .map(JsonElement::jsonObject)
                .first { it["type"]!!.jsonPrimitive.content == "image_url" }
                .getValue("image_url").jsonObject.getValue("url").jsonPrimitive.content
            assertEquals("data:image/png;base64,$expected", imageUrl)
            request.assertExactLength()
        }
    }

    @Test
    fun openAiResponsesStreamsInputImage() = withImage { image, expected ->
        withServer(openAiResponsesResponse()) { server ->
            collect(
                OpenAiProvider(),
                config(server, model = "gpt-4o").copy(responsesApiEnabled = true),
                image,
            )
            val request = server.takeRequest()
            assertEquals("/v1/responses", request.path)
            val imageUrl = request.json["input"]!!.jsonArray
                .flatMap { it.jsonObject["content"]?.jsonArray.orEmpty() }
                .map(JsonElement::jsonObject)
                .first { it["type"]!!.jsonPrimitive.content == "input_image" }
                .getValue("image_url").jsonPrimitive.content
            assertEquals("data:image/png;base64,$expected", imageUrl)
            request.assertExactLength()
        }
    }

    @Test
    fun anthropicStreamsImageSourceData() = withImage { image, expected ->
        withServer(anthropicResponse()) { server ->
            collect(
                AnthropicProvider(),
                config(server, model = "claude-3-5-sonnet-20240620"),
                image,
            )
            val request = server.takeRequest()
            val source = request.json["messages"]!!.jsonArray
                .flatMap { it.jsonObject["content"]!!.jsonArray }
                .map(JsonElement::jsonObject)
                .first { it["type"]!!.jsonPrimitive.content == "image" }
                .getValue("source").jsonObject
            assertEquals("image/png", source.getValue("media_type").jsonPrimitive.content)
            assertEquals(expected, source.getValue("data").jsonPrimitive.content)
            request.assertExactLength()
        }
    }

    @Test
    fun geminiStreamsInlineData() = withImage { image, expected ->
        withServer(geminiResponse()) { server ->
            collect(
                GeminiProvider(),
                config(server, model = "gemini-2.5-flash"),
                image,
            )
            val request = server.takeRequest()
            val inlineData = request.json["contents"]!!.jsonArray
                .flatMap { it.jsonObject["parts"]!!.jsonArray }
                .map(JsonElement::jsonObject)
                .first { it.containsKey("inlineData") }
                .getValue("inlineData").jsonObject
            assertEquals("image/png", inlineData.getValue("mimeType").jsonPrimitive.content)
            assertEquals(expected, inlineData.getValue("data").jsonPrimitive.content)
            request.assertExactLength()
        }
    }

    @Test
    fun ollamaStreamsImageArrayEntry() = withImage { image, expected ->
        withServer(ollamaResponse(), contentType = "application/x-ndjson") { server ->
            collect(
                OllamaProvider(),
                config(server, model = "qwen3:8b"),
                image,
            )
            val request = server.takeRequest()
            val images = request.json["messages"]!!.jsonArray
                .map(JsonElement::jsonObject)
                .first { it["role"]!!.jsonPrimitive.content == "user" }
                .getValue("images").jsonArray
            assertEquals(expected, images.single().jsonPrimitive.content)
            request.assertExactLength()
        }
    }

    private fun collect(
        provider: LlmProvider,
        config: ProviderConfig,
        image: File,
    ) {
        val events = runBlocking {
            withTimeout(3_000L) {
                provider.generateResponse(
                    listOf(
                        ChatMessage(
                            text = "inspect",
                            images = listOf(image.absolutePath),
                            participant = Participant.USER,
                        ),
                    ),
                    config,
                ).toList()
            }
        }
        assertTrue(events.none { it is StreamEvent.Error })
    }

    private fun config(
        server: RecordingServer,
        model: String,
    ) = ProviderConfig(
        apiKey = "",
        modelId = model,
        baseUrl = when {
            model.startsWith("claude") || model.startsWith("gpt") -> "${server.baseUrl}/v1"
            else -> server.baseUrl
        },
        includeImages = true,
        thinkingEnabled = false,
    )

    private fun withImage(test: (File, String) -> Unit) {
        val image = File.createTempFile("agora-stream-image-", ".png")
        try {
            val bytes = ByteArray(32_777) { index -> ((index * 17) and 0xff).toByte() }
            image.writeBytes(bytes)
            test(image, Base64.getEncoder().encodeToString(bytes))
        } finally {
            image.delete()
        }
    }

    private fun withServer(
        response: String,
        contentType: String = "text/event-stream",
        test: (RecordingServer) -> Unit,
    ) = RecordingServer(response, contentType).use(test)

    private data class CapturedRequest(
        val path: String,
        val contentLength: Long?,
        val bytes: ByteArray,
    ) {
        val json: JsonObject
            get() = Json.parseToJsonElement(bytes.decodeToString()).jsonObject

        fun assertExactLength() {
            assertEquals(bytes.size.toLong(), contentLength)
            assertTrue(bytes.decodeToString().contains("__AGORA_BASE64_").not())
        }
    }

    private class RecordingServer(
        private val responseBody: String,
        private val contentType: String,
    ) : AutoCloseable {
        private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        private val requests = LinkedBlockingQueue<CapturedRequest>()
        val baseUrl = "http://127.0.0.1:${server.address.port}"

        init {
            server.createContext("/") { exchange ->
                val bytes = exchange.requestBody.use { it.readBytes() }
                requests.add(
                    CapturedRequest(
                        path = exchange.requestURI.path,
                        contentLength = exchange.requestHeaders
                            .getFirst("Content-Length")
                            ?.toLongOrNull(),
                        bytes = bytes,
                    ),
                )
                val response = responseBody.toByteArray()
                exchange.responseHeaders.add("Content-Type", contentType)
                exchange.sendResponseHeaders(200, response.size.toLong())
                exchange.responseBody.use { it.write(response) }
            }
            server.start()
        }

        fun takeRequest(): CapturedRequest = checkNotNull(
            requests.poll(1, TimeUnit.SECONDS),
        )

        override fun close() = server.stop(0)
    }

    private companion object {
        fun openAiChatResponse() =
            "data: {\"choices\":[{\"index\":0,\"delta\":{\"content\":\"ok\"}," +
                "\"finish_reason\":\"stop\"}]}\n\ndata: [DONE]\n\n"

        fun openAiResponsesResponse() =
            "data: {\"type\":\"response.output_text.delta\",\"sequence_number\":1," +
                "\"delta\":\"ok\"}\n\n" +
                "data: {\"type\":\"response.completed\",\"sequence_number\":2," +
                "\"response\":{\"status\":\"completed\"}}\n\n"

        fun anthropicResponse() = listOf(
            "{\"type\":\"content_block_start\",\"index\":0," +
                "\"content_block\":{\"type\":\"text\",\"text\":\"\"}}",
            "{\"type\":\"content_block_delta\",\"index\":0," +
                "\"delta\":{\"type\":\"text_delta\",\"text\":\"ok\"}}",
            "{\"type\":\"content_block_stop\",\"index\":0}",
            "{\"type\":\"message_delta\",\"delta\":{\"stop_reason\":\"end_turn\"}}",
            "{\"type\":\"message_stop\"}",
        ).joinToString(separator = "\n\n", postfix = "\n\n") { "data: $it" }

        fun geminiResponse() =
            "data: {\"candidates\":[{\"content\":{\"role\":\"model\"," +
                "\"parts\":[{\"text\":\"ok\"}]},\"finishReason\":\"STOP\"}]}\n\n"

        fun ollamaResponse() =
            "{\"message\":{\"role\":\"assistant\",\"content\":\"ok\"}," +
                "\"done\":true,\"done_reason\":\"stop\"," +
                "\"prompt_eval_count\":1,\"eval_count\":1}\n"
    }
}

private fun JsonArray?.orEmpty(): JsonArray = this ?: JsonArray(emptyList())
