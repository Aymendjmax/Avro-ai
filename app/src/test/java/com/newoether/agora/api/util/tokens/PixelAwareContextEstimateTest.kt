package com.newoether.agora.api.util.tokens

import com.newoether.agora.model.AttachmentItem
import com.newoether.agora.model.AttachmentMeta
import com.newoether.agora.model.ChatMessage
import com.newoether.agora.model.Participant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Recorded pixel dimensions must reach the cost model through the durable attachment metadata. */
class PixelAwareContextEstimateTest {

    private val text = "look at this"

    private fun imageMessage(
        pixelWidth: Int?,
        pixelHeight: Int?,
        fileSize: Long?,
        images: List<String> = listOf("shot.jpg"),
        pageCount: Int? = null,
        type: String = "image",
    ) = ChatMessage(
        id = "u1",
        text = text,
        participant = Participant.USER,
    ).copy(
        images = images,
        attachmentMeta = AttachmentMeta(
            items = listOf(
                AttachmentItem(
                    type = type,
                    imageIndex = 0,
                    pageCount = pageCount,
                    pixelWidth = pixelWidth,
                    pixelHeight = pixelHeight,
                    fileSize = fileSize,
                ),
            ),
        ),
    )

    private fun expected(
        imageTokens: Long,
        images: Int = 1,
        envelope: EnvelopeCost = EnvelopeCost.Default,
    ): Int = SafetyMargin.Default.apply(
        envelope.perMessage + HeuristicTextTokenCounter.count(text) + imageTokens * images,
    )

    @Test
    fun anthropicModelPricesAnAttachmentByItsRecordedPixels() {
        val costs = ContextCostModels.forModel("Anthropic:claude-sonnet-4-5")
        val message = imageMessage(
            pixelWidth = 1_000,
            pixelHeight = 1_000,
            // Bytes are recorded too; the pixel rule must win.
            fileSize = 768L * 1_400L,
        )
        assertEquals(
            expected(1_296L),
            CostModelContextEstimator(costs).estimate(listOf(message)),
        )
    }

    @Test
    fun byteFallbackStillAppliesWhenPixelsWereNeverMeasured() {
        val costs = ContextCostModels.forModel("Anthropic:claude-sonnet-4-5")
        val message = imageMessage(pixelWidth = null, pixelHeight = null, fileSize = 768L * 1_400L)
        assertEquals(
            expected(1_400L),
            CostModelContextEstimator(costs).estimate(listOf(message)),
        )
    }

    @Test
    fun everyPageOfOneItemSharesTheRecordedPixelSize() {
        val costs = ContextCostModels.forModel("Anthropic:claude-sonnet-4-5")
        val message = imageMessage(
            pixelWidth = 1_000,
            pixelHeight = 1_000,
            fileSize = 768L * 900L,
            images = listOf("p0.jpg", "p1.jpg", "p2.jpg"),
            pageCount = 3,
            type = "pdf",
        )
        assertEquals(
            expected(1_296L, images = 3),
            CostModelContextEstimator(costs).estimate(listOf(message)),
        )
    }

    @Test
    fun theSameAttachmentCostsDifferentAmountsForDifferentFamilies() {
        val message = imageMessage(pixelWidth = 1_000, pixelHeight = 1_000, fileSize = null)
        val anthropic = CostModelContextEstimator(
            ContextCostModels.forModel("Anthropic:claude-sonnet-4-5"),
        ).estimate(listOf(message))
        val gemini = CostModelContextEstimator(
            ContextCostModels.forModel("Google:models/gemini-2.5-flash"),
        ).estimate(listOf(message))
        val openAi = CostModelContextEstimator(
            ContextCostModels.forModel("OpenAI:gpt-4o"),
        ).estimate(listOf(message))
        assertEquals(expected(1_296L), anthropic)
        assertEquals(expected(4L * 258L), gemini)
        // OpenAI also frames messages more cheaply than the shared default.
        assertEquals(expected(765L, envelope = OpenAiEnvelopeCost), openAi)
        // An unrecognised endpoint has no pixel rule, so a pixel-only attachment stays on the
        // unknown-image fallback.
        assertTrue(
            CostModelContextEstimator(ContextCostModels.forModel("My Gateway:mystery"))
                .estimate(listOf(message)) == expected(ByteProportionalImageCost.UNKNOWN_TOKENS),
        )
    }
}
