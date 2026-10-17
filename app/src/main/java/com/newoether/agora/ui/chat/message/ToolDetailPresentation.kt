package com.newoether.agora.ui.chat.message

import android.content.res.Resources
import com.newoether.agora.R
import com.newoether.agora.model.CitationPolicy
import com.newoether.agora.model.MessageSegment
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull

/** Content decisions shared by Compose and the WebUI; lifecycle stays in ToolPresentationResolver. */
internal data class ToolDetailPresentation(
    val kind: ToolKind,
    val arguments: String?,
    val argumentsLabel: String,
    val resultLabel: String,
    val mcpDevice: String?,
    val body: ToolDetailBody,
)

internal sealed interface ToolDetailBody {
    data class Active(val text: String, val output: String?) : ToolDetailBody
    data class Failed(val text: String, val output: String?) : ToolDetailBody
    data class Stopped(val text: String) : ToolDetailBody
    data class Muted(val text: String) : ToolDetailBody
    data class JsonContent(val values: List<String>) : ToolDetailBody
    data class Shell(val status: String, val device: String, val error: String?, val output: String) : ToolDetailBody
    data class Paths(val values: List<String>) : ToolDetailBody
    data class Grep(val groups: List<ToolDetailMatchGroup>) : ToolDetailBody
    data class FileContent(
        val path: String?,
        val lineCount: String?,
        val content: String,
        val emptyText: String,
        val truncationText: String?,
    ) : ToolDetailBody
    data class Search(val results: List<ToolDetailSearchResult>) : ToolDetailBody
}

internal data class ToolDetailMatch(val line: Int?, val content: String)
internal data class ToolDetailMatchGroup(val path: String, val matches: List<ToolDetailMatch>)
internal data class ToolDetailSearchResult(
    val title: String,
    val url: String?,
    val safeUrl: String?,
    val snippet: String?,
)

internal fun Resources.toolDetailPresentation(segment: MessageSegment): ToolDetailPresentation {
    val presentation = ToolPresentationResolver.resolve(segment)
    return ToolDetailPresentation(
        kind = presentation.kind,
        arguments = presentation.rawArguments?.takeIf { it.isNotBlank() && it != "{}" },
        argumentsLabel = getString(R.string.arguments_label),
        resultLabel = getString(R.string.result_label),
        mcpDevice = presentation.device?.takeIf(String::isNotBlank),
        body = toolDetailBody(presentation),
    )
}

private fun Resources.toolDetailBody(presentation: ToolPresentation): ToolDetailBody {
    if (presentation.kind in setOf(ToolKind.SHELL_EXECUTE, ToolKind.SHELL_JOB_GET, ToolKind.SHELL_JOB_WAIT)) {
        return ToolDetailBody.Shell(
            status = shellExecutionSummary(presentation),
            device = presentation.device?.takeIf(String::isNotBlank) ?: getString(R.string.tool_unknown_device),
            error = presentation.errorMessage?.takeIf { presentation.state == ToolPresentationState.FAILED && it.isNotBlank() },
            output = shellOutputText(presentation) ?: getString(R.string.tool_no_output),
        )
    }
    return when (presentation.state) {
        ToolPresentationState.CALLING -> ToolDetailBody.Active(toolSummary(presentation), presentation.liveOutput)
        ToolPresentationState.RUNNING,
        ToolPresentationState.BACKGROUND_RUNNING -> ToolDetailBody.Active(
            toolSummary(presentation), presentation.liveOutput ?: (presentation.result as? JsonObject).string("output"),
        )
        ToolPresentationState.FAILED -> ToolDetailBody.Failed(
            presentation.errorMessage ?: getString(R.string.tool_call_failed), presentation.liveOutput,
        )
        ToolPresentationState.STOPPED -> ToolDetailBody.Stopped(stoppedToolSummary(presentation))
        ToolPresentationState.EMPTY,
        ToolPresentationState.COMPLETED -> completedToolDetailBody(presentation)
    }
}

private fun Resources.completedToolDetailBody(presentation: ToolPresentation): ToolDetailBody {
    val result = presentation.result as? JsonObject
    return when (presentation.kind) {
        ToolKind.FILE_GLOB -> {
            val paths = (result?.get("files") as? JsonArray).orEmpty().map { value ->
                (value as? JsonPrimitive)?.contentOrNull ?: value.toString()
            }
            if (paths.isEmpty()) ToolDetailBody.Muted(getString(R.string.tool_found_no_files))
            else ToolDetailBody.Paths(paths)
        }
        ToolKind.FILE_GREP -> {
            val groups = (result?.get("matches") as? JsonArray).orEmpty().mapNotNull { value ->
                val item = value as? JsonObject ?: return@mapNotNull null
                item.string("path").orEmpty() to ToolDetailMatch(item.int("line"), item.string("content").orEmpty())
            }.groupBy({ it.first }, { it.second }).map { (path, matches) ->
                ToolDetailMatchGroup(path.ifBlank { getString(R.string.file_path_unknown) }, matches)
            }
            if (groups.isEmpty()) ToolDetailBody.Muted(getString(R.string.tool_found_no_matches))
            else ToolDetailBody.Grep(groups)
        }
        ToolKind.FILE_READ -> {
            val path = result.string("path") ?: presentation.subject
            val content = result.string("content").orEmpty()
            val truncationText = if (result.string("truncated")?.toBooleanStrictOrNull() == true) {
                val nextOffset = (result.long("offset") ?: 0L) +
                    (result.long("returned_bytes") ?: content.toByteArray(Charsets.UTF_8).size.toLong())
                getString(R.string.tool_read_file_truncated, nextOffset)
            } else null
            ToolDetailBody.FileContent(
                path = path,
                lineCount = result.int("lines")?.let { getString(R.string.tool_line_count, it) },
                content = content,
                emptyText = if (path == null) getString(R.string.tool_read_file_empty_default)
                    else getString(R.string.tool_read_file_empty, path),
                truncationText = truncationText,
            )
        }
        ToolKind.WEB_SEARCH -> {
            val results = (result?.get("results") as? JsonArray).orEmpty().mapIndexed { index, value ->
                val item = value as? JsonObject
                val url = item.string("url") ?: item.string("href")
                ToolDetailSearchResult(
                    title = item.string("title") ?: getString(R.string.tool_web_result, index + 1),
                    url = url,
                    safeUrl = CitationPolicy.safeHttpUrl(url),
                    snippet = item.string("snippet") ?: item.string("description")
                        ?: item.string("content") ?: item.string("body"),
                )
            }
            if (results.isEmpty()) ToolDetailBody.Muted(toolSummary(presentation))
            else ToolDetailBody.Search(results)
        }
        ToolKind.MCP -> {
            val values = listOfNotNull(
                presentation.rawTextResult?.takeIf(String::isNotBlank),
                presentation.rawStructuredResult?.takeIf(String::isNotBlank),
            )
            if (values.isEmpty()) rawToolDetailBody(presentation) else ToolDetailBody.JsonContent(values)
        }
        else -> rawToolDetailBody(presentation)
    }
}

private fun Resources.rawToolDetailBody(presentation: ToolPresentation): ToolDetailBody =
    if (presentation.rawResult.isNullOrEmpty()) ToolDetailBody.Muted(toolSummary(presentation))
    else ToolDetailBody.JsonContent(listOf(presentation.rawResult))

internal fun shellOutputText(presentation: ToolPresentation): String? {
    val result = presentation.result as? JsonObject
    val completedOutput = result.string("output")?.takeIf(String::isNotBlank)
        ?: listOfNotNull(
            result.string("stdout")?.takeIf(String::isNotBlank),
            result.string("stderr")?.takeIf(String::isNotBlank),
        ).takeIf(List<String>::isNotEmpty)?.joinToString("\n")
    if (completedOutput != null) return completedOutput
    return presentation.liveOutput?.takeIf(String::isNotBlank)?.takeUnless { output ->
        output.startsWith("Connecting to ") || output == "Starting durable background job"
    }
}

private fun JsonObject?.string(key: String): String? =
    (this?.get(key) as? JsonPrimitive)?.contentOrNull
private fun JsonObject?.int(key: String): Int? =
    (this?.get(key) as? JsonPrimitive)?.intOrNull
private fun JsonObject?.long(key: String): Long? = string(key)?.toLongOrNull()
