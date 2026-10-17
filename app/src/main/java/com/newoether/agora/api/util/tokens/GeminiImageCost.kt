package com.newoether.agora.api.util.tokens

/**
 * Gemini prices images in fixed-cost crops.
 *
 * From the image-understanding guide (ai.google.dev, read 2026-09-27): an image whose both
 * dimensions are at most 384 px costs 258 tokens. Larger images are tiled into 768x768 tiles at 258
 * tokens each, and the documented "rough formula" for the tile count is a crop unit of
 * `floor(min(width, height) / 1.5)` divided into each dimension. The crop unit is measured in the
 * image's own pixels and each crop is then resized to a tile, so the count depends on the aspect
 * ratio rather than on absolute size: the guide's own example, 960x540, yields 3 x 2 = 6 tiles.
 *
 * Google calls the formula rough, so this is an estimate by the provider's own admission.
 */
object GeminiImageCost : PixelImageTokenCost() {
    private const val TOKENS_PER_TILE = 258L
    private const val SINGLE_TILE_MAX_EDGE_PX = 384
    private const val CROP_UNIT_DIVISOR = 1.5

    override fun tokensForPixels(width: Int, height: Int): Long {
        if (width <= SINGLE_TILE_MAX_EDGE_PX && height <= SINGLE_TILE_MAX_EDGE_PX) {
            return TOKENS_PER_TILE
        }
        val cropUnit = Math.floor(minOf(width, height) / CROP_UNIT_DIVISOR)
            .toInt()
            .coerceAtLeast(1)
        val tiles = blocksCovering(width, cropUnit) * blocksCovering(height, cropUnit)
        return (tiles * TOKENS_PER_TILE).coerceAtLeast(TOKENS_PER_TILE)
    }
}
