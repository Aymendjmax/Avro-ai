package com.newoether.agora.mcp

import com.newoether.agora.api.ToolDefinition
import com.newoether.agora.api.ToolFunction
import com.newoether.agora.api.ToolParameters
import com.newoether.agora.api.ToolProperty
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import java.security.MessageDigest

enum class McpConnectionStatus {
    IDLE,
    CONNECTING,
    CONNECTED,
    ERROR,
}

data class McpRemoteTool(
    val name: String,
    val description: String,
    val inputSchema: JsonObject,
)

data class McpToolDescriptor(
    val publicName: String,
    val serverId: String,
    val serverName: String,
    val remote: McpRemoteTool,
    val enabled: Boolean = true,
) {
    fun asToolDefinition(): ToolDefinition = ToolDefinition(
        function = ToolFunction(
            name = publicName,
            description = buildString {
                append(remote.description.ifBlank { "MCP tool ${remote.name}" })
                append("\n\nProvided by MCP server: ")
                append(serverName)
            },
            parameters = remote.inputSchema.toToolParameters(),
        ),
    )
}

data class McpServerSnapshot(
    val serverId: String,
    val status: McpConnectionStatus = McpConnectionStatus.IDLE,
    val tools: List<McpToolDescriptor> = emptyList(),
    val error: String? = null,
    val lastSyncedAt: Long? = null,
)

data class McpImagePayload(
    val data: String,
    val mimeType: String,
)

data class McpCallPayload(
    val textParts: List<String>,
    val images: List<McpImagePayload>,
    val structuredContent: JsonElement?,
    val isError: Boolean,
)

internal fun publicMcpToolName(serverId: String, remoteName: String): String {
    val serverKey = serverId.filter(Char::isLetterOrDigit).take(10).ifBlank { "server" }
    val toolKey = remoteName
        .map { if (it.isLetterOrDigit() || it == '_' || it == '-') it else '_' }
        .joinToString("")
        .trim('_')
        .ifBlank { "tool" }
    val digest = MessageDigest.getInstance("SHA-256")
        .digest(remoteName.toByteArray())
        .take(3)
        .joinToString("") { "%02x".format(it) }
    val prefix = "mcp_${serverKey}_"
    val suffix = "_$digest"
    return prefix + toolKey.take((64 - prefix.length - suffix.length).coerceAtLeast(1)) + suffix
}

private fun JsonObject.toToolParameters(): ToolParameters {
    val properties = (this["properties"] as? JsonObject)
        ?.entries
        ?.sortedBy { it.key }
        ?.associate { (name, schema) ->
            name to (schema as? JsonObject).toToolProperty()
        }
        .orEmpty()
    val required = (this["required"] as? JsonArray)
        ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
        .orEmpty()
        .filter(properties::containsKey)
    val type = (this["type"] as? JsonPrimitive)?.contentOrNull ?: "object"
    return ToolParameters(
        type = type,
        properties = properties,
        required = required,
        schema = toWireSchema(type),
    )
}
/**
 * The MCP input schema as providers receive it: the server's own schema, untouched below the top
 * level. Only the top level is made a well-formed parameter object, which every provider demands:
 * an explicit type, a properties map, and required naming only declared properties. The `$schema`
 * dialect marker is dropped because it is metadata some OpenAI-compatible endpoints reject.
 */
private fun JsonObject.toWireSchema(type: String): JsonObject {
    val properties = this["properties"] as? JsonObject ?: JsonObject(emptyMap())
    val required = (this["required"] as? JsonArray)?.filter { entry ->
        (entry as? JsonPrimitive)?.contentOrNull?.let(properties::containsKey) == true
    }
    return JsonObject(
        buildMap {
            putAll(this@toWireSchema)
            remove("\$schema")
            put("type", JsonPrimitive(type))
            put("properties", properties)
            if (required != null) put("required", JsonArray(required))
        },
    )
}

private fun JsonObject?.toToolProperty(): ToolProperty {
    if (this == null) return ToolProperty(type = "string", description = "")
    val declaredType = when (val type = this["type"]) {
        is JsonPrimitive -> type.contentOrNull
        is JsonArray -> type.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
            .firstOrNull { it != "null" }
        else -> null
    }
    return ToolProperty(
        type = declaredType ?: inferSchemaType(this),
        description = (this["description"] as? JsonPrimitive)?.contentOrNull.orEmpty(),
        items = (this["items"] as? JsonObject)?.toToolProperty(),
    )
}

private fun inferSchemaType(schema: JsonObject): String = when {
    schema["properties"] is JsonObject -> "object"
    schema["items"] is JsonObject -> "array"
    else -> "string"
}

internal fun JsonElement.asObjectOrNull(): JsonObject? =
    runCatching { jsonObject }.getOrNull()
