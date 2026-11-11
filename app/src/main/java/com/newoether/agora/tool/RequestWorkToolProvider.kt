package com.newoether.agora.tool

import com.newoether.agora.api.ToolDefinition
import com.newoether.agora.api.ToolFunction
import com.newoether.agora.api.ToolParameters
import com.newoether.agora.api.ToolProperty
import com.newoether.agora.model.ConversationMode
import com.newoether.agora.viewmodel.GenerationContext
import com.newoether.agora.viewmodel.RequestWorkController
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Exposes the `request_work` tool to models running in [ConversationMode.ASK].
 *
 * When invoked, prompts the user with an interactive UI card to switch the conversation
 * to [ConversationMode.WORK], enabling shell execution, Alpine Linux PRoot sandbox,
 * and filesystem operations.
 */
class RequestWorkToolProvider(
    private val controller: RequestWorkController,
) : ToolProvider {

    override fun definitions(ctx: GenerationContext): List<ToolDefinition> {
        // Only available in ASK mode when enabled in settings. In WORK mode, shell/file capabilities are already active.
        if (ctx.conversationMode != ConversationMode.ASK || !ctx.requestWorkToolEnabled) return emptyList()

        return listOf(
            ToolDefinition(
                function = ToolFunction(
                    name = TOOL_NAME,
                    description = "Request the user to transition this conversation into Work mode. " +
                        "Use this tool ONLY when the user's request requires executing shell/terminal commands, " +
                        "running scripts, creating/editing files on disk, installing packages via apk, " +
                        "or building projects that cannot be fulfilled purely conversationally.",
                    parameters = ToolParameters(
                        properties = mapOf(
                            "reason" to ToolProperty(
                                type = "string",
                                description = "A concise, polite explanation in the user's language explaining " +
                                    "specifically why Work mode and terminal execution are needed to accomplish the task.",
                            ),
                        ),
                        required = listOf("reason"),
                    ),
                ),
            ),
        )
    }

    override fun handles(name: String): Boolean = name == TOOL_NAME

    override suspend fun execute(
        name: String,
        arguments: String,
        ctx: GenerationContext,
    ): String {
        val args = runCatching { Json.parseToJsonElement(arguments.ifBlank { "{}" }) as JsonObject }.getOrNull()
        val reason = (args?.get("reason") as? JsonPrimitive)?.content?.takeIf { it.isNotBlank() }
            ?: "Work mode required for terminal execution and file operations."
        val conversationId = ctx.conversationId ?: ""

        val accepted = controller.requestWork(conversationId, reason)
        return buildJsonObject {
            put("status", if (accepted) "accepted" else "declined")
            put(
                "detail",
                if (accepted) {
                    "User approved transition to Work mode. Shell tools, file tools, and the Alpine Linux sandbox " +
                        "are now active for this conversation. You can now execute terminal commands and manipulate files."
                } else {
                    "The user declined to transition to Work mode. Remain in conversational Ask mode. " +
                        "Do NOT attempt to invoke shell or file tools. Fulfill the user's request conversationally."
                },
            )
        }.toString()
    }

    companion object {
        const val TOOL_NAME = "request_work"
    }
}
