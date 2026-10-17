package com.newoether.agora.data

import android.util.JsonReader
import android.util.JsonToken
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** Reads one JSON value only; callers retain at most one exported entity at a time. */
internal fun Json.readNativeGraphValue(reader: JsonReader): JsonElement = when (reader.peek()) {
    JsonToken.BEGIN_OBJECT -> {
        val values = linkedMapOf<String, JsonElement>()
        reader.beginObject()
        while (reader.hasNext()) {
            values[reader.nextName()] = readNativeGraphValue(reader)
        }
        reader.endObject()
        JsonObject(values)
    }
    JsonToken.BEGIN_ARRAY -> {
        val values = mutableListOf<JsonElement>()
        reader.beginArray()
        while (reader.hasNext()) {
            values.add(readNativeGraphValue(reader))
        }
        reader.endArray()
        JsonArray(values)
    }
    JsonToken.STRING -> JsonPrimitive(reader.nextString())
    JsonToken.NUMBER -> parseToJsonElement(reader.nextString())
    JsonToken.BOOLEAN -> JsonPrimitive(reader.nextBoolean())
    JsonToken.NULL -> {
        reader.nextNull()
        JsonNull
    }
    else -> error("Unexpected JSON token ${reader.peek()}")
}
