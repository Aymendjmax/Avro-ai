package com.newoether.agora.viewmodel

import com.newoether.agora.api.util.findImplicitThinkingClose
import com.newoether.agora.model.MessageSegment
import com.newoether.agora.model.Participant

internal data class PersistedThinkingBoundaryProjection(
    val text: String,
    val thoughts: String?,
    val segments: List<MessageSegment>?,
)

/**
 * Read-only recovery for rows written before the provider thought-boundary normalizer existed.
 *
 * The preconditions deliberately fail closed: a real answer in either the durable text column or
 * an answer segment always wins, and a close marker must leave a non-blank suffix to recover.
 */
internal fun recoverPersistedThinkingBoundary(
    participant: Participant,
    text: String,
    thoughts: String?,
    segments: List<MessageSegment>?,
): PersistedThinkingBoundaryProjection {
    val unchanged = PersistedThinkingBoundaryProjection(text, thoughts, segments)
    if (
        participant != Participant.MODEL ||
        text.isNotBlank() ||
        segments == null ||
        segments.any { segment -> segment.type == "answer" && segment.content.isNotBlank() }
    ) {
        return unchanged
    }

    var recovered = false
    val normalizedSegments = buildList {
        segments.forEach { segment ->
            if (recovered || segment.type != "thought") {
                add(segment)
                return@forEach
            }
            val boundary = findImplicitThinkingClose(segment.content)
            if (boundary == null) {
                add(segment)
                return@forEach
            }
            val answer = segment.content.substring(boundary.endExclusive)
            if (answer.isBlank()) {
                add(segment)
                return@forEach
            }

            recovered = true
            add(segment.copy(content = segment.content.substring(0, boundary.startIndex)))
            add(MessageSegment(type = "answer", content = answer))
        }
    }
    if (!recovered) return unchanged

    val recoveredText = normalizedSegments
        .asSequence()
        .filter { segment -> segment.type == "answer" }
        .joinToString(separator = "") { segment -> segment.content }
    val recoveredThoughts = normalizedSegments
        .asSequence()
        .filter { segment -> segment.type == "thought" }
        .joinToString(separator = "") { segment -> segment.content }
        .takeIf { value -> value.isNotBlank() }

    return PersistedThinkingBoundaryProjection(
        text = recoveredText,
        thoughts = recoveredThoughts,
        segments = normalizedSegments,
    )
}
