package com.newoether.agora.api.util.tokens

/**
 * Base for image costs that price by pixel dimensions.
 *
 * Providers tokenize pixels, not bytes, so a pixel rule is the truthful one. Durable metadata does
 * not always record dimensions, and older messages never will, so anything without a usable pixel
 * size falls back to the byte rule instead of silently costing zero.
 */
abstract class PixelImageTokenCost(
    private val unknownPixelFallback: ImageTokenCost = ByteProportionalImageCost,
) : ImageTokenCost {

    final override fun tokens(image: ImageDescriptor): Long {
        val width = image.pixelWidth?.takeIf { it > 0 }
        val height = image.pixelHeight?.takeIf { it > 0 }
        return if (width != null && height != null) {
            tokensForPixels(width, height)
        } else {
            unknownPixelFallback.tokens(image)
        }
    }

    protected abstract fun tokensForPixels(width: Int, height: Int): Long
}

/** Whole blocks of [divisor] needed to cover [value], the shape every tile and patch rule uses. */
internal fun blocksCovering(value: Int, divisor: Int): Long {
    require(divisor > 0)
    if (value <= 0) return 0L
    return (value.toLong() + divisor - 1L) / divisor
}

/** Aspect-preserving companion dimension, rounded like the providers document it. */
internal fun proportionalEdge(edge: Int, fromEdge: Int, toEdge: Int): Int {
    if (fromEdge <= 0) return 1
    return Math.round(edge.toDouble() * toEdge / fromEdge).toInt().coerceAtLeast(1)
}
