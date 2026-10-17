package com.newoether.agora.api.util.tokens

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pixel image costs checked against the figures published by each provider.
 *
 * Every expected number here comes from a provider document, not from this implementation, so a
 * regression in the formulas fails loudly.
 */
class ImagePixelCostTest {

    private fun ImageTokenCost.tokens(width: Int, height: Int): Long =
        tokens(ImageDescriptor(pixelWidth = width, pixelHeight = height))

    @Test
    fun anthropicStandardTierMatchesPublishedVisualTokenTable() {
        val cost = AnthropicImageCost(AnthropicImageCost.Tier.STANDARD)
        // docs.claude.com resolution and token cost table.
        assertEquals(64L, cost.tokens(200, 200))
        assertEquals(1_296L, cost.tokens(1_000, 1_000))
        assertEquals(1_521L, cost.tokens(1_092, 1_092))
        assertEquals(1_560L, cost.tokens(1_920, 1_080))
        assertEquals(1_564L, cost.tokens(2_000, 1_500))
        assertEquals(1_560L, cost.tokens(3_840, 2_160))
    }

    @Test
    fun anthropicHighResolutionTierMatchesPublishedVisualTokenTable() {
        val cost = AnthropicImageCost(AnthropicImageCost.Tier.HIGH_RESOLUTION)
        assertEquals(64L, cost.tokens(200, 200))
        assertEquals(1_296L, cost.tokens(1_000, 1_000))
        // Not resized at this tier, unlike the standard tier's 1560.
        assertEquals(2_691L, cost.tokens(1_920, 1_080))
        assertEquals(4_784L, cost.tokens(3_840, 2_160))
    }

    @Test
    fun anthropicCostIsOrientationIndependent() {
        val cost = AnthropicImageCost(AnthropicImageCost.Tier.STANDARD)
        assertEquals(cost.tokens(1_920, 1_080), cost.tokens(1_080, 1_920))
    }

    @Test
    fun anthropicTierReadsTheModelVersionFromTheId() {
        assertEquals(
            AnthropicImageCost.Tier.STANDARD,
            AnthropicImageCost.tierForModelName("claude-3-7-sonnet-20250219"),
        )
        assertEquals(
            AnthropicImageCost.Tier.STANDARD,
            AnthropicImageCost.tierForModelName("claude-sonnet-4-5-20250929"),
        )
        assertEquals(
            AnthropicImageCost.Tier.STANDARD,
            AnthropicImageCost.tierForModelName("anthropic/claude-opus-4-1"),
        )
        assertEquals(
            AnthropicImageCost.Tier.HIGH_RESOLUTION,
            AnthropicImageCost.tierForModelName("claude-sonnet-4-7-20260115"),
        )
        assertEquals(
            AnthropicImageCost.Tier.HIGH_RESOLUTION,
            AnthropicImageCost.tierForModelName("claude-opus-5-20260601"),
        )
        // No readable version stays on the conservative tier.
        assertEquals(
            AnthropicImageCost.Tier.STANDARD,
            AnthropicImageCost.tierForModelName("claude-latest"),
        )
    }

    @Test
    fun openAiPatchCostMatchesPublishedWorkedExamples() {
        // platform.openai.com worked examples for a 2,500-patch budget and a 1.2 multiplier.
        val cost = OpenAiPatchImageCost(maxEdgePx = 65_535, patchBudget = 2_500, multiplier = 1.2)
        assertEquals(1_229L, cost.tokens(1_024, 1_024))
        assertEquals(3_000L, cost.tokens(2_048, 2_048))
        assertEquals(2_458L, cost.tokens(4_096, 512))
    }

    @Test
    fun openAiPatchCostWithoutBudgetOnlyAppliesThePixelLimit() {
        val cost = OpenAiPatchImageCost(maxEdgePx = 2_048, patchBudget = null, multiplier = 1.2)
        // 2048x1536 covers 64 x 48 patches, and no budget shrinks it.
        assertEquals(3_687L, cost.tokens(2_048, 1_536))
        // A 4096 px long edge is first fitted to 2048 px, so it costs the same as the fitted image.
        assertEquals(cost.tokens(2_048, 1_024), cost.tokens(4_096, 2_048))
    }

    @Test
    fun openAiTileCostMatchesPublishedWorkedExamples() {
        val cost = OpenAiTileImageCost(baseTokens = 85, tileTokens = 170)
        assertEquals(765L, cost.tokens(1_024, 1_024))
        assertEquals(1_105L, cost.tokens(2_048, 4_096))
        // A single tile still pays the base cost.
        assertEquals(255L, cost.tokens(512, 512))
    }

    @Test
    fun openAiModelSelectionFollowsThePublishedSizingTable() {
        assertEquals(255L, OpenAiImageCosts.forModelName("gpt-4o").tokens(512, 512))
        // gpt-4o-mini prices the same tiles far higher.
        assertEquals(
            2_833L + 5_667L,
            OpenAiImageCosts.forModelName("gpt-4o-mini").tokens(512, 512),
        )
        // gpt-4.1-mini is patch based with a 1.62 multiplier, unlike gpt-4.1.
        assertEquals(765L, OpenAiImageCosts.forModelName("gpt-4.1").tokens(1_024, 1_024))
        assertEquals(1_659L, OpenAiImageCosts.forModelName("gpt-4.1-mini").tokens(1_024, 1_024))
        // An unrecognised OpenAI-family model falls back to the conservative patch rule.
        assertEquals(1_229L, OpenAiImageCosts.forModelName("gpt-7-unknown").tokens(1_024, 1_024))
    }

    @Test
    fun geminiCostMatchesPublishedCropRule() {
        // ai.google.dev: 258 tokens when both dimensions are at most 384 px.
        assertEquals(258L, GeminiImageCost.tokens(300, 300))
        assertEquals(258L, GeminiImageCost.tokens(384, 384))
        // The guide's own example: 960x540 has a 360 px crop unit and 3 x 2 = 6 tiles.
        assertEquals(6L * 258L, GeminiImageCost.tokens(960, 540))
        // A square image is always 2 x 2 crops, whatever its size.
        assertEquals(4L * 258L, GeminiImageCost.tokens(1_024, 1_024))
        assertEquals(4L * 258L, GeminiImageCost.tokens(3_000, 3_000))
    }

    @Test
    fun pixelCostsFallBackToTheByteRuleWithoutDimensions() {
        val descriptors = listOf(
            ImageDescriptor(byteSize = 768L * 300L),
            ImageDescriptor(),
            ImageDescriptor(byteSize = 768L * 300L, pixelWidth = 1_024),
        )
        val costs = listOf(
            AnthropicImageCost(AnthropicImageCost.Tier.STANDARD),
            OpenAiPatchImageCost(2_048, 2_500, 1.2),
            OpenAiTileImageCost(85, 170),
            GeminiImageCost,
        )
        costs.forEach { cost ->
            descriptors.forEach { descriptor ->
                assertEquals(
                    ByteProportionalImageCost.tokens(descriptor),
                    cost.tokens(descriptor),
                )
            }
        }
    }
}
