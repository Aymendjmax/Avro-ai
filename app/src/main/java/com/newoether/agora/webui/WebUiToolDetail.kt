package com.newoether.agora.webui

import android.content.res.Resources
import com.newoether.agora.R
import com.newoether.agora.model.MessageSegment
import com.newoether.agora.ui.chat.message.StreamingJsonArray
import com.newoether.agora.ui.chat.message.StreamingJsonNode
import com.newoether.agora.ui.chat.message.StreamingJsonObject
import com.newoether.agora.ui.chat.message.StreamingJsonParser
import com.newoether.agora.ui.chat.message.StreamingJsonScalar
import com.newoether.agora.ui.chat.message.StreamingJsonStatus
import com.newoether.agora.ui.chat.message.ToolDetailBody
import com.newoether.agora.ui.chat.message.ToolKind
import com.newoether.agora.ui.chat.message.isImageGenerationSegment
import com.newoether.agora.ui.chat.message.persistenceAwareJsonSource
import com.newoether.agora.ui.chat.message.toolDetailPresentation
import com.newoether.agora.ui.chat.message.visibleJsonRoots
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Wire projection only. Content decisions and JSON grammar belong to the shared Compose owners. */
internal fun Resources.webToolDetail(segment: MessageSegment): WebToolDetail {
    val detail = toolDetailPresentation(segment)
    return WebToolDetail(
        kind = detail.kind.name,
        argumentsLabel = detail.argumentsLabel,
        resultLabel = detail.resultLabel,
        arguments = detail.arguments?.let(::webToolDocument),
        mcpDevice = detail.mcpDevice.takeIf { detail.kind == ToolKind.MCP },
        body = when (val body = detail.body) {
            is ToolDetailBody.Active -> WebToolBody.Active(body.text, body.output)
            is ToolDetailBody.Failed -> WebToolBody.Failed(body.text, body.output)
            is ToolDetailBody.Stopped -> WebToolBody.Stopped(body.text)
            is ToolDetailBody.Muted -> WebToolBody.Muted(body.text)
            is ToolDetailBody.JsonContent -> WebToolBody.Documents(body.values.map(::webToolDocument))
            is ToolDetailBody.Shell -> WebToolBody.Shell(body.status, body.device, body.error, body.output)
            is ToolDetailBody.Paths -> WebToolBody.Paths(body.values)
            is ToolDetailBody.Grep -> WebToolBody.Grep(body.groups.map { group ->
                WebToolMatchGroup(group.path, group.matches.map { WebToolMatch(it.line, it.content) })
            })
            is ToolDetailBody.FileContent -> WebToolBody.FileContent(
                body.path, body.lineCount, body.content, body.emptyText, body.truncationText,
            )
            is ToolDetailBody.Search -> WebToolBody.Search(body.results.map {
                WebToolSearchResult(it.title, it.url, it.safeUrl, it.snippet)
            })
        },
        images = segment.toolImages.mapIndexedNotNull { index, image ->
            if (image.path.isBlank()) null else WebToolImage(index, image.width, image.height, image.sha256)
        },
        squareCrop = segment.isImageGenerationSegment(),
        imageLabel = getString(R.string.tool_view_image),
        imageFailedLabel = getString(R.string.attachment_copy_failed_image),
    )
}

internal fun webToolDocument(text: String): WebToolDocument {
    val source = persistenceAwareJsonSource(text)
    val document = StreamingJsonParser.parse(source)
    if (document.status == StreamingJsonStatus.INVALID) return WebToolDocument(text = text)
    return WebToolDocument(
        roots = visibleJsonRoots(document.roots).map(::webToolNode),
        marker = text.substring(source.length).trimStart().takeIf(String::isNotEmpty),
    )
}

private fun webToolNode(node: StreamingJsonNode): WebToolNode = when (node) {
    is StreamingJsonObject -> WebToolNode.Object(node.entries.map {
        WebToolEntry(it.key, it.keyComplete, it.value?.let(::webToolNode))
    }, node.complete)
    is StreamingJsonArray -> WebToolNode.Array(node.values.map(::webToolNode), node.complete)
    is StreamingJsonScalar -> WebToolNode.Scalar(node.content, node.kind.name, node.complete)
}

@Serializable
internal data class WebToolDetail(
    val kind: String,
    val argumentsLabel: String,
    val resultLabel: String,
    val arguments: WebToolDocument?,
    val mcpDevice: String?,
    val body: WebToolBody,
    val images: List<WebToolImage>,
    val squareCrop: Boolean,
    val imageLabel: String,
    val imageFailedLabel: String,
)

@Serializable
internal sealed interface WebToolBody {
    @Serializable @SerialName("active")
    data class Active(val text: String, val output: String?) : WebToolBody
    @Serializable @SerialName("failed")
    data class Failed(val text: String, val output: String?) : WebToolBody
    @Serializable @SerialName("stopped")
    data class Stopped(val text: String) : WebToolBody
    @Serializable @SerialName("muted")
    data class Muted(val text: String) : WebToolBody
    @Serializable @SerialName("documents")
    data class Documents(val values: List<WebToolDocument>) : WebToolBody
    @Serializable @SerialName("shell")
    data class Shell(val status: String, val device: String, val error: String?, val output: String) : WebToolBody
    @Serializable @SerialName("paths")
    data class Paths(val values: List<String>) : WebToolBody
    @Serializable @SerialName("grep")
    data class Grep(val groups: List<WebToolMatchGroup>) : WebToolBody
    @Serializable @SerialName("file")
    data class FileContent(
        val path: String?, val lineCount: String?, val content: String,
        val emptyText: String, val truncationText: String?,
    ) : WebToolBody
    @Serializable @SerialName("search")
    data class Search(val results: List<WebToolSearchResult>) : WebToolBody
}

@Serializable
internal data class WebToolMatchGroup(val path: String, val matches: List<WebToolMatch>)
@Serializable
internal data class WebToolMatch(val line: Int?, val content: String)
@Serializable
internal data class WebToolSearchResult(val title: String, val url: String?, val safeUrl: String?, val snippet: String?)
@Serializable
internal data class WebToolImage(val index: Int, val width: Int?, val height: Int?, val version: String)
@Serializable
internal data class WebToolDocument(
    val text: String? = null,
    val roots: List<WebToolNode> = emptyList(),
    val marker: String? = null,
)
@Serializable
internal sealed interface WebToolNode {
    @Serializable @SerialName("object")
    data class Object(val entries: List<WebToolEntry>, val complete: Boolean) : WebToolNode
    @Serializable @SerialName("array")
    data class Array(val values: List<WebToolNode>, val complete: Boolean) : WebToolNode
    @Serializable @SerialName("scalar")
    data class Scalar(val content: String, val kind: String, val complete: Boolean) : WebToolNode
}
@Serializable
internal data class WebToolEntry(val key: String, val keyComplete: Boolean, val value: WebToolNode?)
