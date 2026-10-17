package com.newoether.agora.api.util

import com.newoether.agora.api.StreamEvent
import com.newoether.agora.api.ToolDefinition
import com.newoether.agora.api.openai.StreamingTextToolCallParser
import com.newoether.agora.api.openai.ToolCallTextParser
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import java.util.UUID

/**
 * Owns provider-neutral compatibility parsing between wire decoders and executable authority.
 * Native provider events remain live. Text-rendered tool calls stay private until the stream ends,
 * so a later native equivalent can win without creating duplicate UI or execution events.
 */
internal class ProviderStreamNormalizer(
    tools: List<ToolDefinition>?,
    private val json: Json = Json,
    private val nativeTextParsingAuthoritative: Boolean = false,
) {
    private data class ThoughtMetadata(val title: String?, val signature: String?)
    private data class ToolFingerprint(val name: String, val arguments: String)
    private data class TextToolCandidate(
        val streamKey: String,
        val id: String?,
        val name: String,
        val arguments: String,
    )

    private enum class ToolProbe { UNDECIDED, TAGGED, JSON, TEXT }

    private val offeredToolNames = tools.orEmpty().map { it.function.name }.toSet()
    private val inlineThinking = IncrementalThinkingParser()
    private val structuredThinking = IncrementalThinkingParser(startInThinking = true)
    // Native authority suppresses generic text tool recovery only. Shared thinking recovery still
    // handles delimiters that a model template emits but llama.cpp does not classify.
    private val textToolParser = StreamingTextToolCallParser().takeIf {
        offeredToolNames.isNotEmpty() && !nativeTextParsingAuthoritative
    }
    private val toolProbeBuffer = StringBuilder()
    private val taggedResidualText = StringBuilder()
    private val textToolCandidates = mutableListOf<TextToolCandidate>()
    private val nativeFingerprints = mutableSetOf<ToolFingerprint>()
    private val nativeCallIds = mutableSetOf<String>()
    private val nativeFingerprintsById = mutableMapOf<String, ToolFingerprint?>()
    private val inlineThought = StringBuilder()
    private val nativeThought = StringBuilder()
    private var inlineVisibleProjection = ""
    private var nativeVisibleProjection = ""
    private var structuredMetadata = ThoughtMetadata(null, null)
    private var toolProbe = ToolProbe.UNDECIDED
    private var malformedTextTool = false
    private val malformedTextCalls = mutableListOf<StreamEvent.ToolCallRequest>()
    private var finished = false

    suspend fun emit(event: StreamEvent, downstream: suspend (StreamEvent) -> Unit) {
        check(!finished) { "Provider stream normalizer already finished" }
        when (event) {
            is StreamEvent.TextChunk -> {
                flushStructuredThinking(downstream)
                routeProviderText(event.text, downstream)
            }
            is StreamEvent.ThoughtChunk -> {
                flushInlineThinking(downstream)
                routeStructuredThought(event, downstream)
            }
            is StreamEvent.ToolCallRequest -> {
                flushThinking(downstream)
                recordNativeCall(event)
                downstream(event)
            }
            is StreamEvent.ToolCallsRequest -> {
                flushThinking(downstream)
                event.calls.forEach(::recordNativeCall)
                downstream(event)
            }
            is StreamEvent.Error -> {
                finish(downstream, releaseTextTools = false)
                downstream(event)
            }
            else -> {
                flushThinking(downstream)
                downstream(event)
            }
        }
    }

    suspend fun finish(
        downstream: suspend (StreamEvent) -> Unit,
        releaseTextTools: Boolean = true,
    ) {
        if (finished) return
        flushThinking(downstream)
        if (releaseTextTools) {
            finishTextTools(downstream)
            releaseTextTools(downstream)
        } else {
            flushAbortedText(downstream)
        }
        finished = true
    }

    private suspend fun routeProviderText(
        content: String,
        downstream: suspend (StreamEvent) -> Unit,
    ) {
        routeRawText(content, downstream)
    }

    private suspend fun routeRawText(
        content: String,
        downstream: suspend (StreamEvent) -> Unit,
    ) {
        inlineThinking.feed(
            content = content,
            thinkingEnabled = true,
            onText = { routeToolText(it, downstream) },
            onThought = { emitInlineThought(it, downstream) },
        )
    }

    private suspend fun routeStructuredThought(
        event: StreamEvent.ThoughtChunk,
        downstream: suspend (StreamEvent) -> Unit,
    ) {
        val wasInThinking = structuredThinking.inThinking
        structuredMetadata = ThoughtMetadata(event.title, event.signature)
        if (event.thought.isEmpty()) {
            if (wasInThinking || event.title != null || event.signature != null) {
                emitNativeThought("", event.title, event.signature, downstream)
            }
            return
        }

        var emittedThought = false
        val pendingText = mutableListOf<String>()
        structuredThinking.feed(
            content = event.thought,
            thinkingEnabled = true,
            onText = { text ->
                if (text.isNotEmpty()) pendingText += text
            },
            onThought = { thought ->
                emittedThought = true
                emitNativeThought(thought, event.title, event.signature, downstream)
            },
        )
        if (!emittedThought && wasInThinking && (event.title != null || event.signature != null)) {
            emitNativeThought("", event.title, event.signature, downstream)
        }
        pendingText.forEach { text -> routeProviderText(text, downstream) }
    }

    private suspend fun flushThinking(downstream: suspend (StreamEvent) -> Unit) {
        flushStructuredThinking(downstream)
        flushInlineThinking(downstream)
    }

    private suspend fun flushStructuredThinking(downstream: suspend (StreamEvent) -> Unit) {
        structuredThinking.flush(
            thinkingEnabled = true,
            onText = { routeProviderText(it, downstream) },
            onThought = {
                emitNativeThought(
                    it,
                    structuredMetadata.title,
                    structuredMetadata.signature,
                    downstream,
                )
            },
        )
    }

    private suspend fun flushInlineThinking(downstream: suspend (StreamEvent) -> Unit) {
        inlineThinking.flush(
            thinkingEnabled = true,
            onText = { routeToolText(it, downstream) },
            onThought = { emitInlineThought(it, downstream) },
        )
    }

    private suspend fun emitInlineThought(
        content: String,
        downstream: suspend (StreamEvent) -> Unit,
    ) {
        if (content.isEmpty()) return
        inlineThought.append(content)
        val inline = inlineThought.toString()
        val native = nativeThought.toString()
        val target = when {
            native.isEmpty() -> inline
            native.startsWith(inline) -> ""
            inline.startsWith(native) -> inline.removePrefix(native)
            else -> inline
        }
        extension(target, inlineVisibleProjection).takeIf(String::isNotEmpty)?.let {
            downstream(StreamEvent.ThoughtChunk(it))
        }
        inlineVisibleProjection = target
    }

    private suspend fun emitNativeThought(
        content: String,
        title: String?,
        signature: String?,
        downstream: suspend (StreamEvent) -> Unit,
    ) {
        if (content.isNotEmpty()) nativeThought.append(content)
        val native = nativeThought.toString()
        val inline = inlineThought.toString()
        val target = when {
            inline.isEmpty() -> native
            inline.startsWith(native) -> ""
            native.startsWith(inline) -> native.removePrefix(inline)
            else -> native
        }
        val delta = extension(target, nativeVisibleProjection)
        if (delta.isNotEmpty()) {
            downstream(StreamEvent.ThoughtChunk(delta, title, signature))
        } else if (title != null || signature != null) {
            downstream(StreamEvent.ThoughtChunk("", title, signature))
        }
        nativeVisibleProjection = target
    }

    private fun extension(target: String, previous: String): String =
        if (target.startsWith(previous)) target.substring(previous.length) else target

    private suspend fun routeToolText(
        content: String,
        downstream: suspend (StreamEvent) -> Unit,
    ) {
        val parser = textToolParser
        if (parser == null || content.isEmpty()) {
            if (content.isNotEmpty()) downstream(StreamEvent.TextChunk(content))
            return
        }
        when (toolProbe) {
            ToolProbe.TEXT -> downstream(StreamEvent.TextChunk(content))
            ToolProbe.TAGGED -> feedTagged(content, downstream)
            ToolProbe.JSON -> toolProbeBuffer.append(content)
            ToolProbe.UNDECIDED -> {
                toolProbeBuffer.append(content)
                decideToolProbe(downstream)
            }
        }
    }

    private suspend fun decideToolProbe(downstream: suspend (StreamEvent) -> Unit) {
        val raw = toolProbeBuffer.toString()
        val candidate = raw.trimStart()
        if (candidate.isEmpty()) return
        if (candidate.first() == '{' || candidate.first() == '[') {
            toolProbe = ToolProbe.JSON
            return
        }
        val lower = candidate.lowercase()
        if (TAGGED_PREFIXES.any(lower::startsWith)) {
            toolProbe = ToolProbe.TAGGED
            toolProbeBuffer.clear()
            feedTagged(raw, downstream)
            return
        }
        if (TAGGED_PREFIXES.any { it.startsWith(lower) }) return
        toolProbe = ToolProbe.TEXT
        toolProbeBuffer.clear()
        downstream(StreamEvent.TextChunk(raw))
    }

    private suspend fun feedTagged(
        content: String,
        downstream: suspend (StreamEvent) -> Unit,
    ) {
        checkNotNull(textToolParser).feed(
            content = content,
            onText = { taggedResidualText.append(it) },
            onUpdate = {},
            onComplete = { addTextCandidate(it) },
            onMalformed = { reportMalformed(it) },
        )
    }

    private fun addTextCandidate(call: StreamingTextToolCallParser.CompletedCall) {
        textToolCandidates += TextToolCandidate(
            streamKey = call.streamKey,
            id = call.id,
            name = call.name,
            arguments = call.arguments,
        )
    }

    private suspend fun finishTextTools(downstream: suspend (StreamEvent) -> Unit) {
        when (toolProbe) {
            ToolProbe.UNDECIDED, ToolProbe.TEXT -> {
                if (toolProbeBuffer.isNotEmpty()) {
                    downstream(StreamEvent.TextChunk(toolProbeBuffer.toString()))
                    toolProbeBuffer.clear()
                }
            }
            ToolProbe.JSON -> finishJsonTool(downstream)
            ToolProbe.TAGGED -> {
                checkNotNull(textToolParser).flush(
                    onText = { taggedResidualText.append(it) },
                    onUpdate = {},
                    onComplete = { addTextCandidate(it) },
                    onMalformed = { reportMalformed(it) },
                )
                if (taggedResidualText.isNotBlank()) {
                    reportMalformed(
                        "Tagged tool payload contained ordinary text",
                        taggedResidualText.toString(),
                    )
                }
            }
        }
    }

    private suspend fun finishJsonTool(downstream: suspend (StreamEvent) -> Unit) {
        val raw = toolProbeBuffer.toString()
        val parsed = ToolCallTextParser.parse(raw)
        if (parsed.isEmpty()) {
            if (looksLikeExplicitJsonTool(raw)) {
                reportMalformed("Whole-content tool payload was malformed", raw)
            } else {
                downstream(StreamEvent.TextChunk(raw))
            }
            return
        }
        parsed.forEachIndexed { index, call ->
            textToolCandidates += TextToolCandidate(
                streamKey = "text_tool_${UUID.randomUUID()}",
                id = call.id,
                name = call.name,
                arguments = call.arguments,
            )
        }
    }

    private suspend fun releaseTextTools(downstream: suspend (StreamEvent) -> Unit) {
        if (textToolCandidates.isEmpty() && malformedTextCalls.isEmpty()) return
        val seenIds = nativeCallIds.toMutableSet()
        val calls = mutableListOf<StreamEvent.ToolCallRequest>()
        for (candidate in textToolCandidates) {
            // A tool that was not offered is the model's mistake, exactly as for native calls: the
            // call is still pairable, so it is released and the tool executor answers it with an
            // error result the model can correct from. The text parser only yields object
            // arguments, and should one ever slip through the executor rejects it the same way.
            // Only identity problems, which make a result impossible to pair, stay fatal below.
            val fingerprint = fingerprint(candidate.name, candidate.arguments)
            val id = candidate.id ?: "call_text_${UUID.randomUUID()}"
            if (!id.matches(safeWireToolCallId)) {
                calls += malformedToolCallRequest(
                    cause = "Text tool payload contained an invalid or duplicate id",
                    originalName = candidate.name,
                    originalArguments = candidate.arguments,
                    streamKey = candidate.streamKey,
                )
                continue
            }
            if (candidate.id in nativeCallIds) {
                if (nativeFingerprintsById[candidate.id] == fingerprint) continue
                calls += malformedToolCallRequest(
                    cause = "Text tool payload contained an invalid or duplicate id",
                    originalName = candidate.name,
                    originalArguments = candidate.arguments,
                    streamKey = candidate.streamKey,
                )
                continue
            }
            if (fingerprint != null && fingerprint in nativeFingerprints) continue
            if (!seenIds.add(id)) {
                calls += malformedToolCallRequest(
                    cause = "Text tool payload contained an invalid or duplicate id",
                    originalName = candidate.name,
                    originalArguments = candidate.arguments,
                    streamKey = candidate.streamKey,
                )
                continue
            }
            calls += StreamEvent.ToolCallRequest(
                id = id,
                name = candidate.name,
                arguments = candidate.arguments.ifBlank { "{}" },
                streamKey = candidate.streamKey,
            )
        }
        // Unparsable payloads are answered as malformed calls after the parsable ones.
        calls += malformedTextCalls
        malformedTextCalls.clear()
        if (calls.size == 1) downstream(calls.single())
        if (calls.size > 1) downstream(StreamEvent.ToolCallsRequest(calls))
    }

    private fun recordNativeCall(call: StreamEvent.ToolCallRequest) {
        nativeCallIds += call.id
        val fingerprint = fingerprint(call.name, call.arguments)
        nativeFingerprintsById[call.id] = fingerprint
        fingerprint?.let(nativeFingerprints::add)
    }

    private fun fingerprint(name: String, arguments: String): ToolFingerprint? {
        val parsed = runCatching {
            json.parseToJsonElement(arguments.ifBlank { "{}" }) as? JsonObject
        }.getOrNull() ?: return null
        return ToolFingerprint(name, canonicalize(parsed).toString())
    }

    private fun canonicalize(element: JsonElement): JsonElement = when (element) {
        is JsonObject -> JsonObject(
            element.entries.sortedBy { it.key }.associate { (key, value) ->
                key to canonicalize(value)
            }
        )
        is JsonArray -> JsonArray(element.map(::canonicalize))
        else -> element
    }

    /**
     * Records one unparsable text tool payload. Instead of failing the run, it is released as a
     * malformed-call stand-in the executor answers with an error, so the model can re-issue it.
     * Later defects in the same payload repeat the first one and are not reported again.
     */
    private fun reportMalformed(cause: String, raw: String? = null) {
        if (malformedTextTool) return
        malformedTextTool = true
        malformedTextCalls += malformedToolCallRequest(cause = cause, originalArguments = raw)
    }

    private suspend fun flushAbortedText(downstream: suspend (StreamEvent) -> Unit) {
        if (toolProbe in setOf(ToolProbe.UNDECIDED, ToolProbe.JSON) && toolProbeBuffer.isNotEmpty()) {
            downstream(StreamEvent.TextChunk(toolProbeBuffer.toString()))
            toolProbeBuffer.clear()
        }
    }

    private fun looksLikeExplicitJsonTool(content: String): Boolean {
        val compact = content.trimStart()
        return compact.contains("\"name\"") ||
            compact.contains("\"function\"") ||
            compact.contains("\"arguments\"") ||
            compact.contains("\"parameters\"")
    }

    private companion object {
        val TAGGED_PREFIXES = listOf("<tool_call>", "<invoke", "<antml:invoke")
    }
}
