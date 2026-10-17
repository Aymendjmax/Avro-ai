package com.newoether.agora.ui.chat.message

import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontWeight
import com.newoether.agora.model.StreamingTextDelta
import org.intellij.markdown.ast.ASTNode
import org.intellij.markdown.flavours.gfm.GFMFlavourDescriptor
import org.intellij.markdown.parser.MarkdownParser
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class IncrementalStreamingMarkdownTest {
    private val flavour = GFMFlavourDescriptor()

    @Test
    fun tableAfterABlankLineParsesAsATable() {
        val root = MarkdownParser(flavour).buildMarkdownTreeFromString(
            "**Title**\n\n| commit | note |\n|---|---|\n| a19e5e64 | ok |\n",
        )
        assertTrue(root.containsTable())
    }
    @Test
    fun tableBelowATextLineIsPreparedAsItsOwnBlock() {
        val source = "**Title**\n| commit | note |\n|---|---|\n| a19e5e64 | ok |\n"
        val prepared = source.openTableBlocks()
        assertEquals("**Title**\n\n| commit | note |\n|---|---|\n| a19e5e64 | ok |\n", prepared)
        assertTrue(MarkdownParser(flavour).buildMarkdownTreeFromString(prepared).containsTable())
    }
    @Test
    fun renderedAnswerTurnsATableBelowTextIntoATable() {
        val source = "**Title**\n| commit | note |\n|---|---|\n| a19e5e64 | ok |\n"
        val prepared = source.toRenderableMarkdownText()
        val document = IncrementalMarkdownDocument(flavour)
        val snapshot = document.update(prepared, source, isStreaming = false)
        assertTrue(
            (snapshot.stableBlocks.map { it.root } + snapshot.liveBlock?.root)
                .filterNotNull()
                .any { it.containsTable() },
        )
    }
    @Test
    fun proseContainingAPipeIsNotSplitAndTablesInsideFencesStayVerbatim() {
        val prose = "left | right\nand more text\n"
        assertEquals(prose, prose.openTableBlocks())
        val fenced = "```\n**Title**\n| a |\n|---|\n```\n"
        assertEquals(fenced, fenced.openTableBlocks())
    }
    private fun ASTNode.containsTable(): Boolean =
        type.toString().contains("TABLE") || children.any { it.containsTable() }

    @Test
    fun appendOnlyUpdate_scansOnlyDeltaAndReusesStableBlock() {
        val document = IncrementalMarkdownDocument(flavour)
        val first = "First paragraph.\n\nSecond"
        val firstSnapshot = document.update(first, first, isStreaming = true)
        val stable = firstSnapshot.stableBlocks.single()
        val scannedAfterFirst = document.scannedCodeUnits

        val second = "$first paragraph grows"
        val secondSnapshot = document.update(second, second, isStreaming = true)

        assertSame(stable, secondSnapshot.stableBlocks.single())
        assertEquals(
            (second.length - first.length).toLong(),
            document.scannedCodeUnits - scannedAfterFirst,
        )
        assertEquals("Second paragraph grows", secondSnapshot.tail)
    }

    @Test
    fun blankLineInsideFence_doesNotPromoteIncompleteCodeBlock() {
        val document = IncrementalMarkdownDocument(flavour)
        val incomplete = "```kotlin\nval answer = 42\n\n"

        val streaming = document.update(incomplete, incomplete, isStreaming = true)

        assertTrue(streaming.stableBlocks.isEmpty())
        assertEquals(incomplete, streaming.tail)

        val complete = "$incomplete```\n\nFollowing"
        val closed = document.update(complete, complete, isStreaming = true)
        assertEquals(1, closed.stableBlocks.size)
        assertEquals("Following", closed.tail)
    }

    @Test
    fun terminalUpdate_keepsTheLiveTailIdentityAndLayoutPath() {
        val document = IncrementalMarkdownDocument(flavour)
        val text = "Stable.\n\nFinal **tail**"
        val streaming = document.update(text, text, isStreaming = true)
        val stable = streaming.stableBlocks.single()
        val liveStart = streaming.liveBlock?.startOffset

        val terminal = document.update(text, text, isStreaming = false)

        assertSame(stable, terminal.stableBlocks.first())
        assertEquals(1, terminal.stableBlocks.size)
        assertEquals("Final **tail**", terminal.tail)
        assertEquals(liveStart, terminal.liveBlock?.startOffset)
        assertFalse(terminal.isStreaming)
    }

    @Test
    fun streamingAfterTerminal_resetsTheFinalizedDocument() {
        val document = IncrementalMarkdownDocument(flavour)
        val terminalText = "Finished"
        document.update(terminalText, terminalText, isStreaming = false)

        val restarted = document.update(terminalText, terminalText, isStreaming = true)

        assertTrue(restarted.stableBlocks.isEmpty())
        assertEquals(terminalText, restarted.tail)
        assertTrue(restarted.isStreaming)
    }

    @Test
    fun publishedBatchStartsTransparentAndRemainsUnicodeSafe() {
        val text = "older text 😀 最新文字"
        val tracker = StreamingTailFadeTracker()
        val sample = tracker.update(
            text = text,
            nowMs = 1_000L,
        )

        val initial = streamingTailAnnotatedString(
            text = text,
            color = Color.White,
            birthTimesMs = sample.birthTimesMs,
            nowMs = 1_000L,
        )
        assertEquals(1, initial.spanStyles.size)
        assertEquals(0f, initial.spanStyles.single().item.color.alpha, 0.0001f)

        val elapsed = streamingTailAnnotatedString(
            text = text,
            color = Color.White,
            birthTimesMs = sample.birthTimesMs,
            nowMs = 1_100L,
        )
        assertEquals(1, elapsed.spanStyles.size)
        assertEquals(0.2f, elapsed.spanStyles.single().item.color.alpha, 0.0001f)
        elapsed.spanStyles.forEach { range ->
            assertFalse(range.start.splitsSurrogatePair(text))
            assertFalse(range.end.splitsSurrogatePair(text))
        }
    }

    @Test
    fun publishedThinkingDeltaInterpolatesFromConfiguredAlphaToSolid() {
        val text = "Thought delta 😀"
        val sample = StreamingTailFadeTracker().update(text, nowMs = 1_000L)

        val initial = streamingTailAnnotatedString(
            text = text,
            color = Color.White,
            birthTimesMs = sample.birthTimesMs,
            nowMs = 1_000L,
            initialAlpha = 0.38f,
        )
        assertEquals(0.38f, initial.spanStyles.single().item.color.alpha, 0.002f)

        val halfway = streamingTailAnnotatedString(
            text = text,
            color = Color.White,
            birthTimesMs = sample.birthTimesMs,
            nowMs = 1_250L,
            initialAlpha = 0.38f,
        )
        assertEquals(0.69f, halfway.spanStyles.single().item.color.alpha, 0.002f)
        halfway.spanStyles.forEach { range ->
            assertFalse(range.start.splitsSurrogatePair(text))
            assertFalse(range.end.splitsSurrogatePair(text))
        }

        val solid = streamingTailAnnotatedString(
            text = text,
            color = Color.White,
            birthTimesMs = sample.birthTimesMs,
            nowMs = 1_500L,
            initialAlpha = 0.38f,
        )
        assertTrue(solid.spanStyles.isEmpty())
    }

    @Test
    fun mutedSuffixUsesHistoricalBandsCapAndAdditiveAge() {
        val text = "OLD😀${"a".repeat(41)}"
        val sample = StreamingTailFadeTracker().update(text, nowMs = 1_000L)

        val initial = streamingTailAnnotatedString(
            text = text,
            color = Color.White,
            fadeCodePoints = 42,
            birthTimesMs = sample.birthTimesMs,
            nowMs = 1_000L,
            initialAlpha = 0.38f,
            spatialBands = 6,
        )
        assertEquals(6, initial.spanStyles.size)
        assertEquals(3, initial.spanStyles.first().start)
        assertEquals(0.38f, initial.spanStyles.last().item.color.alpha, 0.002f)
        initial.spanStyles.forEach { range ->
            assertFalse(range.start.splitsSurrogatePair(text))
            assertFalse(range.end.splitsSurrogatePair(text))
        }

        val aged = streamingTailAnnotatedString(
            text = text,
            color = Color.White,
            fadeCodePoints = 42,
            birthTimesMs = sample.birthTimesMs,
            nowMs = 1_250L,
            initialAlpha = 0.38f,
            spatialBands = 6,
        )
        assertEquals(0.88f, aged.spanStyles.last().item.color.alpha, 0.002f)

        val solid = streamingTailAnnotatedString(
            text = text,
            color = Color.White,
            fadeCodePoints = 42,
            birthTimesMs = sample.birthTimesMs,
            nowMs = 1_310L,
            initialAlpha = 0.38f,
            spatialBands = 6,
        )
        assertTrue(solid.spanStyles.isEmpty())
        assertFalse(
            streamingTailFadeActive(
                birthTimesMs = sample.birthTimesMs,
                nowMs = 1_310L,
                initialAlpha = 0.38f,
            ),
        )
    }

    @Test
    fun onePublishedSnapshotUsesOneBirthTimeForEveryPublishedGlyph() {
        val text = "abcdefgh"
        val tracker = StreamingTailFadeTracker()
        val sample = tracker.update(
            text = text,
            nowMs = 1_500L,
        )

        assertArrayEquals(LongArray(8) { 1_500L }, sample.birthTimesMs)
        val initial = streamingTailAnnotatedString(
            text = text,
            color = Color.White,
            birthTimesMs = sample.birthTimesMs,
            nowMs = 1_500L,
        )
        assertTrue(initial.spanStyles.all { range -> range.item.color.alpha == 0f })
    }

    @Test
    fun temporalAlphaUsesOnlyBirthTimeThenBecomesSolid() {
        val tracker = StreamingTailFadeTracker()
        tracker.update("ab", nowMs = 1_000L)
        val appended = tracker.update("abcd", nowMs = 1_100L)

        assertArrayEquals(
            longArrayOf(1_000L, 1_000L, 1_100L, 1_100L),
            appended.birthTimesMs,
        )

        val fading = streamingTailAnnotatedString(
            text = "abcd",
            color = Color.White,
            birthTimesMs = appended.birthTimesMs,
            nowMs = 1_200L,
            alphaPerSecond = 2f,
        )
        assertEquals(0.2f, fading.spanStyles.last().item.color.alpha, 0.0001f)

        val halfVisible = streamingTailAnnotatedString(
            "abcd", Color.White, birthTimesMs = appended.birthTimesMs, nowMs = 1_350L,
        )
        assertEquals(0.5f, halfVisible.spanStyles.last().item.color.alpha, 0.002f)
        assertTrue(
            streamingTailFadeActive(
                appended.birthTimesMs,
                nowMs = 1_200L,
            ),
        )

        val solid = streamingTailAnnotatedString(
            text = "abcd",
            color = Color.White,
            birthTimesMs = appended.birthTimesMs,
            nowMs = 1_600L,
            alphaPerSecond = 2f,
        )
        assertTrue(solid.spanStyles.isEmpty())
        assertFalse(
            streamingTailFadeActive(
                appended.birthTimesMs,
                nowMs = 1_600L,
            ),
        )
    }

    @Test
    fun directGlyphAlpha_preservesExistingMarkdownSpansAndMetrics() {
        val base = AnnotatedString.Builder().apply {
            append("bold tail")
            addStyle(
                SpanStyle(fontWeight = FontWeight.Bold),
                start = 0,
                end = 4,
            )
        }.toAnnotatedString()

        val faded = streamingTailAnnotatedString(
            text = base,
            color = Color.White,
            fadeCodePoints = 4,
            birthTimesMs = longArrayOf(1_000L, 1_000L, 1_000L, 1_000L),
            nowMs = 1_000L,
        )

        assertEquals(base.text, faded.text)
        assertTrue(
            faded.spanStyles.any {
                it.start == 0 && it.end == 4 && it.item.fontWeight == FontWeight.Bold
            }
        )
        assertEquals(2, faded.spanStyles.size)
    }

    @Test
    fun directGlyphAlphaPreservesInlineContentAnnotation() {
        val base = AnnotatedString.Builder().apply {
            appendInlineContent(
                id = "citation-inline:test",
                alternateText = "[openai.com]",
            )
        }.toAnnotatedString()

        val faded = streamingTailAnnotatedString(
            text = base,
            color = Color.White,
            fadeCodePoints = 4,
            birthTimesMs = longArrayOf(1_000L, 1_000L, 1_000L, 1_000L),
            nowMs = 1_000L,
        )

        assertEquals("[openai.com]", faded.text)
        assertTrue(
            faded.getStringAnnotations(start = 0, end = faded.length)
                .any { it.item == "citation-inline:test" },
        )
    }

    @Test
    fun promotedTail_retainsOriginalGlyphAges() {
        val tracker = StreamingTailFadeTracker()
        tracker.update("closed\n\nlive", nowMs = 1_000L)

        val promoted = tracker.update("live", nowMs = 1_200L)

        assertArrayEquals(
            longArrayOf(1_000L, 1_000L, 1_000L, 1_000L),
            promoted.birthTimesMs,
        )
    }

    @Test
    fun rendererOnlyRewritePreservesPublishedAgesWithoutReplayingFade() {
        val tracker = StreamingTailFadeTracker()
        val deltas = listOf(StreamingTextDelta(sequence = 0L, codePointCount = 6))
        val initial = tracker.update("answer", nowMs = 1_000L, textDeltas = deltas)

        val rewritten = tracker.update(
            text = "an[token]swer",
            nowMs = 1_100L,
            textDeltas = deltas,
        )
        assertArrayEquals(initial.birthTimesMs, rewritten.birthTimesMs)

        val solidRewrite = tracker.update(
            text = "an[terminal-token]swer",
            nowMs = 1_700L,
            textDeltas = deltas,
        )
        assertTrue(solidRewrite.birthTimesMs.isEmpty())
    }

    @Test
    fun rendererRewriteWithNewDeltaBirthsOnlyTheNewTerminalSuffix() {
        val tracker = StreamingTailFadeTracker()
        val initialDelta = StreamingTextDelta(sequence = 0L, codePointCount = 4)
        tracker.update("abcd", nowMs = 1_000L, textDeltas = listOf(initialDelta))

        val rewritten = tracker.update(
            text = "ab[token]cdef",
            nowMs = 1_100L,
            textDeltas = listOf(
                initialDelta,
                StreamingTextDelta(sequence = 1L, codePointCount = 2),
            ),
        )

        assertArrayEquals(
            longArrayOf(1_000L, 1_000L, 1_000L, 1_000L, 1_100L, 1_100L),
            rewritten.birthTimesMs,
        )
    }

    @Test
    fun interactionCommitGate_holdsOnlyTheLatestSnapshotUntilGestureEnds() {
        val gate = StreamingInteractionCommitGate<String>()
        val codeBlock = Any()

        assertEquals("initial", gate.offer("initial"))
        assertNull(gate.setActive(codeBlock, active = true))
        assertNull(gate.offer("stream one"))
        assertNull(gate.offer("stream two"))
        assertEquals("stream two", gate.setActive(codeBlock, active = false))
        assertEquals("terminal", gate.offer("terminal"))
    }

    @Test
    fun interactionCommitGate_waitsForEveryActiveCodeBlockOwner() {
        val gate = StreamingInteractionCommitGate<String>()
        val first = Any()
        val second = Any()

        gate.setActive(first, active = true)
        gate.setActive(second, active = true)
        assertNull(gate.offer("latest"))
        assertNull(gate.setActive(first, active = false))
        assertEquals("latest", gate.setActive(second, active = false))
    }

    @Test
    fun tracker_hasNoCharacterCountCapAndStampsTheFirstPublishedBatch() {
        val tracker = StreamingTailFadeTracker()
        val text = "x".repeat(4_096)

        val sample = tracker.update(text, nowMs = 1_234L)

        assertEquals(4_096, sample.birthTimesMs.size)
        assertTrue(sample.birthTimesMs.all { it == 1_234L })
    }

    @Test
    fun tracker_prunesSolidPrefixBeforeStampingTheNextPublishedBatch() {
        val tracker = StreamingTailFadeTracker()
        tracker.update("abcd", nowMs = 1_000L)

        val sample = tracker.update("abcde", nowMs = 1_700L)

        assertArrayEquals(longArrayOf(1_700L), sample.birthTimesMs)
    }

    @Test
    fun blockFadeSpecs_keepPromotedBlockTailAging() {
        // Document: "para one\n\n" (10 cp, promoted) + "cont" (4 cp, live). The fade sample
        // covers the final 6 cp (window start cp 8): the promoted block's last 2 cp and the
        // live block's 4 cp. Both blocks must keep aging instead of snapping to solid.
        val parser = MarkdownParser(flavour)
        val snapshot = StreamingMarkdownSnapshot(
            inputContent = "para one\n\ncont",
            stableBlocks = listOf(
                StableMarkdownBlock(
                    startOffset = 0,
                    endOffset = 10,
                    sourceContent = "para one\n\n",
                    root = parser.buildMarkdownTreeFromString("para one\n\n"),
                )
            ),
            tail = "cont",
            liveBlock = LiveMarkdownBlock(
                startOffset = 10,
                sourceContent = "cont",
                root = parser.buildMarkdownTreeFromString("cont"),
            ),
            isStreaming = true,
            fadeSample = StreamingTailFadeSample(
                observedAtMs = 2_000L,
                birthTimesMs = longArrayOf(1_000L, 1_010L, 1_020L, 1_030L, 1_040L, 1_050L),
            ),
        )

        val specs = computeBlockFadeSpecs(snapshot)

        assertEquals(2, specs.size)
        assertEquals(2, specs[0]?.tailCodePoints)
        assertArrayEquals(longArrayOf(1_000L, 1_010L), specs[0]!!.birthTimesMs)
        assertEquals(4, specs[1]?.tailCodePoints)
        assertArrayEquals(longArrayOf(1_020L, 1_030L, 1_040L, 1_050L), specs[1]!!.birthTimesMs)
    }

    @Test
    fun nodeFade_mapsWindowOverlapAcrossNodes() {
        val spec = StreamingGlyphFadeSpec(
            tailCodePoints = 3,
            birthTimesMs = longArrayOf(1_000L, 1_010L, 1_020L),
        )
        // Block cp count = 10, window covers cp 7..10. Node [char 5, char 8) = cp 5..8 overlaps
        // cp 7..8 (1 cp, first birth entry).
        val midNode = spec.nodeFade(blockContent = "0123456789", nodeStart = 5, nodeEnd = 8)
        assertEquals(1, midNode?.tailCodePoints)
        assertArrayEquals(longArrayOf(1_000L), midNode!!.birthTimesMs)

        // Node fully inside the window (cp 8..10): the last two birth entries.
        val tailNode = spec.nodeFade(blockContent = "0123456789", nodeStart = 8, nodeEnd = 10)
        assertEquals(2, tailNode?.tailCodePoints)
        assertArrayEquals(longArrayOf(1_010L, 1_020L), tailNode!!.birthTimesMs)

        // Node entirely outside the window.
        assertNull(spec.nodeFade(blockContent = "0123456789", nodeStart = 0, nodeEnd = 4))
    }

    @Test
    fun tracker_serializesWorkerAndInteractionUpdates() {
        val tracker = StreamingTailFadeTracker()
        val executor = Executors.newFixedThreadPool(2)
        val start = CountDownLatch(1)
        val texts = listOf("x".repeat(4_096), "x".repeat(32))
        try {
            val futures = (0..1).map { lane ->
                executor.submit {
                    start.await()
                    repeat(500) { index ->
                        tracker.update(texts[(index + lane) % 2], (index + 1).toLong())
                    }
                }
            }
            start.countDown()
            futures.forEach { it.get(10, TimeUnit.SECONDS) }
        } finally {
            executor.shutdownNow()
        }
    }

    private fun Int.splitsSurrogatePair(text: String): Boolean =
        this in 1 until text.length &&
            Character.isHighSurrogate(text[this - 1]) &&
            Character.isLowSurrogate(text[this])
}
