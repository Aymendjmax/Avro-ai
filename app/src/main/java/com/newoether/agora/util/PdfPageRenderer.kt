package com.newoether.agora.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import java.io.File
import java.net.URI
import java.util.UUID
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ensureActive

object PdfPageRenderer {
    private const val MAX_PAGES = 5
    private const val TARGET_LONG_EDGE = 1536

    /**
     * Renders only the requested [pages] (first [MAX_PAGES] if omitted) to internal storage
     * at full quality. Cancellation-aware: partial files are deleted on cancel or failure.
     */
    suspend fun renderAsImages(
        context: Context,
        uri: Uri,
        pages: Set<Int>? = null,
    ): List<String> = renderAsImages(context, uri.toString(), pages)

    suspend fun renderAsImages(
        context: Context,
        source: String,
        pages: Set<Int>? = null,
        outputOwner: Any? = null,
    ): List<String> {
        val descriptor = openDescriptor(context, source) ?: return emptyList()
        val renderer = runCatching { PdfRenderer(descriptor) }
            .onFailure { runCatching { descriptor.close() } }.getOrThrow()
        val paths = mutableListOf<String>()

        try {
            val totalPages = renderer.pageCount
            val selectedPages = pages?.filter { it in 0 until totalPages }?.toSet()
                ?: (0 until minOf(totalPages, MAX_PAGES)).toSet()
            for (index in selectedPages.sorted()) {
                coroutineContext.ensureActive()
                val page = renderer.openPage(index)
                var bitmap: Bitmap? = null
                try {
                    val scale = TARGET_LONG_EDGE.toFloat() / maxOf(page.width, page.height)
                    val width = (page.width * scale).toInt().coerceAtLeast(1)
                    val height = (page.height * scale).toInt().coerceAtLeast(1)
                    bitmap = createPageBitmap(width, height)
                    page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)

                    val file = File(context.filesDir, "pdf_${UUID.randomUUID()}_$index.jpg")
                    outputOwner?.let { AttachmentFiles.retainLivePath(it, file.absolutePath) }
                    try {
                        val encoded = file.outputStream().use { output ->
                            bitmap.compress(Bitmap.CompressFormat.JPEG, 80, output)
                        }
                        check(encoded) { "PDF page encoding failed" }
                        coroutineContext.ensureActive()
                        paths += file.absolutePath
                    } catch (failure: Exception) {
                        file.delete()
                        throw failure
                    }
                } finally {
                    bitmap?.recycle()
                    page.close()
                }
            }
        } catch (cancelled: CancellationException) {
            paths.forEach { File(it).delete() }
            throw cancelled
        } catch (failure: Exception) {
            paths.forEach { File(it).delete() }
            throw failure
        } finally {
            renderer.close()
        }
        return paths
    }

    /**
     * Renders every page (up to [maxPages]) to internal storage. Cancellation-aware: if the
     * calling coroutine is cancelled or fails mid-render, partial page files are deleted.
     */
    suspend fun renderAllPages(
        context: Context,
        uri: Uri,
        maxPages: Int = 200,
        onProgress: (suspend (current: Int, total: Int) -> Unit)? = null,
    ): List<String> = renderAllPages(context, uri.toString(), maxPages, onProgress)

    suspend fun renderAllPages(
        context: Context,
        source: String,
        maxPages: Int = 200,
        onProgress: (suspend (current: Int, total: Int) -> Unit)? = null,
        outputOwner: Any? = null,
    ): List<String> {
        val descriptor = openDescriptor(context, source) ?: return emptyList()
        val renderer = runCatching { PdfRenderer(descriptor) }
            .onFailure { runCatching { descriptor.close() } }.getOrThrow()
        val paths = mutableListOf<String>()
        try {
            val effectiveTotal = minOf(renderer.pageCount, maxPages)

            for (index in 0 until effectiveTotal) {
                coroutineContext.ensureActive()
                val page = renderer.openPage(index)
                var bitmap: Bitmap? = null
                try {
                    val scale = TARGET_LONG_EDGE.toFloat() / maxOf(page.width, page.height)
                    val width = (page.width * scale).toInt().coerceAtLeast(1)
                    val height = (page.height * scale).toInt().coerceAtLeast(1)
                    bitmap = createPageBitmap(width, height)
                    page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)

                    val file = File(context.filesDir, "pdf_preview_${UUID.randomUUID()}_$index.jpg")
                    outputOwner?.let { AttachmentFiles.retainLivePath(it, file.absolutePath) }
                    try {
                        val encoded = file.outputStream().use { output ->
                            bitmap.compress(Bitmap.CompressFormat.JPEG, 80, output)
                        }
                        check(encoded) { "PDF preview encoding failed" }
                        coroutineContext.ensureActive()
                        paths += file.absolutePath
                    } catch (failure: Exception) {
                        file.delete()
                        throw failure
                    }
                } finally {
                    bitmap?.recycle()
                    page.close()
                }
                onProgress?.invoke(index + 1, effectiveTotal)
            }
        } catch (cancelled: CancellationException) {
            paths.forEach { File(it).delete() }
            throw cancelled
        } catch (failure: Exception) {
            paths.forEach { File(it).delete() }
            throw failure
        } finally {
            renderer.close()
        }
        return paths
    }

    private fun openDescriptor(context: Context, source: String): ParcelFileDescriptor? = try {
        when {
            File(source).isAbsolute -> ParcelFileDescriptor.open(
                File(source),
                ParcelFileDescriptor.MODE_READ_ONLY,
            )
            source.startsWith("file:", ignoreCase = true) -> ParcelFileDescriptor.open(
                File(URI(source)),
                ParcelFileDescriptor.MODE_READ_ONLY,
            )
            else -> context.contentResolver.openFileDescriptor(Uri.parse(source), "r")
        }
    } catch (_: Exception) {
        null
    }

    private fun createPageBitmap(width: Int, height: Int): Bitmap =
        Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).apply {
            eraseColor(Color.WHITE)
        }

    fun getPageCount(context: Context, uri: Uri): Int =
        getPageCount(context, uri.toString())

    fun getPageCount(context: Context, source: String): Int {
        return try {
            val descriptor = openDescriptor(context, source) ?: return 0
            val renderer = runCatching { PdfRenderer(descriptor) }
                .onFailure { runCatching { descriptor.close() } }.getOrThrow()
            val count = renderer.pageCount
            renderer.close()
            count
        } catch (_: Exception) {
            0
        }
    }
}
