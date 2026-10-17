package com.newoether.agora.api.util.tokens

/**
 * Anthropic prices images in 28x28 visual tokens, capped per model tier.
 *
 * From the vision documentation (docs.claude.com, read 2026-09-27): an image costs
 * `ceil(width / 28) * ceil(height / 28)` visual tokens. Each tier has a long-edge limit and a
 * visual-token limit, and an image larger than either is downscaled to "the largest size that fits
 * the tier's limits while preserving its aspect ratio".
 */
class AnthropicImageCost(private val tier: Tier) : PixelImageTokenCost() {

    /** Published tier limits. Standard covers everything before Claude 4.7. */
    enum class Tier(val maxLongEdgePx: Int, val maxVisualTokens: Long) {
        STANDARD(maxLongEdgePx = 1_568, maxVisualTokens = 1_568L),
        HIGH_RESOLUTION(maxLongEdgePx = 2_576, maxVisualTokens = 4_784L),
    }

    override fun tokensForPixels(width: Int, height: Int): Long {
        val longEdge = maxOf(width, height)
        // "Largest size that fits both limits": visual tokens never fall as the long edge grows, so
        // the largest admissible long edge is found by bisection instead of a closed form, which the
        // provider does not publish.
        var low = 1
        var high = minOf(longEdge, tier.maxLongEdgePx)
        var best = 1
        while (low <= high) {
            val mid = low + (high - low) / 2
            if (visualTokens(width, height, mid) <= tier.maxVisualTokens) {
                best = mid
                low = mid + 1
            } else {
                high = mid - 1
            }
        }
        return visualTokens(width, height, best)
    }

    private fun visualTokens(width: Int, height: Int, scaledLongEdge: Int): Long {
        val longEdge = maxOf(width, height)
        val shortEdge = minOf(width, height)
        val scaledShortEdge = proportionalEdge(shortEdge, longEdge, scaledLongEdge)
        return blocksCovering(scaledLongEdge, PATCH_PX) * blocksCovering(scaledShortEdge, PATCH_PX)
    }

    companion object {
        private const val PATCH_PX = 28
        private const val HIGH_RESOLUTION_MAJOR = 4
        private const val HIGH_RESOLUTION_MINOR = 7

        /**
         * The high-resolution tier starts at Claude 4.7. An id with no readable version stays on the
         * standard tier, which is what every model before 4.7 uses.
         */
        fun tierForModelName(modelName: String): Tier =
            if (
                AnthropicModelId.parse(modelName)
                    .versionAtLeast(HIGH_RESOLUTION_MAJOR, HIGH_RESOLUTION_MINOR)
            ) {
                Tier.HIGH_RESOLUTION
            } else {
                Tier.STANDARD
            }

        fun forModelName(modelName: String): ImageTokenCost =
            AnthropicImageCost(tierForModelName(modelName))
    }
}
