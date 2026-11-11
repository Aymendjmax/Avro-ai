package com.newoether.agora.ui.chat.message

import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.view.ViewGroup
import android.webkit.WebView
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.FileProvider
import coil.compose.AsyncImage
import com.newoether.agora.R
import com.newoether.agora.model.MessageSegment
import com.newoether.agora.sandbox.FileStore
import com.newoether.agora.tool.PresentedFileInfo
import com.newoether.agora.ui.motion.MotionAwareModalBottomSheet
import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull

/**
 * Interactive card component presenting generated output deliverables in the chat timeline.
 * Modeled after Claude's clean artifact cards:
 * - A compact, comfortable card with leading document icon, title, subtitle (Category · EXT), and Share icon.
 * - Tapping anywhere on the card opens a full-screen BottomSheet viewer with top bar (Close, Title, 3-dots menu).
 * - 3-dots menu offers Download, Copy (for readable text), Share, and HTML Live Preview/Code toggle.
 */
@Composable
internal fun PresentedFilesBlock(
    segment: MessageSegment,
    modifier: Modifier = Modifier,
    onMediaClick: ((List<String>, Int) -> Unit)? = null,
) {
    val files = remember(segment.toolStructuredResult, segment.toolResult) {
        parsePresentedFiles(segment)
    }

    if (files.isEmpty()) return

    Column(
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
    ) {
        files.forEach { fileInfo ->
            PresentedFileItemCard(
                fileInfo = fileInfo,
                onMediaClick = onMediaClick,
            )
        }
    }
}

@Composable
internal fun PresentedFileItemCard(
    fileInfo: PresentedFileInfo,
    modifier: Modifier = Modifier,
    onMediaClick: ((List<String>, Int) -> Unit)? = null,
) {
    val context = LocalContext.current
    val physicalFile = remember(fileInfo.path) {
        resolvePhysicalFile(context, fileInfo.path)
    }
    var showViewerSheet by rememberSaveable { mutableStateOf(false) }

    val cardShape = RoundedCornerShape(16.dp)

    Surface(
        shape = cardShape,
        color = MaterialTheme.colorScheme.surfaceContainer,
        border = BorderStroke(
            width = 1.dp,
            color = if (fileInfo.error != null) {
                MaterialTheme.colorScheme.error.copy(alpha = 0.5f)
            } else {
                MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f)
            },
        ),
        modifier = modifier
            .fillMaxWidth()
            .clickable(role = Role.Button) {
                showViewerSheet = true
            },
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 12.dp),
        ) {
            // Leading Icon Container (Claude artifact style rounded rectangle)
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(44.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(
                        if (fileInfo.error != null) MaterialTheme.colorScheme.errorContainer
                        else MaterialTheme.colorScheme.surfaceContainerHighest,
                    ),
            ) {
                Icon(
                    imageVector = if (fileInfo.error != null) Icons.Default.Warning
                    else fileIconForMime(fileInfo.mimeType),
                    contentDescription = null,
                    tint = if (fileInfo.error != null) MaterialTheme.colorScheme.onErrorContainer
                    else MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(22.dp),
                )
            }

            Spacer(modifier = Modifier.width(14.dp))

            // Center Column: Title & Subtitle (Category · EXT)
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    text = fileInfo.name,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )

                Text(
                    text = if (fileInfo.error != null) {
                        fileInfo.error
                    } else {
                        val category = categoryLabel(fileInfo.mimeType)
                        val ext = fileInfo.name.substringAfterLast('.', "").uppercase().ifBlank { "FILE" }
                        "$category · $ext"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = if (fileInfo.error != null) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            Spacer(modifier = Modifier.width(8.dp))

            // Right: Minimalist Share Icon
            IconButton(
                onClick = {
                    if (physicalFile != null && physicalFile.exists()) {
                        shareFile(context, physicalFile, fileInfo.mimeType)
                    } else {
                        Toast.makeText(context, context.getString(R.string.present_files_not_found), Toast.LENGTH_SHORT).show()
                    }
                },
                modifier = Modifier.size(36.dp),
            ) {
                Icon(
                    imageVector = Icons.Default.Share,
                    contentDescription = stringResource(R.string.present_files_share),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp),
                )
            }
        }
    }

    // Full-page BottomSheet Viewer
    if (showViewerSheet) {
        PresentedFileViewerSheet(
            fileInfo = fileInfo,
            physicalFile = physicalFile,
            onDismiss = { showViewerSheet = false },
        )
    }
}

/**
 * Full-height BottomSheet viewer (Claude Artifact Sheet style)
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PresentedFileViewerSheet(
    fileInfo: PresentedFileInfo,
    physicalFile: File?,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    val mime = fileInfo.mimeType.lowercase()
    val isHtml = mime.contains("html") || fileInfo.name.endsWith(".html", ignoreCase = true)
    val isImage = fileInfo.isImage || mime.startsWith("image/")
    val isText = isReadableText(fileInfo)
    val isBinary = !isText && !isImage

    var isHtmlPreview by rememberSaveable { mutableStateOf(isHtml) }
    var menuExpanded by remember { mutableStateOf(false) }

    val fileContent = remember(physicalFile) {
        if (physicalFile != null && physicalFile.exists() && isText) {
            runCatching {
                if (physicalFile.length() <= 2 * 1024 * 1024L) {
                    physicalFile.readText(Charsets.UTF_8)
                } else {
                    physicalFile.bufferedReader().useLines { lines ->
                        lines.take(2000).joinToString("\n") + "\n\n[Truncated large file...]"
                    }
                }
            }.getOrNull().orEmpty()
        } else {
            ""
        }
    }

    MotionAwareModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface,
        modifier = Modifier.fillMaxHeight(),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize(),
        ) {
            // Top Bar: Close (X) | Title with Ellipsis | 3-Dots Menu
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 4.dp),
            ) {
                IconButton(onClick = onDismiss) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "Close",
                        tint = MaterialTheme.colorScheme.onSurface,
                    )
                }

                Text(
                    text = fileInfo.name,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .weight(1f)
                        .padding(horizontal = 8.dp),
                )

                Box {
                    IconButton(onClick = { menuExpanded = true }) {
                        Icon(
                            imageVector = Icons.Default.MoreVert,
                            contentDescription = "Options",
                            tint = MaterialTheme.colorScheme.onSurface,
                        )
                    }

                    DropdownMenu(
                        expanded = menuExpanded,
                        onDismissRequest = { menuExpanded = false },
                    ) {
                        // HTML Preview / Code toggle
                        if (isHtml) {
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        stringResource(
                                            if (isHtmlPreview) R.string.present_files_view_code
                                            else R.string.present_files_preview,
                                        ),
                                    )
                                },
                                leadingIcon = {
                                    Icon(
                                        imageVector = if (isHtmlPreview) Icons.Default.Code else Icons.Default.Visibility,
                                        contentDescription = null,
                                    )
                                },
                                onClick = {
                                    isHtmlPreview = !isHtmlPreview
                                    menuExpanded = false
                                },
                            )
                        }

                        // Copy (ONLY for copyable text/code files)
                        if (isText && fileContent.isNotBlank()) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.present_files_copy)) },
                                leadingIcon = {
                                    Icon(
                                        imageVector = Icons.Default.ContentCopy,
                                        contentDescription = null,
                                    )
                                },
                                onClick = {
                                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                                    clipboard?.setPrimaryClip(ClipData.newPlainText(fileInfo.name, fileContent))
                                    Toast.makeText(context, context.getString(R.string.present_files_copied), Toast.LENGTH_SHORT).show()
                                    menuExpanded = false
                                },
                            )
                        }

                        // Download (Save to Downloads/Avro)
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.present_files_download)) },
                            leadingIcon = {
                                Icon(
                                    imageVector = Icons.Default.Download,
                                    contentDescription = null,
                                )
                            },
                            onClick = {
                                if (physicalFile != null && physicalFile.exists()) {
                                    val ok = saveFileToDownloads(context, physicalFile, fileInfo.mimeType)
                                    val msg = if (ok) {
                                        context.getString(R.string.present_files_saved_to_downloads)
                                    } else {
                                        context.getString(R.string.present_files_save_failed)
                                    }
                                    Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                                } else {
                                    Toast.makeText(context, context.getString(R.string.present_files_not_found), Toast.LENGTH_SHORT).show()
                                }
                                menuExpanded = false
                            },
                        )

                        // Share
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.present_files_share)) },
                            leadingIcon = {
                                Icon(
                                    imageVector = Icons.Default.Share,
                                    contentDescription = null,
                                )
                            },
                            onClick = {
                                if (physicalFile != null && physicalFile.exists()) {
                                    shareFile(context, physicalFile, fileInfo.mimeType)
                                } else {
                                    Toast.makeText(context, context.getString(R.string.present_files_not_found), Toast.LENGTH_SHORT).show()
                                }
                                menuExpanded = false
                            },
                        )
                    }
                }
            }

            HorizontalDivider(
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f),
            )

            // Sheet Body
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .weight(1f),
            ) {
                when {
                    fileInfo.error != null -> {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center,
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(24.dp),
                        ) {
                            Icon(
                                imageVector = Icons.Default.Warning,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.error,
                                modifier = Modifier.size(48.dp),
                            )
                            Spacer(modifier = Modifier.height(16.dp))
                            Text(
                                text = fileInfo.error,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                    }

                    isHtml && isHtmlPreview -> {
                        AndroidView(
                            factory = { ctx ->
                                WebView(ctx).apply {
                                    layoutParams = ViewGroup.LayoutParams(
                                        ViewGroup.LayoutParams.MATCH_PARENT,
                                        ViewGroup.LayoutParams.MATCH_PARENT,
                                    )
                                    settings.javaScriptEnabled = true
                                    settings.domStorageEnabled = true
                                    settings.useWideViewPort = true
                                    settings.loadWithOverviewMode = true
                                    settings.builtInZoomControls = true
                                    settings.displayZoomControls = false
                                }
                            },
                            update = { webView ->
                                webView.loadDataWithBaseURL(null, fileContent, "text/html", "UTF-8", null)
                            },
                            modifier = Modifier.fillMaxSize(),
                        )
                    }

                    isImage && physicalFile != null && physicalFile.exists() -> {
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(16.dp),
                        ) {
                            AsyncImage(
                                model = physicalFile,
                                contentDescription = fileInfo.name,
                                contentScale = ContentScale.Fit,
                                modifier = Modifier.fillMaxSize(),
                            )
                        }
                    }

                    isText -> {
                        val scrollState = rememberScrollState()
                        val isCode = mime.startsWith("text/x-") || mime.contains("javascript") ||
                            mime.contains("typescript") || mime.contains("json") ||
                            mime.contains("python") || mime.contains("kotlin") || isHtml

                        SelectionContainer {
                            Text(
                                text = fileContent,
                                style = if (isCode) {
                                    MaterialTheme.typography.bodySmall.copy(
                                        fontFamily = FontFamily.Monospace,
                                        fontSize = 12.5.sp,
                                        lineHeight = 18.sp,
                                    )
                                } else {
                                    MaterialTheme.typography.bodyMedium.copy(
                                        lineHeight = 22.sp,
                                    )
                                },
                                color = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier
                                    .fillMaxSize()
                                    .verticalScroll(scrollState)
                                    .padding(16.dp),
                            )
                        }
                    }

                    isBinary -> {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center,
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(24.dp),
                        ) {
                            Box(
                                contentAlignment = Alignment.Center,
                                modifier = Modifier
                                    .size(64.dp)
                                    .clip(RoundedCornerShape(18.dp))
                                    .background(MaterialTheme.colorScheme.surfaceContainerHighest),
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Description,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(32.dp),
                                )
                            }
                            Spacer(modifier = Modifier.height(16.dp))
                            Text(
                                text = fileInfo.name,
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = fileInfo.sizeFormatted,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Spacer(modifier = Modifier.height(12.dp))
                            Text(
                                text = stringResource(R.string.present_files_binary_notice),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.outline,
                            )
                            Spacer(modifier = Modifier.height(24.dp))
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                            ) {
                                Button(
                                    onClick = {
                                        if (physicalFile != null) {
                                            saveFileToDownloads(context, physicalFile, fileInfo.mimeType)
                                            Toast.makeText(context, context.getString(R.string.present_files_saved_to_downloads), Toast.LENGTH_SHORT).show()
                                        }
                                    },
                                ) {
                                    Icon(imageVector = Icons.Default.Download, contentDescription = null)
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(stringResource(R.string.present_files_download))
                                }
                                OutlinedButton(
                                    onClick = {
                                        if (physicalFile != null) shareFile(context, physicalFile, fileInfo.mimeType)
                                    },
                                ) {
                                    Icon(imageVector = Icons.Default.Share, contentDescription = null)
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(stringResource(R.string.present_files_share))
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun isReadableText(fileInfo: PresentedFileInfo): Boolean {
    val m = fileInfo.mimeType.lowercase()
    val ext = fileInfo.name.substringAfterLast('.', "").lowercase()
    if (m.startsWith("text/") || m.contains("json") || m.contains("javascript") ||
        m.contains("typescript") || m.contains("xml") || m.contains("yaml") ||
        m.contains("sh") || m.contains("markdown") || m.contains("html")
    ) {
        return true
    }
    return ext in setOf(
        "txt", "md", "markdown", "py", "sh", "bash", "kt", "kts", "java", "c", "h", "cpp",
        "hpp", "rs", "go", "js", "mjs", "cjs", "ts", "tsx", "html", "htm", "css", "scss",
        "json", "xml", "yaml", "yml", "csv", "sql", "gradle", "properties", "ini", "env",
    )
}

private fun categoryLabel(mimeType: String): String {
    val m = mimeType.lowercase()
    return when {
        m.startsWith("image/") -> "Image"
        m.contains("html") -> "Web Page"
        m.startsWith("text/x-") || m.contains("javascript") || m.contains("typescript") ||
            m.contains("python") || m.contains("kotlin") || m.contains("java") -> "Code"
        m.contains("json") || m.contains("csv") || m.contains("xml") || m.contains("yaml") -> "Data"
        m.contains("pdf") || m.contains("markdown") || m.startsWith("text/") -> "Document"
        m.contains("zip") || m.contains("tar") || m.contains("archive") -> "Archive"
        else -> "Document"
    }
}

internal fun parsePresentedFiles(segment: MessageSegment): List<PresentedFileInfo> {
    val rawJson = segment.toolStructuredResult ?: segment.toolResult ?: return emptyList()
    val root = runCatching {
        Json.parseToJsonElement(rawJson) as? JsonObject
    }.getOrNull() ?: return emptyList()

    val filesArray = root["files"] as? JsonArray ?: return emptyList()
    return filesArray.mapNotNull { element ->
        val obj = element as? JsonObject ?: return@mapNotNull null
        val name = (obj["name"] as? JsonPrimitive)?.contentOrNull ?: "file"
        val path = (obj["path"] as? JsonPrimitive)?.contentOrNull ?: ""
        val size = (obj["size"] as? JsonPrimitive)?.longOrNull ?: 0L
        val sizeFormatted = (obj["size_formatted"] as? JsonPrimitive)?.contentOrNull ?: "0 B"
        val mimeType = (obj["mime_type"] as? JsonPrimitive)?.contentOrNull ?: "application/octet-stream"
        val isImage = (obj["is_image"] as? JsonPrimitive)?.booleanOrNull ?: mimeType.startsWith("image/")
        val uri = (obj["uri"] as? JsonPrimitive)?.contentOrNull
        val error = (obj["error"] as? JsonPrimitive)?.contentOrNull

        PresentedFileInfo(
            name = name,
            path = path,
            size = size,
            sizeFormatted = sizeFormatted,
            mimeType = mimeType,
            isImage = isImage,
            uri = uri,
            error = error,
        )
    }
}

internal fun resolvePhysicalFile(context: Context, virtualPath: String): File? {
    val homeDir = FileStore.getSandboxHomeDir(context)
    val cleanName = File(virtualPath).name.trim().trim('/')

    // 1. Direct path
    val relDirect = virtualPath.removePrefix("/home/").trimStart('/')
    val fDirect = File(homeDir, relDirect).canonicalFile
    if (fDirect.exists() && fDirect.isFile && fDirect.canonicalPath.startsWith(homeDir.canonicalPath)) {
        return fDirect
    }

    // 2. Candidate outputs
    val fOutputs = File(File(homeDir, "outputs"), cleanName).canonicalFile
    if (fOutputs.exists() && fOutputs.isFile && fOutputs.canonicalPath.startsWith(homeDir.canonicalPath)) {
        return fOutputs
    }

    // 3. Candidate projects
    val fProjects = File(File(homeDir, "projects"), cleanName).canonicalFile
    if (fProjects.exists() && fProjects.isFile && fProjects.canonicalPath.startsWith(homeDir.canonicalPath)) {
        return fProjects
    }

    // 4. Candidate home root
    val fHomeRoot = File(homeDir, cleanName).canonicalFile
    if (fHomeRoot.exists() && fHomeRoot.isFile && fHomeRoot.canonicalPath.startsWith(homeDir.canonicalPath)) {
        return fHomeRoot
    }

    return null
}

internal fun fileIconForMime(mimeType: String): ImageVector {
    val m = mimeType.lowercase()
    return when {
        m.startsWith("image/") -> Icons.Default.Image
        m.startsWith("text/x-") || m.contains("javascript") ||
            m.contains("typescript") || m.contains("html") ||
            m.contains("json") -> Icons.Default.Code
        else -> Icons.Default.Description
    }
}

internal fun shareFile(context: Context, file: File, mimeType: String) {
    val uri = try {
        FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    } catch (_: Exception) {
        null
    } ?: return

    val intent = Intent(Intent.ACTION_SEND).apply {
        type = mimeType
        putExtra(Intent.EXTRA_STREAM, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }

    runCatching {
        context.startActivity(Intent.createChooser(intent, file.name).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        })
    }
}

internal fun saveFileToDownloads(context: Context, sourceFile: File, mimeType: String): Boolean {
    return runCatching {
        val resolver = context.contentResolver
        val fileName = sourceFile.name

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val contentValues = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, fileName)
                put(MediaStore.Downloads.MIME_TYPE, mimeType)
                put(MediaStore.Downloads.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/Avro")
            }
            val destinationUri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, contentValues)
                ?: return false

            resolver.openOutputStream(destinationUri)?.use { out ->
                sourceFile.inputStream().use { inStream ->
                    inStream.copyTo(out)
                }
            } ?: return false
            true
        } else {
            val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            val avroDir = File(downloadsDir, "Avro").apply { if (!exists()) mkdirs() }
            val target = File(avroDir, fileName)
            sourceFile.copyTo(target, overwrite = true)
            true
        }
    }.getOrDefault(false)
}
