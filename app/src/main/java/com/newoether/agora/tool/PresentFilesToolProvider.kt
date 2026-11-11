package com.newoether.agora.tool

import android.content.Context
import android.webkit.MimeTypeMap
import androidx.core.content.FileProvider
import com.newoether.agora.api.ToolDefinition
import com.newoether.agora.api.ToolFunction
import com.newoether.agora.api.ToolParameters
import com.newoether.agora.api.ToolProperty
import com.newoether.agora.model.ConversationMode
import com.newoether.agora.sandbox.FileStore
import com.newoether.agora.sandbox.SandboxManagerFactory
import com.newoether.agora.viewmodel.GenerationContext
import java.io.File
import java.text.DecimalFormat
import java.util.Locale
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

@Serializable
data class PresentedFileInfo(
    val name: String,
    val path: String,
    val size: Long,
    val sizeFormatted: String,
    val mimeType: String,
    val isImage: Boolean,
    val uri: String? = null,
    val error: String? = null,
)

/**
 * Provider for the `present_files` tool.
 * Enables the model to present generated deliverables, artifacts, and project files
 * from the PRoot sandbox to the user as interactive download/preview cards in chat.
 */
class PresentFilesToolProvider(
    private val context: Context,
    private val sandboxFactory: SandboxManagerFactory? = null,
) : ToolProvider {

    override fun definitions(ctx: GenerationContext): List<ToolDefinition> {
        val available = ctx.conversationMode == ConversationMode.WORK ||
            ctx.sandboxEnabled || ctx.shellEnabled
        if (!available) return emptyList()

        return listOf(
            ToolDefinition(
                function = ToolFunction(
                    name = TOOL_NAME,
                    description = "Present generated deliverables, output files, or project files to the user as interactive downloadable cards in chat. " +
                        "Save files into /home/outputs/ (e.g. '/home/outputs/report.html') or /home/projects/ and call this tool with their paths. " +
                        "Simple filenames or relative paths like 'outputs/report.html' or 'report.html' are automatically resolved.",
                    parameters = ToolParameters(
                        properties = mapOf(
                            "paths" to ToolProperty(
                                type = "array",
                                description = "List of sandbox file paths to present (e.g. ['/home/outputs/report.html', 'outputs/data.csv', '/home/projects/app/main.py']).",
                                items = ToolProperty("string", "A sandbox file path."),
                            ),
                            "path" to ToolProperty(
                                type = "string",
                                description = "Single sandbox file path to present (alternative to paths).",
                            ),
                        ),
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
        val args = runCatching {
            Json.parseToJsonElement(arguments.ifBlank { "{}" }) as? JsonObject
        }.getOrNull()

        val requestedPaths = extractRequestedPaths(args)
        if (requestedPaths.isEmpty()) {
            return buildJsonObject {
                put("type", TOOL_NAME)
                put("success", false)
                put("error", "No file paths specified. Provide 'paths' (array) or 'path' (string).")
            }.toString()
        }

        val homeDir = FileStore.getSandboxHomeDir(context)
        val presentedFiles = mutableListOf<PresentedFileInfo>()
        val errors = mutableListOf<String>()

        for (rawPath in requestedPaths) {
            // Security: Path traversal rejection
            if (rawPath.contains("..")) {
                val err = "Path traversal attempted: $rawPath"
                errors.add(err)
                presentedFiles.add(
                    PresentedFileInfo(
                        name = File(rawPath).name,
                        path = rawPath,
                        size = 0L,
                        sizeFormatted = "0 B",
                        mimeType = "application/octet-stream",
                        isImage = false,
                        error = err,
                    ),
                )
                continue
            }

            val normalized = normalizePath(rawPath)
            val candidate = resolveCandidateFile(
                homeDir = homeDir,
                rawPath = rawPath,
                normalizedPath = normalized,
                conversationId = ctx.conversationId,
            )

            if (candidate == null) {
                val err = "File not found: $rawPath. (Saved in /home/outputs/ or /home/projects/?)"
                errors.add(err)
                presentedFiles.add(
                    PresentedFileInfo(
                        name = File(rawPath).name,
                        path = normalized,
                        size = 0L,
                        sizeFormatted = "0 B",
                        mimeType = resolveMimeType(File(rawPath)),
                        isImage = false,
                        error = err,
                    ),
                )
                continue
            }

            val (physicalFile, effectivePath) = candidate

            // Strict sandbox home jail check
            if (!physicalFile.canonicalPath.startsWith(homeDir.canonicalPath)) {
                val err = "Path escapes sandbox jail: $rawPath"
                errors.add(err)
                presentedFiles.add(
                    PresentedFileInfo(
                        name = physicalFile.name,
                        path = effectivePath,
                        size = 0L,
                        sizeFormatted = "0 B",
                        mimeType = "application/octet-stream",
                        isImage = false,
                        error = err,
                    ),
                )
                continue
            }

            val mimeType = resolveMimeType(physicalFile)
            val isImage = mimeType.startsWith("image/")
            val contentUri = runCatching {
                FileProvider.getUriForFile(
                    context,
                    "${context.packageName}.fileprovider",
                    physicalFile,
                ).toString()
            }.getOrNull()

            presentedFiles.add(
                PresentedFileInfo(
                    name = physicalFile.name,
                    path = effectivePath,
                    size = physicalFile.length(),
                    sizeFormatted = formatFileSize(physicalFile.length()),
                    mimeType = mimeType,
                    isImage = isImage,
                    uri = contentUri,
                ),
            )
        }

        val successCount = presentedFiles.count { it.error == null }

        return buildJsonObject {
            put("type", TOOL_NAME)
            put("success", successCount > 0)
            put("count", successCount)
            if (errors.isNotEmpty()) {
                putJsonArray("errors") {
                    errors.forEach { add(JsonPrimitive(it)) }
                }
            }
            putJsonArray("files") {
                presentedFiles.forEach { file ->
                    add(
                        buildJsonObject {
                            put("name", file.name)
                            put("path", file.path)
                            put("size", file.size)
                            put("size_formatted", file.sizeFormatted)
                            put("mime_type", file.mimeType)
                            put("is_image", file.isImage)
                            file.uri?.let { put("uri", it) }
                            file.error?.let { put("error", it) }
                        },
                    )
                }
            }
        }.toString()
    }

    private fun extractRequestedPaths(args: JsonObject?): List<String> {
        if (args == null) return emptyList()
        val paths = mutableListOf<String>()

        (args["paths"] as? JsonArray)?.forEach { element ->
            val str = (element as? JsonPrimitive)?.content?.trim()
            if (!str.isNullOrBlank() && !paths.contains(str)) paths.add(str)
        }

        (args["path"] as? JsonPrimitive)?.content?.trim()?.takeIf { it.isNotBlank() }?.let {
            if (!paths.contains(it)) paths.add(it)
        }

        (args["files"] as? JsonArray)?.forEach { element ->
            when (element) {
                is JsonPrimitive -> element.content.trim().takeIf { it.isNotBlank() }?.let {
                    if (!paths.contains(it)) paths.add(it)
                }
                is JsonObject -> {
                    (element["path"] as? JsonPrimitive)?.content?.trim()?.takeIf { it.isNotBlank() }?.let {
                        if (!paths.contains(it)) paths.add(it)
                    }
                }
                else -> Unit
            }
        }

        return paths
    }

    companion object {
        const val TOOL_NAME = "present_files"

        fun normalizePath(rawPath: String): String {
            var p = rawPath.trim().replace('\\', '/')
            while (p.startsWith("./")) {
                p = p.removePrefix("./").trim()
            }
            p = p.replace(Regex("^/+"), "/")
            p = p.replace(Regex("^/home/home/"), "/home/")
            if (p.startsWith("home/")) {
                p = "/" + p
            } else if (p.startsWith("/outputs/") || p.startsWith("outputs/")) {
                p = "/home/outputs/" + p.removePrefix("/outputs/").removePrefix("outputs/").trimStart('/')
            } else if (p.startsWith("/projects/") || p.startsWith("projects/")) {
                p = "/home/projects/" + p.removePrefix("/projects/").removePrefix("projects/").trimStart('/')
            } else if (p.startsWith("/uploads/") || p.startsWith("uploads/")) {
                p = "/home/uploads/" + p.removePrefix("/uploads/").removePrefix("uploads/").trimStart('/')
            } else if (p.startsWith("/notes/") || p.startsWith("notes/")) {
                p = "/home/notes/" + p.removePrefix("/notes/").removePrefix("notes/").trimStart('/')
            } else if (!p.startsWith("/")) {
                p = "/home/outputs/$p"
            } else if (!p.startsWith("/home/")) {
                p = "/home" + p
            }
            return p.replace("//", "/")
        }

        fun resolveCandidateFile(
            homeDir: File,
            rawPath: String,
            normalizedPath: String,
            conversationId: String?,
        ): Pair<File, String>? {
            val cleanName = File(rawPath).name.trim().trim('/')
            val candidates = mutableListOf<Pair<File, String>>()

            // 1. Direct normalized path
            val relDirect = normalizedPath.removePrefix("/home/").trimStart('/')
            val fDirect = File(homeDir, relDirect).canonicalFile
            candidates.add(fDirect to normalizedPath)

            // 2. In /home/outputs/<conversationId>/<filename>
            if (!conversationId.isNullOrBlank()) {
                val fChatOutputs = File(File(homeDir, "outputs/$conversationId"), cleanName).canonicalFile
                candidates.add(fChatOutputs to "/home/outputs/$conversationId/$cleanName")
            }

            // 3. In /home/outputs/<filename>
            val fOutputs = File(File(homeDir, "outputs"), cleanName).canonicalFile
            candidates.add(fOutputs to "/home/outputs/$cleanName")

            // 4. In /home/projects/<filename>
            val fProjects = File(File(homeDir, "projects"), cleanName).canonicalFile
            candidates.add(fProjects to "/home/projects/$cleanName")

            // 5. In /home/<filename>
            val fHomeRoot = File(homeDir, cleanName).canonicalFile
            candidates.add(fHomeRoot to "/home/$cleanName")

            // 6. In /home/uploads/<conversationId>/<filename>
            if (!conversationId.isNullOrBlank()) {
                val fChatUploads = File(File(homeDir, "uploads/$conversationId"), cleanName).canonicalFile
                candidates.add(fChatUploads to "/home/uploads/$conversationId/$cleanName")
            }

            return candidates.firstOrNull { candidate ->
                try {
                    candidate.first.exists() &&
                        candidate.first.isFile &&
                        candidate.first.canonicalPath.startsWith(homeDir.canonicalPath)
                } catch (_: Exception) {
                    false
                }
            }
        }

        fun isAllowedPath(normalizedPath: String): Boolean {
            if (normalizedPath.contains("..")) return false
            return normalizedPath.startsWith("/home/") || normalizedPath == "/home"
        }

        fun resolveMimeType(file: File): String {
            val extension = file.extension.lowercase(Locale.ROOT)
            if (extension.isBlank()) return "application/octet-stream"

            val fromMap = MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension)
            if (!fromMap.isNullOrBlank()) return fromMap

            return when (extension) {
                "md", "markdown" -> "text/markdown"
                "json" -> "application/json"
                "yaml", "yml" -> "text/yaml"
                "sh", "bash" -> "application/x-sh"
                "py" -> "text/x-python"
                "kt", "kts" -> "text/x-kotlin"
                "java" -> "text/x-java-source"
                "c", "h", "cpp", "hpp" -> "text/x-c"
                "rs" -> "text/rust"
                "go" -> "text/x-go"
                "js", "mjs", "cjs" -> "application/javascript"
                "ts", "tsx" -> "application/typescript"
                "html", "htm" -> "text/html"
                "css" -> "text/css"
                "csv" -> "text/csv"
                "svg" -> "image/svg+xml"
                "pdf" -> "application/pdf"
                "zip" -> "application/zip"
                "tar" -> "application/x-tar"
                "gz" -> "application/gzip"
                "apk" -> "application/vnd.android.package-archive"
                else -> "application/octet-stream"
            }
        }

        fun formatFileSize(bytes: Long): String {
            if (bytes <= 0) return "0 B"
            val units = arrayOf("B", "KB", "MB", "GB", "TB")
            val digitGroups = (Math.log10(bytes.toDouble()) / Math.log10(1024.0)).toInt()
            val format = DecimalFormat("#,##0.#")
            val clampedGroup = digitGroups.coerceIn(0, units.size - 1)
            return "${format.format(bytes / Math.pow(1024.0, clampedGroup.toDouble()))} ${units[clampedGroup]}"
        }
    }
}
