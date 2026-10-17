package com.newoether.agora.viewmodel

import com.newoether.agora.readLocaleStringResourceSources
import com.newoether.agora.R
import com.newoether.agora.api.GenerationError
import android.content.Context
import io.mockk.every
import io.mockk.mockk
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GenerationErrorPresentationTest {
    @Test
    fun `typed app owned generation failures map to localized resources`() {
        val expected = mapOf(
            GenerationError.Network(401, "ignored") to R.string.generation_error_authentication,
            GenerationError.Network(429, "ignored") to R.string.generation_error_rate_limit,
            GenerationError.Network(503, "ignored") to R.string.generation_error_server,
            GenerationError.Network(418, "teapot") to R.string.generation_error_network_http,
            GenerationError.SseParse("raw", "cause") to R.string.generation_error_sse_parse,
            GenerationError.IncompleteStream("Provider", null, false, true) to
                R.string.generation_error_incomplete_stream,
            GenerationError.IncompleteStream("Provider", null, true, true) to
                R.string.generation_error_incomplete_tool_stream,
            GenerationError.OutputTruncated("Provider", "max_tokens") to
                R.string.generation_error_output_truncated,
            GenerationError.ToolExecution("tool", "{}", "detail") to
                R.string.generation_error_tool_execution,
            GenerationError.Transcription("image", "detail") to
                R.string.generation_error_transcription,
            GenerationError.Embedding("model", "detail") to R.string.generation_error_embedding,
            GenerationError.RequestFormat("Provider", "detail") to
                R.string.generation_error_request_format,
            GenerationError.Cancelled to R.string.generation_error_cancelled,
            GenerationError.Timeout to R.string.generation_error_timeout,
        )

        expected.forEach { (error, resourceId) ->
            assertEquals(resourceId, error.ownedGenerationErrorStringResourceId())
        }
        assertNull(GenerationError.Api(null, null, "provider detail").ownedGenerationErrorStringResourceId())
        assertNull(GenerationError.LocalModel("local detail").ownedGenerationErrorStringResourceId())
        assertNull(GenerationError.Configuration("config detail").ownedGenerationErrorStringResourceId())
        assertNull(GenerationError.Unknown(RuntimeException()).ownedGenerationErrorStringResourceId())
        assertNull(GenerationError.Unknown(RuntimeException("opaque detail")).ownedGenerationErrorStringResourceId())
    }

    @Test
    fun `unknown exception shares canonical cause detail and localized known reasons`() {
        val context = mockk<Context>()
        every { context.getString(R.string.generation_error_connection_reset) } returns "Connection reset."
        val nested = GenerationError.Unknown(RuntimeException(" ").apply {
            initCause(IllegalStateException("connection reset"))
        })
        assertEquals(R.string.generation_error_connection_reset, nested.ownedGenerationErrorStringResourceId())
        assertEquals("Connection reset.", localizedGenerationError(context, nested))
        assertEquals("java.lang.NullPointerException", localizedGenerationError(context, GenerationError.Unknown(NullPointerException())))
        for (detail in listOf("invalid_request_error", "{\"message\":\"bad\"}", "https://example.com/error")) {
            assertEquals(detail, localizedGenerationError(context, GenerationError.Unknown(RuntimeException(detail))))
        }
    }

    @Test
    fun `known transport details are case insensitive and resource owned`() {
        assertEquals(
            KnownGenerationErrorDetail.CONNECTION_CLOSED,
            knownGenerationErrorDetail("connection closed"),
        )
        assertEquals(
            KnownGenerationErrorDetail.CONNECTION_CLOSED,
            knownGenerationErrorDetail("Connection Closed"),
        )
        assertEquals(
            R.string.generation_error_connection_closed,
            KnownGenerationErrorDetail.CONNECTION_CLOSED.stringResourceId(),
        )
        assertEquals(
            R.string.generation_error_connection_refused,
            KnownGenerationErrorDetail.CONNECTION_REFUSED.stringResourceId(),
        )
        assertEquals(
            R.string.generation_error_connection_reset,
            KnownGenerationErrorDetail.CONNECTION_RESET.stringResourceId(),
        )
        assertEquals(
            R.string.generation_error_unknown_host,
            KnownGenerationErrorDetail.UNKNOWN_HOST.stringResourceId(),
        )
        assertEquals(
            R.string.generation_error_tls_failure,
            KnownGenerationErrorDetail.TLS_FAILURE.stringResourceId(),
        )
        assertNull(knownGenerationErrorDetail("provider-specific failure"))
    }

    @Test
    fun `plain prose is sentence cased without rewriting opaque diagnostics`() {
        assertEquals("Connection closed", normalizeGenerationErrorDetail("connection closed"))
        assertEquals("Already Correct", normalizeGenerationErrorDetail("Already Correct"))
        assertEquals("{\"error\":\"bad\"}", normalizeGenerationErrorDetail("{\"error\":\"bad\"}"))
        assertEquals("invalid_request_error", normalizeGenerationErrorDetail("invalid_request_error"))
        assertEquals(
            "invalid_request_error: bad request",
            normalizeGenerationErrorDetail("invalid_request_error: bad request"),
        )
        assertEquals("https://example.com/error", normalizeGenerationErrorDetail("https://example.com/error"))
    }

    @Test
    fun `persisted network wrapper delegates its detail to the localized boundary`() {
        val context = mockk<Context>()
        every { context.getString(R.string.generation_error_connection_closed) } returns
            "Connection closed."
        assertEquals(
            context.getString(R.string.generation_error_connection_closed),
            normalizePersistedGenerationErrorText(context, "Network error (0): connection closed"),
        )
    }

    @Test
    fun `persisted network json displays one decoded human message`() {
        val context = mockk<Context>()
        val detail = "【账户余额不足】当前可用余额 $0.57\n" +
            "按量计费需要可用余额 > 0 才能发起请求。\n" +
            "去充值：https://api.lmuai.com/purchase?recharge=1"
        val rawJson = "{\"balance\":0.57287809,\"code\":\"INSUFFICIENT_BALANCE\"," +
            "\"error\":{\"code\":\"INSUFFICIENT_BALANCE\"," +
            "\"message\":\"【账户余额不足】当前可用余额 $0.57\\n" +
            "按量计费需要可用余额 \\u003e 0 才能发起请求。\\n" +
            "去充值：https://api.lmuai.com/purchase?recharge=1\"," +
            "\"type\":\"billing_error\"}," +
            "\"message\":\"duplicate envelope message\"," +
            "\"reason\":\"insufficient balance\"}"
        every {
            context.getString(R.string.generation_error_network_http, 403, detail)
        } returns "网络错误（403）：$detail"

        assertEquals(
            "网络错误（403）：$detail",
            normalizePersistedGenerationErrorText(
                context,
                "Network error (403): $rawJson",
            ),
        )
    }

    @Test
    fun `persisted network wrapper extracts primitive error value`() {
        val context = mockk<Context>()
        every {
            context.getString(R.string.generation_error_network_http, 400, "Invalid JSON")
        } returns "Network error (400): Invalid JSON"

        assertEquals(
            "Network error (400): Invalid JSON",
            normalizePersistedGenerationErrorText(
                context,
                """Network error (400): {"error":"Invalid JSON"}""",
            ),
        )
    }

    @Test
    fun `structured error extraction uses supported precedence and safe fallback`() {
        assertEquals(
            "nested",
            extractStructuredGenerationErrorDetail(
                """{"error":{"message":"nested"},"message":"top","reason":"reason"}""",
            ),
        )
        assertEquals(
            "top",
            extractStructuredGenerationErrorDetail("""{"message":"top","reason":"reason"}"""),
        )
        assertEquals(
            "Invalid JSON",
            extractStructuredGenerationErrorDetail("""{"error":"Invalid JSON"}"""),
        )
        assertEquals(
            "detail",
            extractStructuredGenerationErrorDetail("""{"detail":"detail"}"""),
        )
        assertEquals(
            "description",
            extractStructuredGenerationErrorDetail("""{"error_description":"description"}"""),
        )
        assertEquals(
            "root message",
            extractStructuredGenerationErrorDetail(""""root message""""),
        )
        assertEquals(
            "reason",
            extractStructuredGenerationErrorDetail("""{"reason":"reason"}"""),
        )
        assertNull(extractStructuredGenerationErrorDetail("{bad"))
        assertNull(extractStructuredGenerationErrorDetail("""["message"]"""))
        assertNull(extractStructuredGenerationErrorDetail("""{"code":"opaque"}"""))
    }

    @Test
    fun `chat generation consumers use typed localized presentation`() {
        val generation = sourceFile(
            "app/src/main/java/com/newoether/agora/viewmodel/GenerationManager.kt",
        ) + sourceFile(
            "app/src/main/java/com/newoether/agora/viewmodel/GenerationOutputAccumulator.kt",
        )
        val transcription = sourceFile(
            "app/src/main/java/com/newoether/agora/viewmodel/TranscriptionManager.kt",
        )
        val bar = sourceFile(
            "app/src/main/java/com/newoether/agora/ui/chat/message/GenerationErrorBar.kt",
        )

        assertTrue(generation.contains("localizedGenerationError(context, event.error)"))
        assertFalse(generation.contains("generationErrorMessage = event.message"))
        assertTrue(transcription.contains("localizedGenerationError(context, event.error)"))
        assertFalse(transcription.contains("streamError = event.message"))
        assertTrue(bar.contains("normalizePersistedGenerationErrorText("))
    }

    @Test
    fun `Local semantic error code reaches live and final persisted segments`() {
        val generation = sourceFile(
            "app/src/main/java/com/newoether/agora/viewmodel/GenerationManager.kt",
        ) + sourceFile(
            "app/src/main/java/com/newoether/agora/viewmodel/GenerationOutputAccumulator.kt",
        )
        val streaming = sourceFile(
            "app/src/main/java/com/newoether/agora/viewmodel/GenerationStreamingSegments.kt",
        )
        val liveProjection = generation
            .substringAfter("segments = buildLiveSegments(")
            .substringBefore("retryText = output.retryText")
        val finalProjection = generation
            .substringAfter("val generatedMessage = GenerationFinalSnapshot(")
            .substringBefore(").toMessage()")
        val errorSegmentProjection = streaming
            .substringAfter("errorMessage?.takeIf { it.isNotBlank() }")
            .substringBefore("result.addAll(citations)")
        val finalMessageProjection = streaming
            .substringAfter("internal fun GenerationFinalSnapshot.toMessage(): ChatMessage")

        assertTrue(generation.contains(
            "generationErrorCode = (event.error as? GenerationError.LocalModel)?.code"
        ))
        assertTrue(liveProjection.contains("generationErrorMessage"))
        assertTrue(liveProjection.contains("generationErrorCode"))
        assertTrue(finalProjection.contains("errorMessage = output.generationErrorMessage"))
        assertTrue(finalProjection.contains("errorCode = output.generationErrorCode"))
        assertTrue(streaming.contains("errorCode: String? = null"))
        assertTrue(errorSegmentProjection.contains("errorCode = errorCode"))
        assertTrue(streaming.contains("val errorCode: String? = null"))
        assertTrue(finalMessageProjection.contains("errorMessage,"))
        assertTrue(finalMessageProjection.contains("errorCode,"))
    }

    @Test
    fun `Local context help reuses the shared terminal and Markdown link presentation`() {
        val bar = sourceFile(
            "app/src/main/java/com/newoether/agora/ui/chat/message/GenerationErrorBar.kt",
        )
        val helpAction = bar
            .substringAfter("private fun LocalContextHelpAction(onClick: () -> Unit)")
            .substringBefore("internal fun StoppedGenerationBar")

        assertTrue(bar.contains("GenerationTerminalText("))
        assertTrue(bar.contains("stringResource(R.string.learn_more)"))
        assertTrue(bar.contains("R.string.local_model_limitations_title"))
        assertTrue(bar.contains("R.string.local_model_limitations_body"))
        assertTrue(bar.contains("Text(stringResource(R.string.ok))"))
        assertTrue(helpAction.contains("MaterialTheme.colorScheme.primary"))
        assertTrue(helpAction.contains("MarkdownLinkPressedAlpha"))
        assertTrue(helpAction.contains("MarkdownLinkPressAnimationMillis"))
        assertTrue(helpAction.contains("easing = FastOutSlowInEasing"))
        assertTrue(helpAction.contains("indication = null"))
        assertFalse(helpAction.contains(".background("))
        assertFalse(helpAction.contains("Surface("))
    }

    @Test
    fun `terminal finalization failures escape to the bound Run recovery owner`() {
        val generation = sourceFile(
            "app/src/main/java/com/newoether/agora/viewmodel/GenerationManager.kt",
        ) + sourceFile(
            "app/src/main/java/com/newoether/agora/viewmodel/GenerationOutputAccumulator.kt",
        )
        val failureLog =
            "DebugLog.e(\"AgoraVM\", \"Failed to execute terminal generation effect\", e)"
        val afterFailureLog = generation.substringAfter(failureLog, missingDelimiterValue = "")

        assertTrue(afterFailureLog.trimStart().startsWith("throw e"))
    }

    @Test
    fun `every supported locale owns the generation error key set`() {
        val keys = setOf(
            "generation_error_authentication",
            "generation_error_rate_limit",
            "generation_error_server",
            "generation_error_network",
            "generation_error_network_http",
            "generation_error_sse_parse",
            "generation_error_incomplete_stream",
            "generation_error_incomplete_tool_stream",
            "generation_error_incomplete_stream_reason",
            "generation_error_incomplete_tool_stream_reason",
            "generation_error_output_truncated",
            "generation_error_tool_execution",
            "generation_error_transcription",
            "generation_error_embedding",
            "generation_error_request_format",
            "generation_error_cancelled",
            "generation_error_timeout",
            "generation_error_unexpected",
            "generation_error_connection_closed",
            "generation_error_connection_refused",
            "generation_error_connection_reset",
            "generation_error_unknown_host",
            "generation_error_tls_failure",
            "low_context_mode",
            "low_context_mode_default_desc",
            "learn_more",
            "local_model_limitations_title",
            "local_model_limitations_body",
        )
        val directories = listOf(
            "values-ar",
            "values-de",
            "values-es",
            "values-fr",
            "values-ja",
            "values-ko",
            "values-pt-rBR",
            "values-ru",
            "values-vi",
            "values-zh",
            "values-zh-rTW",
        )
        val defaults = stringValues(sourceFile("app/src/main/res/values/strings.xml"))
        assertTrue(defaults.keys.containsAll(keys))
        directories.forEach { directory ->
            val localized = stringValues(sourceFile("app/src/main/res/$directory/strings.xml"))
            assertTrue("$directory missing ${keys - localized.keys}", localized.keys.containsAll(keys))
            keys.forEach { key ->
                assertNotEquals("$directory left $key in English", defaults[key], localized[key])
            }
        }
    }

    private fun stringValues(xml: String): Map<String, String> =
        Regex("""<string name="([^"]+)"(?: [^>]*)?>([^<]*)</string>""")
            .findAll(xml)
            .associate { it.groupValues[1] to it.groupValues[2] }

    private fun sourceFile(relativePath: String): String {
        var directory = File(requireNotNull(System.getProperty("user.dir"))).absoluteFile
        repeat(8) {
            val candidate = File(directory, relativePath)
            if (candidate.isFile) {
                return if (candidate.name == "strings.xml") {
                    candidate.readLocaleStringResourceSources()
                } else candidate.readText()
            }
            directory = directory.parentFile ?: error("Reached filesystem root")
        }
        error("Unable to locate $relativePath")
    }
}
