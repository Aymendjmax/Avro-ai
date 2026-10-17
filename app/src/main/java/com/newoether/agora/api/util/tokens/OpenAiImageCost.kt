package com.newoether.agora.api.util.tokens

import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.sqrt

/**
 * Patch-based OpenAI image cost, the rule the current vision models use.
 *
 * From the images-and-vision guide (platform.openai.com, read 2026-09-27): fit the image inside the
 * detail level's pixel-dimension limit, cover it with 32x32 patches, shrink proportionally when a
 * patch budget applies, then multiply the patch count by the model multiplier and round up.
 *
 * Agora never sets `detail`, so these values are the `auto` behaviour of each model family.
 */
class OpenAiPatchImageCost(
    private val maxEdgePx: Int,
    private val patchBudget: Int?,
    private val multiplier: Double,
) : PixelImageTokenCost() {

    override fun tokensForPixels(width: Int, height: Int): Long {
        val longEdge = maxOf(width, height)
        var fittedWidth = width
        var fittedHeight = height
        if (longEdge > maxEdgePx) {
            if (width >= height) {
                fittedWidth = maxEdgePx
                fittedHeight = proportionalEdge(height, width, maxEdgePx)
            } else {
                fittedHeight = maxEdgePx
                fittedWidth = proportionalEdge(width, height, maxEdgePx)
            }
        }
        var patches = patchesCovering(fittedWidth, fittedHeight)
        val budget = patchBudget
        if (budget != null && patches > budget) {
            val shrink = shrinkFactor(fittedWidth, fittedHeight, budget)
            val scaledWidth = floor(fittedWidth * shrink).toInt().coerceAtLeast(1)
            val scaledHeight = floor(fittedHeight * shrink).toInt().coerceAtLeast(1)
            patches = patchesCovering(scaledWidth, scaledHeight)
        }
        return ceil(patches * multiplier).toLong()
    }

    /**
     * The published shrink factor: a first pass by area, then corrected so the count still fits the
     * budget once both dimensions are truncated to whole patches.
     */
    private fun shrinkFactor(width: Int, height: Int, budget: Int): Double {
        val area = width.toDouble() * height
        val byArea = sqrt(PATCH_PX * PATCH_PX * budget.toDouble() / area)
        val widthPatches = width * byArea / PATCH_PX
        val heightPatches = height * byArea / PATCH_PX
        val correction = minOf(
            floor(widthPatches) / widthPatches,
            floor(heightPatches) / heightPatches,
        )
        return byArea * correction
    }

    private fun patchesCovering(width: Int, height: Int): Long =
        blocksCovering(width, PATCH_PX) * blocksCovering(height, PATCH_PX)

    private companion object {
        const val PATCH_PX = 32
    }
}

/**
 * Tile-based OpenAI image cost, used by the GPT-4o, GPT-4.1, GPT-5 and o-series vision models.
 *
 * From the same guide: fit inside 2048x2048, then scale the shortest side to 768 px if it is larger,
 * count 512 px squares, and add the model's base tokens.
 */
class OpenAiTileImageCost(
    private val baseTokens: Int,
    private val tileTokens: Int,
) : PixelImageTokenCost() {

    override fun tokensForPixels(width: Int, height: Int): Long {
        var fittedWidth = width
        var fittedHeight = height
        val longEdge = maxOf(width, height)
        if (longEdge > MAX_SQUARE_PX) {
            if (width >= height) {
                fittedWidth = MAX_SQUARE_PX
                fittedHeight = proportionalEdge(height, width, MAX_SQUARE_PX)
            } else {
                fittedHeight = MAX_SQUARE_PX
                fittedWidth = proportionalEdge(width, height, MAX_SQUARE_PX)
            }
        }
        val shortEdge = minOf(fittedWidth, fittedHeight)
        if (shortEdge > SHORT_EDGE_PX) {
            val ratio = SHORT_EDGE_PX.toDouble() / shortEdge
            if (fittedWidth <= fittedHeight) {
                fittedHeight = floor(fittedHeight * ratio).toInt().coerceAtLeast(1)
                fittedWidth = SHORT_EDGE_PX
            } else {
                fittedWidth = floor(fittedWidth * ratio).toInt().coerceAtLeast(1)
                fittedHeight = SHORT_EDGE_PX
            }
        }
        val tiles = blocksCovering(fittedWidth, TILE_PX) * blocksCovering(fittedHeight, TILE_PX)
        return baseTokens + tiles * tileTokens
    }

    private companion object {
        const val MAX_SQUARE_PX = 2_048
        const val SHORT_EDGE_PX = 768
        const val TILE_PX = 512
    }
}

/**
 * Which OpenAI image rule a model id uses.
 *
 * The published table pairs each family with a sizing behaviour and a multiplier, so the selection
 * is a list of known families plus a conservative default: patch based, no shrink budget, the 1.2
 * multiplier shared by every current model. An unknown endpoint therefore over-estimates rather than
 * under-estimates. Order matters, because `mini` and `nano` variants price differently from the
 * family they are named after.
 */
object OpenAiImageCosts {
    private val PATCH_DEFAULT = OpenAiPatchImageCost(
        maxEdgePx = 65_535,
        patchBudget = null,
        multiplier = 1.2,
    )

    fun forModelName(modelName: String): ImageTokenCost {
        val name = modelName.substringAfterLast('/').lowercase()
        return when {
            name.startsWith("gpt-4o-mini") -> OpenAiTileImageCost(2_833, 5_667)
            name.startsWith("gpt-4o") -> OpenAiTileImageCost(85, 170)
            name.startsWith("gpt-4.1-mini") -> OpenAiPatchImageCost(2_048, 6_144, 1.62)
            name.startsWith("gpt-4.1-nano") -> OpenAiPatchImageCost(2_048, 6_144, 2.46)
            name.startsWith("gpt-4.1") -> OpenAiTileImageCost(85, 170)
            name.startsWith("gpt-5.1") -> OpenAiTileImageCost(70, 140)
            name.startsWith("gpt-5.2") -> OpenAiPatchImageCost(2_048, 6_144, 1.2)
            name.startsWith("gpt-5.4") -> OpenAiPatchImageCost(2_048, 2_500, 1.2)
            name.startsWith("gpt-5.5") -> OpenAiPatchImageCost(6_000, 10_000, 1.2)
            name.startsWith("gpt-5-nano") -> OpenAiPatchImageCost(2_048, 2_500, 1.5)
            name.startsWith("gpt-5-mini") -> OpenAiPatchImageCost(2_048, 2_500, 1.2)
            name.startsWith("gpt-5") -> OpenAiTileImageCost(70, 140)
            name.startsWith("o4-mini") -> OpenAiPatchImageCost(2_048, 2_500, 1.72)
            name.startsWith("o1") || name.startsWith("o3") -> OpenAiTileImageCost(75, 150)
            else -> PATCH_DEFAULT
        }
    }
}
