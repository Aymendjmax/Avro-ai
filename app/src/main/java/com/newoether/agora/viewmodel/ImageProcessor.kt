package com.newoether.agora.viewmodel

import android.app.Application
import android.media.MediaMetadataRetriever
import android.net.Uri
import com.newoether.agora.util.AttachmentSourceReader
import com.newoether.agora.util.AttachmentFiles
import java.io.File
import java.net.URI
import java.util.UUID
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

data class VideoSliceConfig(
    val intervalMicros: Long,
    val frameCount: Int,
)

/** Pixel size of an image artifact, the input every provider's image token rule is defined on. */
data class ImagePixelSize(val width: Int, val height: Int)

internal fun imageSampleSizeForBounds(
    width: Int,
    height: Int,
    maxEdge: Int = 2048,
): Int {
    require(width > 0 && height > 0)
    require(maxEdge > 0)
    val longestEdge = maxOf(width, height).toLong()
    var scale = 1
    while ((longestEdge + scale - 1L) / scale > maxEdge) {
        scale = Math.multiplyExact(scale, 2)
    }
    return scale
}

class ImageProcessor(
    private val app: Application,
) {
    suspend fun normalizeImage(source: String, outputOwner: Any? = null): String? = withContext(Dispatchers.IO) {
        var output: File? = null
        try {
            coroutineContext.ensureActive()
            val bounds = android.graphics.BitmapFactory.Options().apply {
                inJustDecodeBounds = true
            }
            openStream(source)?.use { stream ->
                android.graphics.BitmapFactory.decodeStream(stream, null, bounds)
            }
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@withContext null

            val scale = imageSampleSizeForBounds(
                bounds.outWidth,
                bounds.outHeight,
                MAX_IMAGE_EDGE.toInt(),
            )

            coroutineContext.ensureActive()
            val decodeOptions = android.graphics.BitmapFactory.Options().apply {
                inSampleSize = scale
            }
            val bitmap = openStream(source)?.use { stream ->
                android.graphics.BitmapFactory.decodeStream(stream, null, decodeOptions)
            } ?: return@withContext null

            try {
                coroutineContext.ensureActive()
                val target = File(app.filesDir, "img_${UUID.randomUUID()}.jpg")
                outputOwner?.let { AttachmentFiles.retainLivePath(it, target.absolutePath) }
                output = target
                val encoded = target.outputStream().use { stream ->
                    bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 80, stream)
                }
                check(encoded) { "Image encoding failed" }
                coroutineContext.ensureActive()
                target.absolutePath
            } finally {
                bitmap.recycle()
            }
        } catch (cancelled: CancellationException) {
            output?.delete()
            throw cancelled
        } catch (_: Exception) {
            output?.delete()
            null
        }
    }

    /**
     * Bounds-only decode of an already produced artifact. Best effort: an unreadable or unsupported
     * file reports no size, and the token estimate falls back to its byte rule.
     */
    suspend fun measurePixels(source: String): ImagePixelSize? = withContext(Dispatchers.IO) {
        try {
            val bounds = android.graphics.BitmapFactory.Options().apply {
                inJustDecodeBounds = true
            }
            openStream(source)?.use { stream ->
                android.graphics.BitmapFactory.decodeStream(stream, null, bounds)
            }
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) null
            else ImagePixelSize(bounds.outWidth, bounds.outHeight)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            null
        }
    }

    suspend fun extractVideoFrames(
        source: String,
        config: VideoSliceConfig,
        outputOwner: Any? = null,
    ): List<String> = withContext(Dispatchers.IO) {
        val paths = mutableListOf<String>()
        val retriever = MediaMetadataRetriever()
        try {
            setRetrieverSource(retriever, source)
            val frameCount = config.frameCount.coerceAtLeast(1)
            var timeUs = 0L
            repeat(frameCount) { index ->
                coroutineContext.ensureActive()
                val bitmap = retriever.getFrameAtTime(
                    timeUs,
                    MediaMetadataRetriever.OPTION_CLOSEST,
                )
                if (bitmap != null) {
                    val boundedBitmap = bitmap.scaleToMaxEdge(MAX_IMAGE_EDGE.toInt())
                    val output = File(app.filesDir, "vid_${UUID.randomUUID()}_$index.jpg")
                    outputOwner?.let { AttachmentFiles.retainLivePath(it, output.absolutePath) }
                    try {
                        val encoded = output.outputStream().use { stream ->
                            boundedBitmap.compress(
                                android.graphics.Bitmap.CompressFormat.JPEG,
                                80,
                                stream,
                            )
                        }
                        check(encoded) { "Video frame encoding failed" }
                        coroutineContext.ensureActive()
                        paths += output.absolutePath
                    } catch (failure: Exception) {
                        output.delete()
                        throw failure
                    } finally {
                        boundedBitmap.recycle()
                        if (boundedBitmap !== bitmap) bitmap.recycle()
                    }
                }
                timeUs += config.intervalMicros.coerceAtLeast(0L)
            }
            paths
        } catch (cancelled: CancellationException) {
            paths.forEach { File(it).delete() }
            throw cancelled
        } catch (_: Exception) {
            paths.forEach { File(it).delete() }
            emptyList()
        } finally {
            retriever.release()
        }
    }

    private fun setRetrieverSource(
        retriever: MediaMetadataRetriever,
        source: String,
    ) {
        when {
            File(source).isAbsolute -> retriever.setDataSource(source)
            source.startsWith("file:", ignoreCase = true) ->
                retriever.setDataSource(File(URI(source)).absolutePath)
            else -> retriever.setDataSource(app, Uri.parse(source))
        }
    }

    private fun android.graphics.Bitmap.scaleToMaxEdge(
        maxEdge: Int,
    ): android.graphics.Bitmap {
        val longestEdge = maxOf(width, height)
        if (longestEdge <= maxEdge) return this
        val ratio = maxEdge.toDouble() / longestEdge.toDouble()
        val targetWidth = maxOf(1, kotlin.math.round(width * ratio).toInt())
        val targetHeight = maxOf(1, kotlin.math.round(height * ratio).toInt())
        return android.graphics.Bitmap.createScaledBitmap(
            this,
            targetWidth,
            targetHeight,
            true,
        )
    }

    private fun openStream(source: String): java.io.InputStream? =
        AttachmentSourceReader.open(app, source)

    private companion object {
        const val MAX_IMAGE_EDGE = 2048L
    }
}
