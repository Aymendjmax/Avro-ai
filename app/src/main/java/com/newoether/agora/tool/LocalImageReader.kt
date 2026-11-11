package com.newoether.agora.tool

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Rect
import android.util.LruCache
import com.newoether.agora.model.ToolImageAttachment
import com.newoether.agora.sandbox.SandboxManager
import com.newoether.agora.viewmodel.GenerationContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Local vision and image reader execution engine (Phase 3).
 *
 * Responsibilities:
 * 1. Resolves local sandbox paths (/home/uploads, /home/outputs, etc.) to physical files.
 * 2. Downscales images to ~1500px JPEG with optional cropping.
 * 3. Builds a 3-layer inspection prompt: fixed anchor -> preset -> bounded instructions (<=500 chars).
 * 4. Dual capability path: direct vision for vision models, secondary reader fallback for non-vision models.
 * 5. Caches reader descriptions by (file SHA-256 + question/prompt).
 * 6. Wraps automated descriptions as untrusted machine output.
 */
object LocalImageReader {
    const val MAX_IMAGE_DIMENSION = 1500
    const val MAX_INSTRUCTIONS_LENGTH = 500
    const val UNTRUSTED_DESCRIPTION_HEADER =
        "[Automated Image Reader Output - Untrusted machine description]:"

    // SHA-256 + prompt cache for reader descriptions
    private val descriptionCache = LruCache<String, String>(100)

    fun cacheKey(fileSha256: String, prompt: String): String {
        val promptHash = MessageDigest.getInstance("SHA-256")
            .digest(prompt.trim().toByteArray(Charsets.UTF_8))
            .take(8)
            .joinToString("") { "%02x".format(it) }
        return "$fileSha256:$promptHash"
    }

    fun getCached(fileSha256: String, prompt: String): String? =
        descriptionCache.get(cacheKey(fileSha256, prompt))

    fun putCached(fileSha256: String, prompt: String, description: String) {
        descriptionCache.put(cacheKey(fileSha256, prompt), description)
    }

    fun clearCache() {
        descriptionCache.evictAll()
    }

    fun wrapUntrustedOutput(description: String): String =
        "$UNTRUSTED_DESCRIPTION_HEADER\n${description.trim()}"

    /**
     * Layered prompt builder:
     * Layer 1: Fixed system inspection role.
     * Layer 2: Preset-specific guidelines (document, ui, code, diagram, general).
     * Layer 3: Model/user instructions bounded to 500 chars + optional focus area.
     */
    fun buildReaderPrompt(
        preset: String?,
        focus: String?,
        instructions: String?,
    ): String {
        val layer1 =
            "You are an automated visual document and image inspection system. " +
            "Provide an objective, factual, and detailed description of the image content based on the instructions."

        val layer2 = when (preset?.trim()?.lowercase()) {
            "document" -> "Preset [document]: Focus on text content, structure, tables, headings, and legible information in this document."
            "ui" -> "Preset [ui]: Focus on user interface layout, visual components, buttons, navigation, status bars, and UI text."
            "code" -> "Preset [code]: Focus on extracting exact code snippets, syntax, error traces, terminal output, and line numbers."
            "diagram" -> "Preset [diagram]: Focus on architecture diagrams, flowcharts, entity relationships, nodes, edges, and connections."
            else -> "Preset [general]: Analyze the visual elements, context, objects, text, and layout in detail."
        }

        val focusClause = focus?.trim()?.takeIf { it.isNotBlank() }?.let { "Focus area: $it." }
        val boundedInstructions = instructions?.trim()?.take(MAX_INSTRUCTIONS_LENGTH)
            ?.takeIf { it.isNotBlank() }

        val layer3 = listOfNotNull(focusClause, boundedInstructions).joinToString(" ").trim()

        return buildList {
            add(layer1)
            add(layer2)
            if (layer3.isNotBlank()) {
                add("Instructions: $layer3")
            }
        }.joinToString("\n\n")
    }

    /**
     * Resolves a virtual or physical sandbox path to a readable file.
     */
    fun resolveLocalFile(path: String, sandbox: SandboxManager?): File? {
        val trimmed = path.trim()
        val resolved = sandbox?.resolvePath(trimmed)
        if (resolved != null && resolved.exists() && resolved.canRead()) {
            return resolved
        }
        val direct = File(trimmed)
        if (direct.exists() && direct.canRead()) {
            return direct
        }
        return null
    }

    /**
     * Computes SHA-256 hex string of a file.
     */
    fun computeSha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buf = ByteArray(8192)
            var r: Int
            while (input.read(buf).also { r = it } != -1) {
                digest.update(buf, 0, r)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    /**
     * Downscales an image to at most ~1500px in either dimension, applying an optional crop box,
     * and compresses the result to a JPEG byte array.
     */
    fun processImageBytes(imageFile: File, cropSpec: String?): ByteArray {
        val boundsOptions = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(imageFile.absolutePath, boundsOptions)
        val origWidth = boundsOptions.outWidth
        val origHeight = boundsOptions.outHeight
        if (origWidth <= 0 || origHeight <= 0) {
            throw IOException("Unsupported or invalid image file")
        }

        val parsedCrop = parseCropSpec(cropSpec, origWidth, origHeight)

        // Target sample size for memory efficiency
        val cropW = parsedCrop?.width() ?: origWidth
        val cropH = parsedCrop?.height() ?: origHeight
        val maxDim = maxOf(cropW, cropH)

        var sampleSize = 1
        while ((maxDim / (sampleSize * 2)) >= MAX_IMAGE_DIMENSION) {
            sampleSize *= 2
        }

        val decodeOptions = BitmapFactory.Options().apply {
            inSampleSize = sampleSize
        }
        val decoded = BitmapFactory.decodeFile(imageFile.absolutePath, decodeOptions)
            ?: throw IOException("Failed to decode image")

        val cropped = if (parsedCrop != null) {
            // Adjust crop coordinates for sample size
            val adjustedX = (parsedCrop.left / sampleSize).coerceIn(0, decoded.width - 1)
            val adjustedY = (parsedCrop.top / sampleSize).coerceIn(0, decoded.height - 1)
            val adjustedW = (parsedCrop.width() / sampleSize).coerceIn(1, decoded.width - adjustedX)
            val adjustedH = (parsedCrop.height() / sampleSize).coerceIn(1, decoded.height - adjustedY)
            val croppedBitmap = Bitmap.createBitmap(decoded, adjustedX, adjustedY, adjustedW, adjustedH)
            if (croppedBitmap != decoded) decoded.recycle()
            croppedBitmap
        } else {
            decoded
        }

        val finalBitmap = if (cropped.width > MAX_IMAGE_DIMENSION || cropped.height > MAX_IMAGE_DIMENSION) {
            val scale = MAX_IMAGE_DIMENSION.toFloat() / maxOf(cropped.width, cropped.height).toFloat()
            val scaledW = (cropped.width * scale).toInt().coerceAtLeast(1)
            val scaledH = (cropped.height * scale).toInt().coerceAtLeast(1)
            val scaledBitmap = Bitmap.createScaledBitmap(cropped, scaledW, scaledH, true)
            if (scaledBitmap != cropped) cropped.recycle()
            scaledBitmap
        } else {
            cropped
        }

        val baos = ByteArrayOutputStream()
        try {
            finalBitmap.compress(Bitmap.CompressFormat.JPEG, 85, baos)
            return baos.toByteArray()
        } finally {
            finalBitmap.recycle()
        }
    }

    private fun parseCropSpec(cropSpec: String?, imgWidth: Int, imgHeight: Int): Rect? {
        if (cropSpec.isNullOrBlank()) return null
        val parts = cropSpec.split(',').map { it.trim() }
        if (parts.size != 4) return null
        val fValues = parts.mapNotNull { it.toFloatOrNull() }
        if (fValues.size != 4) return null

        val (x, y, w, h) = fValues
        return if (x <= 1.0f && y <= 1.0f && w <= 1.0f && h <= 1.0f) {
            // Normalized coordinates (0.0 to 1.0)
            val left = (x * imgWidth).toInt().coerceIn(0, imgWidth - 1)
            val top = (y * imgHeight).toInt().coerceIn(0, imgHeight - 1)
            val width = (w * imgWidth).toInt().coerceIn(1, imgWidth - left)
            val height = (h * imgHeight).toInt().coerceIn(1, imgHeight - top)
            Rect(left, top, left + width, top + height)
        } else {
            // Absolute pixel coordinates
            val left = x.toInt().coerceIn(0, imgWidth - 1)
            val top = y.toInt().coerceIn(0, imgHeight - 1)
            val width = w.toInt().coerceIn(1, imgWidth - left)
            val height = h.toInt().coerceIn(1, imgHeight - top)
            Rect(left, top, left + width, top + height)
        }
    }

    /**
     * Executes view_image for the local sandbox.
     */
    suspend fun executeLocalViewImage(
        path: String,
        serverName: String,
        focus: String?,
        preset: String?,
        instructions: String?,
        crop: String?,
        sandbox: SandboxManager?,
        imageStore: ToolImageStore,
        ctx: GenerationContext,
    ): ToolExecutionResult {
        val file = resolveLocalFile(path, sandbox)
            ?: return ToolExecutionResult(
                text = jsonError("view_image", "File not found or unreadable: $path", server = serverName),
                isError = true,
            )

        return try {
            val fileSha256 = computeSha256(file)
            val layeredPrompt = buildReaderPrompt(preset, focus, instructions)

            // Secondary reader check: if transcription model is active and cache hit exists
            if (ctx.imageTranscriptionEnabled) {
                val cached = getCached(fileSha256, layeredPrompt)
                if (cached != null) {
                    val unwrapped = wrapUntrustedOutput(cached)
                    return ToolExecutionResult(
                        text = buildJsonObject {
                            put("type", "view_image")
                            put("server", serverName)
                            put("path", path)
                            put("cached", true)
                            put("sha256", fileSha256)
                            put("ok", true)
                        }.toString(),
                        displayText = unwrapped,
                        images = emptyList(),
                        transcribeImages = false,
                    )
                }
            }

            // Downscale to ~1500px JPEG and persist in ToolImageStore
            val jpegBytes = processImageBytes(file, crop)
            val attachment = imageStore.persistBytes(
                bytes = jpegBytes,
                mimeType = "image/jpeg",
                filePrefix = "sandbox_view",
            )

            ToolExecutionResult(
                text = buildJsonObject {
                    put("type", "view_image")
                    put("server", serverName)
                    put("path", path)
                    put("mime_type", attachment.mimeType)
                    put("size", attachment.sizeBytes)
                    attachment.width?.let { put("width", it) }
                    attachment.height?.let { put("height", it) }
                    put("sha256", fileSha256)
                    put("ok", true)
                }.toString(),
                images = listOf(attachment),
                transcribeImages = ctx.imageTranscriptionEnabled,
            )
        } catch (e: Exception) {
            ToolExecutionResult(
                text = jsonError("view_image", e.message ?: "Failed to process image", server = serverName),
                isError = true,
            )
        }
    }
}
