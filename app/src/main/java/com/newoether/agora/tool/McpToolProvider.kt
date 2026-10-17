package com.newoether.agora.tool

import com.newoether.agora.api.ToolDefinition
import com.newoether.agora.mcp.McpRegistry
import com.newoether.agora.viewmodel.GenerationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

class McpToolProvider(
    private val registry: McpRegistry,
) : ToolProvider {
    override fun definitions(ctx: GenerationContext): List<ToolDefinition> =
        registry.enabledTools().map { it.asToolDefinition() }

    override fun handles(name: String): Boolean = registry.descriptor(name) != null

    override fun presentationMetadata(name: String): ToolPresentationMetadata? =
        registry.descriptor(name)?.let { descriptor ->
            ToolPresentationMetadata(
                displayName = descriptor.remote.name,
                target = descriptor.serverName,
            )
        }

    override suspend fun execute(
        name: String,
        arguments: String,
        ctx: GenerationContext,
    ): String = registry.execute(name, arguments).text

    override fun executeEvents(
        name: String,
        arguments: String,
        ctx: GenerationContext,
    ): Flow<ToolExecutionEvent> = flow {
        presentationMetadata(name)?.target?.let { target ->
            emit(ToolExecutionEvent.TargetResolved(target))
        }
        emit(ToolExecutionEvent.Progress("Calling MCP tool"))
        emit(ToolExecutionEvent.Completed(registry.execute(name, arguments)))
    }
}
