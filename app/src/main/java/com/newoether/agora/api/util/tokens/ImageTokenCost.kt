package com.newoether.agora.api.util.tokens

/**
 * Everything known offline about one provider-visible image.
 *
 * Pixel dimensions are what providers actually price, but durable attachment metadata does not
 * always record them, so the byte size stays available as a fallback input. An implementation must
 * define a cost for every combination, including "nothing is known".
 */
data class ImageDescriptor(
    val byteSize: Long? = null,
    val pixelWidth: Int? = null,
    val pixelHeight: Int? = null,
)

/** Prices one image in provider-visible tokens, before any safety margin. */
fun interface ImageTokenCost {
    fun tokens(image: ImageDescriptor): Long
}

/**
 * Byte-proportional image cost. Providers tokenize visual tiles, not bytes, so the byte divisor is
 * a deliberately coarse proxy that keeps a thumbnail well below a full-resolution photo while
 * staying model independent. [BYTES_PER_TOKEN] puts a 768 KiB image at the historical 1024-token
 * figure, and the bounds keep one image between a quarter and one and a half of that figure.
 *
 * This is the fallback for endpoints and attachments whose pixel dimensions are unknown.
 */
object ByteProportionalImageCost : ImageTokenCost {
    const val BYTES_PER_TOKEN = 768L
    const val MIN_TOKENS = 256L
    const val MAX_TOKENS = 1_536L

    /** Cost of an image whose durable metadata records no usable byte size. */
    const val UNKNOWN_TOKENS = 1_024L

    override fun tokens(image: ImageDescriptor): Long = image.byteSize
        ?.takeIf { it > 0L }
        ?.let { bytes -> (bytes / BYTES_PER_TOKEN).coerceIn(MIN_TOKENS, MAX_TOKENS) }
        ?: UNKNOWN_TOKENS
}
