package com.newoether.agora.api.openai

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

internal fun extractLastJsonObject(text: String, json: Json): String? {
    val trimmed = text.trim()
    if (trimmed.isEmpty()) return null
    var endIndex = trimmed.lastIndexOf('}')
    while (endIndex != -1) {
        var depth = 0
        var inString = false
        var startIndex = -1
        for (i in endIndex downTo 0) {
            val c = trimmed[i]
            if (c == '"' && (i == 0 || trimmed[i - 1] != '\\')) {
                inString = !inString
            } else if (!inString) {
                if (c == '}') depth++
                else if (c == '{') {
                    depth--
                    if (depth == 0) {
                        startIndex = i
                        break
                    }
                }
            }
        }
        if (startIndex != -1) {
            val candidate = trimmed.substring(startIndex, endIndex + 1)
            if (runCatching { json.parseToJsonElement(candidate) is JsonObject }.getOrDefault(false)) {
                return candidate
            }
        }
        endIndex = trimmed.lastIndexOf('}', endIndex - 1)
    }
    return null
}
