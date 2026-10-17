package com.newoether.agora.tool

import com.newoether.agora.util.Constants
import com.newoether.agora.util.ShellFileReadResult
import com.newoether.agora.util.shellFileLineCount
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

internal fun boundedFileReadJson(
    server: String,
    path: String,
    result: ShellFileReadResult,
    maxLength: Int = Constants.MAX_TOOL_RESULT_LENGTH,
): String {
    require(maxLength > 0)

    fun encode(content: String, truncated: Boolean): String = buildJsonObject {
        put("type", "file_read")
        put("server", server)
        put("path", path)
        put("content", content)
        put("lines", shellFileLineCount(content))
        put("total_lines", result.totalLines)
        put("total_bytes", result.totalBytes)
        put("returned_bytes", content.toByteArray(Charsets.UTF_8).size)
        put("offset", result.offset)
        put("limit", result.limit)
        put("truncated", truncated)
    }.toString()

    val fullResult = encode(result.content, result.truncated)
    if (fullResult.length <= maxLength) return fullResult

    var low = 0
    var high = result.content.length
    var best = encode("", truncated = true)
    while (low <= high) {
        val midpoint = low + (high - low) / 2
        val safeLength = if (
            midpoint in 1 until result.content.length &&
            result.content[midpoint - 1].isHighSurrogate() &&
            result.content[midpoint].isLowSurrogate()
        ) {
            midpoint - 1
        } else {
            midpoint
        }
        val candidate = encode(result.content.substring(0, safeLength), truncated = true)
        if (candidate.length <= maxLength) {
            best = candidate
            low = midpoint + 1
        } else {
            high = midpoint - 1
        }
    }
    return best
}

