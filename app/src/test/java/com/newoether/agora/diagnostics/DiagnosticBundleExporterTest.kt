package com.newoether.agora.diagnostics

import java.time.Instant
import java.time.ZoneId
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DiagnosticBundleExporterTest {
    @Test
    fun `export names use local date time with milliseconds for both formats`() {
        val generatedAtMillis = Instant.parse("2026-10-08T11:50:00.123Z").toEpochMilli()
        val zoneId = ZoneId.of("Asia/Shanghai")

        assertEquals(
            "agora-diagnostics-redacted-json-2026-10-08_19-50-00-123.json",
            DiagnosticBundleExporter.fileName(DiagnosticExportFormat.REDACTED_JSON, generatedAtMillis, zoneId),
        )
        assertEquals(
            "agora-diagnostics-summary-text-2026-10-08_19-50-00-123.txt",
            DiagnosticBundleExporter.fileName(DiagnosticExportFormat.SUMMARY_TEXT, generatedAtMillis, zoneId),
        )
        assertEquals(
            "agora-diagnostics-redacted-json-2026-10-08_04-50-00-123.json",
            DiagnosticBundleExporter.fileName(
                DiagnosticExportFormat.REDACTED_JSON,
                generatedAtMillis,
                ZoneId.of("America/Los_Angeles"),
            ),
        )
        val jsonExport = DiagnosticBundleExporter.export(
            DiagnosticSnapshot(), DiagnosticExportFormat.REDACTED_JSON, generatedAtMillis,
        )
        assertEquals(
            generatedAtMillis.toString(),
            kotlinx.serialization.json.Json.parseToJsonElement(jsonExport)
                .jsonObject.getValue("generatedAtMillis").jsonPrimitive.content,
        )
        val textExport = DiagnosticBundleExporter.export(
            DiagnosticSnapshot(), DiagnosticExportFormat.SUMMARY_TEXT, generatedAtMillis,
        )
        assertTrue(textExport.contains("generatedAtMillis=$generatedAtMillis"))
    }

    @Test
    fun `redacted json keeps message content and masks only credentials while summary stays content free`() {
        val providerId = "custom-provider-00000000-0000-4000-8000-000000000001"
        val requestBody = DiagnosticRedactor.captureJson(
            """{"messages":[{"role":"user","content":"private prompt"}],"api_key":"request-secret","max_tokens":10}""",
            credentialValues = setOf("request-secret"),
        )
        val events = listOf(
            DiagnosticEvent(
                sequence = 1L,
                timestampMillis = 10L,
                context = DiagnosticRequestContext(
                    requestId = REQUEST_ID,
                    provider = providerId,
                    model = "$providerId:model",
                    requestKind = "chat",
                ),
                payload = DiagnosticEventPayload.HttpRequest(
                    method = "POST",
                    url = DiagnosticRedactor.captureUrl(
                        "https://example.invalid/chat?key=query-secret&model=test",
                    ),
                    headers = DiagnosticRedactor.captureHeaders(
                        mapOf("Authorization" to "Bearer header-secret"),
                    ),
                    body = requestBody,
                ),
            ),
            DiagnosticEvent(
                sequence = 2L,
                timestampMillis = 11L,
                context = DiagnosticRequestContext(requestId = REQUEST_ID),
                payload = DiagnosticEventPayload.WireLine(
                    lineNumber = 1L,
                    line = DiagnosticRedactor.captureWireLine(
                        """data: {"text":"private response","result":"private tool result"}""",
                    ),
                ),
            ),
            DiagnosticEvent(
                sequence = 3L,
                timestampMillis = 12L,
                context = DiagnosticRequestContext(requestId = REQUEST_ID),
                payload = DiagnosticEventPayload.ParsedStreamEvent(
                    eventType = "HostedToolCallUpdate",
                    attributes = mapOf("name" to "fixture_tool", "resultChars" to "19"),
                    content = DiagnosticRedactor.captureContent("private parsed tool result"),
                ),
            ),
        )
        val snapshot = DiagnosticSnapshot(
            state = DiagnosticCaptureState.RUNNING,
            session = DiagnosticSession(
                id = SESSION_ID,
                startedAtMillis = 1L,
            ),
            events = events,
            nextSequence = 4L,
            retainedPayloadBytes = 321L,
            droppedEventCount = 2L,
            evictedEventCount = 3L,
            truncatedPayloadCount = 4L,
            capacityLimitReached = true,
        )

        val redacted = DiagnosticBundleExporter.export(
            snapshot = snapshot,
            format = DiagnosticExportFormat.REDACTED_JSON,
            generatedAtMillis = 20L,
        )
        val summary = DiagnosticBundleExporter.export(
            snapshot = snapshot,
            format = DiagnosticExportFormat.SUMMARY_TEXT,
            generatedAtMillis = 20L,
        )

        assertEquals(
            listOf(
                DiagnosticExportFormat.REDACTED_JSON,
                DiagnosticExportFormat.SUMMARY_TEXT,
            ),
            DiagnosticExportFormat.entries,
        )
        assertTrue(redacted.contains("private prompt"))
        assertTrue(redacted.contains("private response"))
        assertTrue(redacted.contains("private tool result"))
        assertTrue(redacted.contains("private parsed tool result"))
        assertFalse(redacted.contains("request-secret"))
        assertFalse(redacted.contains("query-secret"))
        assertFalse(redacted.contains("header-secret"))
        assertFalse(redacted.contains("[REDACTED_CONTENT]"))
        assertTrue(redacted.contains("[REDACTED_SECRET]"))
        assertTrue(redacted.contains("max_tokens"))
        assertTrue(redacted.contains("fixture_tool"))
        assertTrue(redacted.contains(providerId))
        assertTrue(redacted.contains("\"format\": \"REDACTED_JSON\""))
        assertTrue(redacted.contains("\"captureState\": \"RUNNING\""))
        assertTrue(redacted.contains("\"nextSequence\": 4"))
        assertTrue(redacted.contains("\"retainedPayloadBytes\": 321"))
        assertTrue(redacted.contains("\"evictedEventCount\": 3"))
        assertTrue(redacted.contains("\"truncatedPayloadCount\": 4"))
        assertTrue(redacted.contains("\"capacityLimitReached\": true"))
        assertTrue(redacted.contains("\"captureIncompleteDueToCapacity\": true"))

        assertTrue(summary.contains("eventCount=3"))
        assertTrue(summary.contains("droppedEventCount=2"))
        assertTrue(summary.contains("capacityLimitReached=true"))
        assertTrue(summary.contains("captureIncompleteDueToCapacity=true"))
        assertTrue(summary.contains("type=HttpRequest"))
        assertTrue(summary.contains("type=ParsedStreamEvent"))
        assertFalse(summary.contains("private prompt"))
        assertFalse(summary.contains("private response"))
        assertFalse(summary.contains("private parsed tool result"))
    }

    private companion object {
        const val SESSION_ID = "raw-session-id"
        const val REQUEST_ID = "raw-request-id"
    }
}
