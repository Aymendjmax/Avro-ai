package com.newoether.agora.mcp

import android.content.Context
import android.util.Base64
import com.newoether.agora.sandbox.FileStore
import com.newoether.agora.tool.PresentFilesToolProvider
import com.newoether.agora.tool.ToolExecutionResult
import java.io.File
import java.io.InputStream
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull

/**
 * File bridge between MCP (Model Context Protocol) tool servers and the local PRoot Linux sandbox.
 *
 * Capabilities:
 * 1. File reference resolution: Intercepts `file:<path>` references in MCP tool input arguments,
 *    locating the file inside the PRoot sandbox home jail (/home/outputs, /home/uploads, /home/projects)
 *    and embedding file content or normalized sandbox paths.
 * 2. Output file bridging: Captures binary, image, and file payloads returned by MCP tools,
 *    writing them directly into the sandbox deliverables directory (/home/outputs/<chat>/)
 *    so the autonomous agent can inspect them with shell tools or present them to the user via present_files.
 */
object McpFileBridge {

    const val FILE_PREFIX = "file:"
    private const val MAX_INLINE_TEXT_BYTES = 512 * 1024L // 512 KB

    /**
     * Resolves a `file:<path>` reference to a physical [File] on device within the sandbox home jail.
     */
    fun resolveSandboxFile(
        context: Context,
        rawReference: String,
        conversationId: String? = null,
    ): Pair<File, String>? {
        val cleanPath = rawReference
            .removePrefix(FILE_PREFIX)
            .removePrefix("//")
            .trim()

        if (cleanPath.contains("..")) return null

        val homeDir = FileStore.getSandboxHomeDir(context)
        val normalized = PresentFilesToolProvider.normalizePath(cleanPath)

        return PresentFilesToolProvider.resolveCandidateFile(
            homeDir = homeDir,
            rawPath = cleanPath,
            normalizedPath = normalized,
            conversationId = conversationId,
        )
    }

    /**
     * Transforms MCP input arguments by resolving any `file:<path>` values.
     * If an argument value is `"file:<path>"`, reads the file content from the sandbox
     * (as UTF-8 text for text files, or base64 data URI for binary files) and substitutes it.
     */
    fun transformInputArguments(
        context: Context,
        rawArguments: String,
        conversationId: String? = null,
    ): String {
        if (!rawArguments.contains(FILE_PREFIX)) return rawArguments

        return runCatching {
            val root = Json.parseToJsonElement(rawArguments)
            val transformed = transformJsonElement(context, root, conversationId)
            transformed.toString()
        }.getOrDefault(rawArguments)
    }

    private fun transformJsonElement(
        context: Context,
        element: JsonElement,
        conversationId: String?,
    ): JsonElement = when (element) {
        is JsonObject -> buildJsonObject {
            element.forEach { (key, value) ->
                put(key, transformJsonElement(context, value, conversationId))
            }
        }
        is JsonArray -> buildJsonArray {
            element.forEach { value ->
                add(transformJsonElement(context, value, conversationId))
            }
        }
        is JsonPrimitive -> {
            val text = element.contentOrNull
            if (text != null && text.startsWith(FILE_PREFIX)) {
                val resolved = resolveSandboxFile(context, text, conversationId)
                if (resolved != null && resolved.first.exists() && resolved.first.isFile) {
                    val file = resolved.first
                    val mime = PresentFilesToolProvider.resolveMimeType(file)
                    val isText = mime.startsWith("text/") || mime.contains("json") ||
                        mime.contains("javascript") || mime.contains("typescript") ||
                        mime.contains("yaml") || mime.contains("markdown")

                    if (isText && file.length() <= MAX_INLINE_TEXT_BYTES) {
                        val content = runCatching { file.readText(Charsets.UTF_8) }.getOrNull()
                        if (content != null) JsonPrimitive(content) else element
                    } else {
                        // Pass effective virtual sandbox path so server knows where to read/write
                        JsonPrimitive(resolved.second)
                    }
                } else {
                    element
                }
            } else {
                element
            }
        }
    }

    /**
     * Processes tool outputs: saves returned images and file attachments into the PRoot sandbox
     * outputs directory `/home/outputs/<chat>/` and appends `file:<path>` references to the output text.
     */
    fun bridgeOutputFiles(
        context: Context,
        serverName: String,
        toolName: String,
        result: ToolExecutionResult,
        conversationId: String? = null,
    ): ToolExecutionResult {
        if (result.isError) return result

        val homeDir = FileStore.getSandboxHomeDir(context)
        val chatId = conversationId?.takeIf { it.isNotBlank() } ?: "default"
        val outputsDir = File(homeDir, "outputs/$chatId").apply { if (!exists()) mkdirs() }

        val bridgedPaths = mutableListOf<String>()

        // 1. Bridge saved images into sandbox outputs
        result.images.forEachIndexed { index, imageAttachment ->
            try {
                val sourceFile = File(imageAttachment.path)
                if (sourceFile.exists()) {
                    val ext = sourceFile.extension.ifBlank {
                        imageAttachment.mimeType.substringAfterLast('/').ifBlank { "png" }
                    }
                    val destFile = File(outputsDir, "mcp_${toolName}_${System.currentTimeMillis()}_$index.$ext")
                    sourceFile.copyTo(destFile, overwrite = true)
                    bridgedPaths.add("/home/outputs/$chatId/${destFile.name}")
                }
            } catch (_: Exception) {
                // Ignore copy errors
            }
        }

        if (bridgedPaths.isEmpty()) return result

        val notice = buildString {
            append("\n\n")
            if (bridgedPaths.size == 1) {
                append("[MCP Deliverable saved to sandbox: file:${bridgedPaths.first()}]")
            } else {
                append("[MCP Deliverables saved to sandbox: ")
                append(bridgedPaths.joinToString(", ") { "file:$it" })
                append("]")
            }
            append("\n(You can inspect with shell tools or deliver to user with present_files)")
        }

        return result.copy(
            text = result.text + notice,
        )
    }
}
