package com.newoether.agora.diagnostics

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Produces local-only diagnostic exports from the credential-sanitized capture snapshot. */
object DiagnosticBundleExporter {
    private val json = Json {
        classDiscriminator = "payloadType"
        encodeDefaults = true
        prettyPrint = true
    }

    fun fileName(
        format: DiagnosticExportFormat,
        generatedAtMillis: Long,
        zoneId: ZoneId = ZoneId.systemDefault(),
    ): String {
        val timestamp = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss-SSS", Locale.ROOT)
            .withZone(zoneId)
            .format(Instant.ofEpochMilli(generatedAtMillis))
        val formatName = format.name.lowercase(Locale.ROOT).replace('_', '-')
        val extension = if (format == DiagnosticExportFormat.SUMMARY_TEXT) "txt" else "json"
        return "agora-diagnostics-$formatName-$timestamp.$extension"
    }

    fun export(
        snapshot: DiagnosticSnapshot,
        format: DiagnosticExportFormat,
        generatedAtMillis: Long = System.currentTimeMillis(),
    ): String = when (format) {
        DiagnosticExportFormat.REDACTED_JSON -> exportJson(
            snapshot = snapshot,
            generatedAtMillis = generatedAtMillis,
        )
        DiagnosticExportFormat.SUMMARY_TEXT -> exportSummary(
            snapshot = snapshot,
            generatedAtMillis = generatedAtMillis,
        )
    }

    private fun exportJson(
        snapshot: DiagnosticSnapshot,
        generatedAtMillis: Long,
    ): String {
        val root = buildJsonObject {
            put("schemaVersion", 1)
            put("generatedAtMillis", generatedAtMillis)
            put("format", DiagnosticExportFormat.REDACTED_JSON.name)
            put("captureState", snapshot.state.name)
            put("captureActive", snapshot.isCaptureActive)
            put("capacityLimitReached", snapshot.capacityLimitReached)
            put("captureIncompleteDueToCapacity", snapshot.capacityLimitReached)
            put("nextSequence", snapshot.nextSequence)
            put("retainedPayloadBytes", snapshot.retainedPayloadBytes)
            put("droppedEventCount", snapshot.droppedEventCount)
            put("evictedEventCount", snapshot.evictedEventCount)
            put("truncatedPayloadCount", snapshot.truncatedPayloadCount)
            put("eventCount", snapshot.events.size)
            snapshot.session?.let { session ->
                putJsonObject("session") {
                    put("id", session.id)
                    put("startedAtMillis", session.startedAtMillis)
                }
            }
            putJsonArray("events") {
                snapshot.events.forEach { event ->
                    add(
                        json.encodeToJsonElement(
                            DiagnosticEvent.serializer(),
                            event.redactContent(),
                        ),
                    )
                }
            }
        }
        return json.encodeToString(JsonObject.serializer(), root)
    }

    private fun exportSummary(
        snapshot: DiagnosticSnapshot,
        generatedAtMillis: Long,
    ): String = buildString {
        appendLine("Avro Diagnostic Capture Summary")
        appendLine("schemaVersion=1")
        appendLine("generatedAtMillis=$generatedAtMillis")
        appendLine("format=${DiagnosticExportFormat.SUMMARY_TEXT.name}")
        appendLine("captureState=${snapshot.state.name}")
        appendLine("captureActive=${snapshot.isCaptureActive}")
        appendLine("capacityLimitReached=${snapshot.capacityLimitReached}")
        appendLine("captureIncompleteDueToCapacity=${snapshot.capacityLimitReached}")
        appendLine("nextSequence=${snapshot.nextSequence}")
        appendLine("retainedPayloadBytes=${snapshot.retainedPayloadBytes}")
        appendLine("eventCount=${snapshot.events.size}")
        appendLine("droppedEventCount=${snapshot.droppedEventCount}")
        appendLine("evictedEventCount=${snapshot.evictedEventCount}")
        appendLine("truncatedPayloadCount=${snapshot.truncatedPayloadCount}")
        snapshot.session?.let { session ->
            appendLine("sessionId=${session.id}")
            appendLine("startedAtMillis=${session.startedAtMillis}")
        }
        snapshot.events.forEach { event ->
            append('#')
            append(event.sequence)
            append(" timestampMillis=")
            append(event.timestampMillis)
            event.context.requestKind?.let { append(" requestKind=$it") }
            event.context.provider?.let { append(" provider=$it") }
            event.context.model?.let { append(" model=$it") }
            event.context.pass?.let { append(" pass=$it") }
            append(" type=")
            append(event.payload.typeName())
            append(event.payload.summaryAttributes())
            appendLine()
        }
    }

    private fun DiagnosticEvent.redactContent(): DiagnosticEvent = copy(
        payload = when (val current = payload) {
            is DiagnosticEventPayload.RuntimeTransition,
            is DiagnosticEventPayload.HttpStage -> current
            is DiagnosticEventPayload.HttpRequest -> current.copy(
                body = DiagnosticRedactor.redactJsonContent(current.body),
            )
            is DiagnosticEventPayload.HttpResponseBody -> current.copy(
                body = DiagnosticRedactor.redactJsonContent(current.body),
            )
            is DiagnosticEventPayload.WireLine -> current.copy(
                line = DiagnosticRedactor.redactWireContent(current.line),
            )
            is DiagnosticEventPayload.ParsedStreamEvent -> current.copy(
                content = current.content?.let(DiagnosticRedactor::redactContent),
            )
        },
    )

    private fun DiagnosticEventPayload.typeName(): String = when (this) {
        is DiagnosticEventPayload.RuntimeTransition -> "RuntimeTransition"
        is DiagnosticEventPayload.HttpStage -> "HttpStage"
        is DiagnosticEventPayload.HttpRequest -> "HttpRequest"
        is DiagnosticEventPayload.HttpResponseBody -> "HttpResponseBody"
        is DiagnosticEventPayload.WireLine -> "WireLine"
        is DiagnosticEventPayload.ParsedStreamEvent -> "ParsedStreamEvent"
    }

    private fun DiagnosticEventPayload.summaryAttributes(): String = when (this) {
        is DiagnosticEventPayload.RuntimeTransition ->
            " commandType=$commandType oldState=$oldState newState=$newState"
        is DiagnosticEventPayload.HttpStage ->
            " stage=$stage elapsedMillis=$elapsedMillis" + attributes.summaryPairs()
        is DiagnosticEventPayload.HttpRequest -> " method=$method bodyChars=${body.originalLength}"
        is DiagnosticEventPayload.HttpResponseBody -> " code=$code bodyChars=${body.originalLength}"
        is DiagnosticEventPayload.WireLine ->
            " lineNumber=$lineNumber lineChars=${line.originalLength}"
        is DiagnosticEventPayload.ParsedStreamEvent ->
            " eventType=$eventType" + attributes.summaryPairs()
    }

    private fun Map<String, String>.summaryPairs(): String =
        entries.joinToString(separator = "", prefix = "") { (key, value) -> " $key=$value" }
}
