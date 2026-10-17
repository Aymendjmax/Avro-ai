package com.newoether.agora.data

import com.newoether.agora.model.CitationAnchor
import com.newoether.agora.model.CitationPolicy
import com.newoether.agora.model.CitationRecord
import com.newoether.agora.model.MessagePersistenceGuard
import com.newoether.agora.model.toMessageSegment
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull

internal data class ImportedCitationText(
    val text: String,
    val citations: List<CitationRecord>,
)

private data class ImportedSource(
    val ids: Set<String>,
    val title: String?,
    val url: String?,
    val fileName: String?,
    val location: String?,
    val excerpt: String?,
    val matchedText: String?,
    val startIndex: Int?,
    val endIndex: Int?,
)

private data class PendingMarker(
    val answerEnd: Int,
    val kind: String,
    val sourceId: String,
)

private val privateMarker = Regex(
    "\uE200(cite|filecite)\uE202([^\uE200\uE201]+)\uE201|【(turn\\d+[a-z]+\\d+)】",
    RegexOption.IGNORE_CASE,
)
private val privateSourceId = Regex("turn\\d+[a-z]+\\d+", RegexOption.IGNORE_CASE)

internal fun projectChatGptCitations(
    rawText: String,
    references: List<JsonElement>,
): ImportedCitationText {
    val sources = references.mapNotNull(JsonElement::toImportedSource)
    val sourcesById = buildMap<String, ImportedSource> {
        sources.forEach { source ->
            source.ids.forEach { id -> putIfAbsent(id.lowercase(), source) }
        }
    }
    val cleaned = StringBuilder(rawText.length)
    val pending = mutableListOf<PendingMarker>()
    var cursor = 0
    privateMarker.findAll(rawText).forEach { match ->
        cleaned.append(rawText, cursor, match.range.first)
        val explicitKind = match.groupValues[1].ifBlank { "cite" }
        val ids = match.groupValues[2].takeIf(String::isNotBlank)
            ?.split('\uE202')
            ?.map(String::trim)
            ?.filter(String::isNotBlank)
            ?: listOf(match.groupValues[3]).filter(String::isNotBlank)
        ids.forEach { id ->
            pending += PendingMarker(
                answerEnd = cleaned.length,
                kind = explicitKind,
                sourceId = id,
            )
        }
        cursor = match.range.last + 1
    }
    cleaned.append(rawText, cursor, rawText.length)
    val answer = CitationPolicy.stripPrivateMarkers(cleaned.toString())
    val citations = pending.mapNotNull { marker ->
        val source = sourcesById[marker.sourceId.lowercase()] ?: return@mapNotNull null
        source.toCitationRecord(
            provider = "openai",
            privateId = marker.sourceId,
            forceFile = marker.kind.equals("filecite", ignoreCase = true),
            answerText = answer,
            markerEnd = marker.answerEnd,
        )
    }
    return ImportedCitationText(
        text = answer,
        citations = CitationPolicy.deduplicate(citations, answer),
    )
}

internal fun projectClaudeCitation(
    citation: JsonElement,
    answerStartIndex: Int,
    answerText: String,
): CitationRecord? {
    val source = citation.toImportedSource() ?: return null
    return source.toCitationRecord(
        provider = "anthropic",
        privateId = source.ids.firstOrNull(),
        forceFile = source.fileName != null,
        answerText = answerText,
        markerEnd = answerText.length,
        anchorStartOverride = answerStartIndex,
    )
}

internal fun encodeImportedCitations(
    citations: Iterable<CitationRecord>,
    answerText: String,
): String? = MessagePersistenceGuard.encodeSegmentsBounded(
    CitationPolicy.deduplicate(citations, answerText).map(CitationRecord::toMessageSegment),
)

private fun ImportedSource.toCitationRecord(
    provider: String,
    privateId: String?,
    forceFile: Boolean,
    answerText: String,
    markerEnd: Int,
    anchorStartOverride: Int? = null,
): CitationRecord? {
    if (url == null && fileName == null && title == null) return null
    val anchor = when {
        anchorStartOverride != null && answerText.isNotEmpty() -> CitationAnchor(
            startIndex = anchorStartOverride,
            endIndex = anchorStartOverride + answerText.length,
            citedText = answerText,
        )
        else -> resolveImportedAnchor(answerText, markerEnd)
    }
    return CitationPolicy.create(
        provider = provider,
        kind = when {
            forceFile || fileName != null -> "file"
            CitationPolicy.safeHttpUrl(url) != null -> "url"
            else -> "document"
        },
        title = title,
        url = url,
        fileName = fileName,
        location = location,
        providerSourceId = privateId,
        excerpt = excerpt,
        anchors = listOfNotNull(anchor),
    )
}

private fun ImportedSource.resolveImportedAnchor(
    answerText: String,
    markerEnd: Int,
): CitationAnchor? {
    val matched = matchedText?.takeIf(String::isNotEmpty)
    if (
        matched != null &&
        startIndex != null &&
        endIndex != null &&
        startIndex >= 0 &&
        endIndex <= answerText.length &&
        endIndex > startIndex &&
        answerText.substring(startIndex, endIndex) == matched
    ) {
        return CitationAnchor(startIndex, endIndex, matched)
    }
    if (matched == null || markerEnd !in 0..answerText.length) return null
    val start = answerText.lastIndexOf(matched, startIndex = markerEnd)
    if (start < 0) return null
    val end = start + matched.length
    if (end > markerEnd || answerText.substring(end, markerEnd).isNotBlank()) return null
    return CitationAnchor(start, end, matched)
}

private fun JsonElement.toImportedSource(): ImportedSource? {
    val ids = linkedSetOf<String>()
    collectPrivateIds(ids)
    collectStructuredRefIds(ids)
    findNamedString("uuid", "source_id")?.let(ids::add)
    val title = findNamedString("title", "alt", "attribution", "name")
    val url = findNamedString("url", "uri", "link") ?: findSafeUrlCandidate()
    val fileName = findNamedString("file_name", "filename")
    val location = findNamedString("location")
    val excerpt = findNamedString("snippet", "excerpt", "cited_text")
    val matchedText = findNamedString("matched_text")
    return ImportedSource(
        ids = ids,
        title = title,
        url = url,
        fileName = fileName,
        location = location,
        excerpt = excerpt,
        matchedText = matchedText,
        startIndex = findNamedInt("start_idx", "start_index"),
        endIndex = findNamedInt("end_idx", "end_index"),
    ).takeIf {
        it.ids.isNotEmpty() || it.url != null || it.fileName != null || it.title != null
    }
}

private fun JsonElement.collectPrivateIds(output: MutableSet<String>) {
    when (this) {
        is JsonArray -> forEach { it.collectPrivateIds(output) }
        is JsonObject -> values.forEach { it.collectPrivateIds(output) }
        is JsonPrimitive -> if (isString) {
            privateSourceId.findAll(content).forEach { output += it.value }
        }
    }
}

private fun JsonElement.collectStructuredRefIds(output: MutableSet<String>) {
    when (this) {
        is JsonArray -> forEach { it.collectStructuredRefIds(output) }
        is JsonObject -> {
            val turn = primitiveInt("turn_index")
            val type = primitiveString("ref_type")
            val index = primitiveInt("ref_index")
            if (turn != null && type != null && index != null) {
                output += "turn$turn$type$index"
            }
            values.forEach { it.collectStructuredRefIds(output) }
        }
        is JsonPrimitive -> Unit
    }
}

private fun JsonElement.findNamedString(vararg names: String): String? = when (this) {
    is JsonArray -> firstNotNullOfOrNull { it.findNamedString(*names) }
    is JsonObject -> {
        names.firstNotNullOfOrNull { name -> primitiveString(name) }
            ?: values.firstNotNullOfOrNull { it.findNamedString(*names) }
    }
    is JsonPrimitive -> null
}

private fun JsonElement.findNamedInt(vararg names: String): Int? = when (this) {
    is JsonArray -> firstNotNullOfOrNull { it.findNamedInt(*names) }
    is JsonObject -> {
        names.firstNotNullOfOrNull { name -> primitiveInt(name) }
            ?: values.firstNotNullOfOrNull { it.findNamedInt(*names) }
    }
    is JsonPrimitive -> null
}

private fun JsonElement.findSafeUrlCandidate(): String? = when (this) {
    is JsonArray -> firstNotNullOfOrNull(JsonElement::findSafeUrlCandidate)
    is JsonObject -> {
        (this["safe_urls"] as? JsonArray)
            ?.firstNotNullOfOrNull { (it as? JsonPrimitive)?.content }
            ?: values.firstNotNullOfOrNull(JsonElement::findSafeUrlCandidate)
    }
    is JsonPrimitive -> null
}

private fun JsonObject.primitiveString(key: String): String? =
    (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf(String::isNotBlank)

private fun JsonObject.primitiveInt(key: String): Int? =
    (this[key] as? JsonPrimitive)?.intOrNull
