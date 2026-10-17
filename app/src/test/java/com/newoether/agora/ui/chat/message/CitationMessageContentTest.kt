package com.newoether.agora.ui.chat.message

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextDecoration
import com.mikepenz.markdown.annotator.DefaultAnnotatorSettings
import com.mikepenz.markdown.model.markdownAnnotator
import com.newoether.agora.model.CitationAnchor
import com.newoether.agora.model.CitationPolicy
import com.newoether.agora.model.CitationRecord
import com.newoether.agora.model.MessageSegment
import org.intellij.markdown.flavours.gfm.GFMFlavourDescriptor
import org.intellij.markdown.parser.MarkdownParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

internal class CitationMessageContentTest : CitationContentTestFixture() {
    @Test
    fun realOpenAiItemRelativeLinkIsRelocatedAndReplacedByOneNativeCapsule() {
        val url = "https://openai.com/research/index/?utm_source=openai"
        val cited = "([openai.com]($url))"
        val providerAnswer = "OpenAI research: $cited"
        val finalAnswer = "Earlier answer before tool. $providerAnswer"
        val localStart = providerAnswer.indexOf(cited)
        val source = requireNotNull(
            CitationPolicy.create(
                provider = "openai",
                kind = "url",
                title = "OpenAI Research",
                url = url,
                anchors = listOf(
                    CitationAnchor(localStart, localStart + cited.length, cited),
                ),
            ),
        )

        val projection = projectCitationMarkdown(finalAnswer, listOf(source))
        val marker = projection.markers.single()

        assertEquals(
            "Earlier answer before tool. OpenAI research: ${marker.token}",
            projection.markdown,
        )
        assertFalse(projection.markdown.contains("([openai.com]"))
        assertFalse(projection.markdown.contains(url))
        assertEquals("openai.com", marker.label)
    }

    @Test
    fun projectedOpenAiCapsuleSurvivesTheFinalMarkdownAnnotationPath() {
        val url = "https://openai.com/news/research/?utm_source=openai"
        val cited = "([openai.com]($url))"
        val answer = "Research news: $cited"
        val source = citation(
            answer = answer,
            title = "OpenAI Newsroom",
            url = url,
            ranges = arrayOf(answer.indexOf(cited) until answer.length),
        )
        val projection = projectCitationMarkdown(answer, listOf(source))
        val marker = projection.markers.single()
        val paragraph = MarkdownParser(GFMFlavourDescriptor())
            .buildMarkdownTreeFromString(projection.markdown)
            .children
            .single()

        val rendered = buildCitationAwareMarkdownAnnotatedString(
            content = projection.markdown,
            textNode = paragraph,
            style = TextStyle.Default,
            annotatorSettings = DefaultAnnotatorSettings(
                linkTextSpanStyle = TextLinkStyles(),
                codeSpanStyle = SpanStyle(),
                annotator = markdownAnnotator(),
            ),
            citationTokens = mapOf(
                marker.token to CitationInlineToken(
                    inlineId = marker.inlineId,
                    alternateText = "[${marker.label}]",
                ),
            ),
        )

        assertEquals("Research news: [openai.com]", rendered.text)
        assertTrue(
            rendered.getStringAnnotations(start = 0, end = rendered.length)
                .any { annotation -> annotation.item == marker.inlineId },
        )
        assertFalse(rendered.text.contains('('))
    }

    @Test
    fun labelOnlyAnchorReplacesTheContainingSameSourceWrapper() {
        val url = "https://youtube.com/watch?v=source"
        val label = "youtube.com"
        val wrapper = "([$label]($url))"
        val answer = "Grounded claim $wrapper"
        val labelStart = answer.indexOf(label)
        val source = requireNotNull(
            CitationPolicy.create(
                provider = "openai",
                kind = "url",
                title = "YouTube",
                url = url,
                anchors = listOf(
                    CitationAnchor(labelStart, labelStart + label.length, label),
                ),
                answerText = answer,
            ),
        )

        val projection = projectCitationMarkdown(answer, listOf(source))
        val marker = projection.markers.single()

        assertEquals("Grounded claim ${marker.token}", projection.markdown)
    }

    @Test
    fun markdownLinkAnchorReplacesTheContainingSameSourceWrapper() {
        val url = "https://youtube.com/watch?v=source"
        val link = "[youtube.com]($url)"
        val wrapper = "($link)"
        val answer = "Grounded claim $wrapper"
        val linkStart = answer.indexOf(link)
        val source = requireNotNull(
            CitationPolicy.create(
                provider = "openai",
                kind = "url",
                title = "YouTube",
                url = url,
                anchors = listOf(
                    CitationAnchor(linkStart, linkStart + link.length, link),
                ),
                answerText = answer,
            ),
        )

        val projection = projectCitationMarkdown(answer, listOf(source))
        val marker = projection.markers.single()

        assertEquals("Grounded claim ${marker.token}", projection.markdown)
    }

    @Test
    fun uniqueSameSourceWrapperWithoutAnAnchorBecomesOneCapsule() {
        val url = "https://youtube.com/watch?v=source"
        val wrapper = "([youtube.com]($url))"
        val answer = "Grounded claim $wrapper"
        val source = requireNotNull(
            CitationPolicy.create(
                provider = "openai",
                kind = "url",
                title = "YouTube",
                url = url,
            ),
        )

        val projection = projectCitationMarkdown(answer, listOf(source))
        val marker = projection.markers.single()

        assertEquals("Grounded claim ${marker.token}", projection.markdown)
    }

    @Test
    fun ordinaryMarkdownLinkWithoutCitationWrapperRemainsMarkdown() {
        val url = "https://youtube.com/watch?v=source"
        val link = "[youtube.com]($url)"
        val answer = "Open $link"
        val source = requireNotNull(
            CitationPolicy.create(
                provider = "openai",
                kind = "url",
                title = "YouTube",
                url = url,
            ),
        )

        val projection = projectCitationMarkdown(answer, listOf(source))

        assertEquals(answer, projection.markdown)
        assertTrue(projection.markers.isEmpty())
    }

    @Test
    fun repeatedSameSourceWrappersWithoutAnchorsRemainAmbiguous() {
        val url = "https://youtube.com/watch?v=source"
        val wrapper = "([youtube.com]($url))"
        val answer = "$wrapper and $wrapper"
        val source = requireNotNull(
            CitationPolicy.create(
                provider = "openai",
                kind = "url",
                title = "YouTube",
                url = url,
            ),
        )

        val projection = projectCitationMarkdown(answer, listOf(source))

        assertEquals(answer, projection.markdown)
        assertTrue(projection.markers.isEmpty())
    }

    @Test
    fun separatelyAnchoredSameSourceWrappersAreBothReplaced() {
        val url = "https://youtube.com/watch?v=source"
        val label = "youtube.com"
        val wrapper = "([$label]($url))"
        val answer = "$wrapper and $wrapper"
        val firstLabelStart = answer.indexOf(label)
        val secondLabelStart = answer.lastIndexOf(label)
        val source = requireNotNull(
            CitationPolicy.create(
                provider = "openai",
                kind = "url",
                title = "YouTube",
                url = url,
                anchors = listOf(
                    CitationAnchor(
                        firstLabelStart,
                        firstLabelStart + label.length,
                        label,
                    ),
                    CitationAnchor(
                        secondLabelStart,
                        secondLabelStart + label.length,
                        label,
                    ),
                ),
                answerText = answer,
            ),
        )

        val projection = projectCitationMarkdown(answer, listOf(source))
        val marker = projection.markers.single()

        assertEquals("${marker.token} and ${marker.token}", projection.markdown)
    }

    @Test
    fun wrapperUrlContainingParenthesesUsesTheAstRange() {
        val url = "https://en.wikipedia.org/wiki/Function_(mathematics)"
        val wrapper = "([wikipedia.org]($url))"
        val answer = "Grounded claim $wrapper"
        val source = requireNotNull(
            CitationPolicy.create(
                provider = "openai",
                kind = "url",
                title = "Wikipedia",
                url = url,
            ),
        )

        val projection = projectCitationMarkdown(answer, listOf(source))
        val marker = projection.markers.single()

        assertEquals("Grounded claim ${marker.token}", projection.markdown)
    }

    @Test
    fun unsafeParenthesizedLinkIsNotReclassified() {
        val wrapper = "([example](javascript:alert(1)))"
        val source = requireNotNull(
            CitationPolicy.create(
                provider = "openai",
                kind = "url",
                title = "Example",
                url = "javascript:alert(1)",
            ),
        )

        val projection = projectCitationMarkdown(wrapper, listOf(source))

        assertEquals(wrapper, projection.markdown)
        assertTrue(projection.markers.isEmpty())
    }

    @Test
    fun parenthesizedLinkToAnotherTargetIsNotReplaced() {
        val cited = "([example.com](https://example.com/not-the-source))"
        val source = requireNotNull(
            CitationPolicy.create(
                provider = "openai",
                kind = "url",
                title = "OpenAI",
                url = "https://openai.com/research",
                anchors = listOf(CitationAnchor(0, cited.length, cited)),
                answerText = cited,
            ),
        )

        val projection = projectCitationMarkdown(cited, listOf(source))

        assertEquals(cited, projection.markdown)
        assertTrue(projection.markers.isEmpty())
    }

    @Test
    fun ordinaryClaimCitationKeepsClaimAndAppendsCapsule() {
        val answer = "Grounded claim"
        val source = citation(
            answer = answer,
            title = "OpenAI",
            url = "https://openai.com/research",
            ranges = arrayOf(answer.indices),
        )

        val projection = projectCitationMarkdown(answer, listOf(source))
        val marker = projection.markers.single()

        assertEquals("$answer${marker.token}", projection.markdown)
    }

    @Test
    fun adjacentCitationsCollapseIntoOneCapsuleWithAdditionalCount() {
        val answer = "Grounded claim"
        val sources = listOf(
            citation(
                answer = answer,
                title = "Example one",
                url = "https://example.com/one",
                ranges = arrayOf(answer.indices),
            ),
            citation(
                answer = answer,
                title = "Example two",
                url = "https://second.example/two",
                ranges = arrayOf(answer.indices),
            ),
            citation(
                answer = answer,
                title = "Example three",
                url = "https://third.example/three",
                ranges = arrayOf(answer.indices),
            ),
        )

        val projection = projectCitationMarkdown(answer, sources)
        val marker = projection.markers.single()

        assertEquals(sources.map(CitationRecord::sourceId), marker.sources.map(CitationRecord::sourceId))
        assertEquals(2, marker.additionalCount)
        assertEquals("example.com +2", marker.displayLabel)
        assertEquals("$answer${marker.token}", projection.markdown)
        assertEquals(1, projection.markdown.count { it == marker.token })
    }

    @Test
    fun citationsSeparatedByVisibleAnswerTextRemainSeparateCapsules() {
        val answer = "Alpha and beta"
        val first = citation(
            answer = answer,
            title = "First",
            url = "https://first.example/a",
            ranges = arrayOf(0 until 5),
        )
        val second = citation(
            answer = answer,
            title = "Second",
            url = "https://second.example/b",
            ranges = arrayOf(10 until 14),
        )

        val projection = projectCitationMarkdown(answer, listOf(first, second))

        assertEquals(2, projection.markers.size)
        assertTrue(projection.markers.all { it.sources.size == 1 })
        assertTrue(projection.markdown.contains("${projection.markers[0].token} and "))
    }

    @Test
    fun groupedCapsuleUsesGroupedAlternateText() {
        val answer = "Claim"
        val first = citation(
            answer = answer,
            title = "Example",
            url = "https://example.com/a",
            ranges = arrayOf(answer.indices),
        )
        val second = citation(
            answer = answer,
            title = "Second",
            url = "https://second.example/b",
            ranges = arrayOf(answer.indices),
        )
        val marker = projectCitationMarkdown(answer, listOf(first, second)).markers.single()

        val replaced = AnnotatedString("A${marker.token}B")
            .replaceCitationInlineTokens(
                mapOf(
                    marker.token to CitationInlineToken(
                        inlineId = marker.inlineId,
                        alternateText = "[${marker.displayLabel}]",
                    ),
                ),
            )

        assertEquals("A[example.com +1]B", replaced.text)
    }

    @Test
    fun inlineFadeIdentityDoesNotChangeWhenAdjacentSourceCountChanges() {
        val answer = "Claim"
        val first = citation(
            answer = answer,
            title = "Example",
            url = "https://example.com/a",
            ranges = arrayOf(answer.indices),
        )
        val second = citation(
            answer = answer,
            title = "Second",
            url = "https://second.example/b",
            ranges = arrayOf(answer.indices),
        )
        val singleMarker = projectCitationMarkdown(answer, listOf(first)).markers.single()
        val groupedMarker = projectCitationMarkdown(answer, listOf(first, second)).markers.single()

        assertFalse(singleMarker.inlineId == groupedMarker.inlineId)
        assertEquals(
            citationInlineAppearanceKey(singleMarker),
            citationInlineAppearanceKey(groupedMarker),
        )
    }

    @Test
    fun citationCapsuleVisualContractMatchesThinkingCardAndPreservesFade() {
        assertEquals(2, CITATION_CAPSULE_TONAL_ELEVATION_DP)
        assertEquals(0.7f, CITATION_CAPSULE_FOREGROUND_ALPHA, 0.0f)
        assertEquals(320, CITATION_CAPSULE_FADE_DURATION_MS)
        assertEquals(36, CITATION_SOURCES_SUMMARY_MIN_HEIGHT_DP)
        assertEquals(16, CITATION_SOURCES_SUMMARY_HORIZONTAL_PADDING_DP)
        assertEquals(8, CITATION_SOURCES_SUMMARY_VERTICAL_PADDING_DP)
        assertEquals(18, CITATION_SOURCES_SUMMARY_ICON_SIZE_DP)
        assertEquals(8, CITATION_SOURCES_SUMMARY_ICON_GAP_DP)
        assertEquals(84, CITATION_INLINE_PRIMARY_MAX_WIDTH_DP)
        assertEquals(14, CITATION_INLINE_HORIZONTAL_PADDING_DP)
        assertEquals(11, CITATION_INLINE_FONT_SIZE_SP)
        assertEquals(12, CITATION_INLINE_LINE_HEIGHT_SP)
        assertEquals(22, CITATION_INLINE_PLACEHOLDER_HEIGHT_SP)
        assertEquals(4, CITATION_INLINE_SUFFIX_GAP_DP)
        assertEquals(2, CITATION_INLINE_OUTER_SPACER_DP)
        assertEquals(50, CITATION_SOURCE_ROW_SHAPE_PERCENT)
        assertEquals(0.12f, CITATION_SOURCE_BADGE_BACKGROUND_ALPHA, 0.0f)
        assertEquals(0.8f, CITATION_SOURCE_BADGE_FOREGROUND_ALPHA, 0.0f)
    }

    @Test
    fun singleInlinePlaceholderReservesOnlyApprovedOuterSpacingWithoutSuffixSpace() {
        assertEquals(
            78,
            citationInlinePlaceholderWidthPx(
                primaryTextWidthPx = 60,
                suffixTextWidthPx = null,
                primaryMaxWidthPx = 168,
                horizontalPaddingPx = 14,
                suffixGapPx = 4,
                outerSpacingEachSidePx = 2,
            ),
        )
        assertEquals(
            94,
            citationInlinePlaceholderWidthPx(
                primaryTextWidthPx = 60,
                suffixTextWidthPx = 12,
                primaryMaxWidthPx = 168,
                horizontalPaddingPx = 14,
                suffixGapPx = 4,
                outerSpacingEachSidePx = 2,
            ),
        )
        assertEquals(
            186,
            citationInlinePlaceholderWidthPx(
                primaryTextWidthPx = 400,
                suffixTextWidthPx = null,
                primaryMaxWidthPx = 168,
                horizontalPaddingPx = 14,
                suffixGapPx = 4,
                outerSpacingEachSidePx = 2,
            ),
        )
    }

    @Test
    fun everySourcesSheetUsesCountedTitle() {
        assertEquals(
            "3 Sources",
            citationSourcesSheetTitle(
                sourceCount = 3,
                sourcesLabel = "Sources",
            ),
        )
        assertEquals(
            "54 Sources",
            citationSourcesSheetTitle(
                sourceCount = 54,
                sourcesLabel = "Sources",
            ),
        )
    }

    @Test
    fun codeAndLinkAnchorsFallBackToSourcesOnly() {
        val answer = "`code` and [link](https://example.com) plus plain"
        val codeStart = answer.indexOf("code")
        val linkStart = answer.indexOf("link")
        val plainStart = answer.indexOf("plain")
        val source = citation(
            answer = answer,
            title = "Example",
            url = "https://example.com/b",
            ranges = arrayOf(
                codeStart until codeStart + 4,
                linkStart until linkStart + 4,
                plainStart until plainStart + 5,
            ),
        )

        val projection = projectCitationMarkdown(answer, listOf(source))

        val marker = projection.markers.single()
        assertEquals(1, projection.markdown.count { it == marker.token })
        assertEquals(plainStart + 5, projection.markdown.indexOf(marker.token))
    }

    @Test
    fun inlineLabelsPreferNormalizedHostThenFileName() {
        val urlSource = requireNotNull(
            CitationPolicy.create(
                provider = "openai",
                kind = "url",
                title = "OpenAI",
                url = "https://www.OpenAI.com/research",
            ),
        )
        val fileSource = requireNotNull(
            CitationPolicy.create(
                provider = "anthropic",
                kind = "file",
                title = "Quarterly report",
                fileName = "report.pdf",
                providerSourceId = "file-1",
            ),
        )

        assertEquals("openai.com", citationInlineLabel(urlSource))
        assertEquals("report.pdf", citationInlineLabel(fileSource))
    }

    @Test
    fun sourceSummaryVisibilityUsesBottomInformationActionBoundary() {
        assertTrue(citationSummaryVisible(showActions = true, informationVisible = true, sourceCount = 54))
        assertFalse(citationSummaryVisible(showActions = false, informationVisible = true, sourceCount = 54))
        assertFalse(citationSummaryVisible(showActions = true, informationVisible = false, sourceCount = 54))
        assertFalse(citationSummaryVisible(showActions = true, informationVisible = true, sourceCount = 0))
        assertEquals(320, ACTIONS_ENTER_DURATION_MS)
        assertEquals(220, ACTIONS_EXIT_DURATION_MS)
    }

    @Test
    fun timelineSliceShiftsAnchorsWithoutRenumberingSources() {
        val answer = "First. Second."
        val first = citation(
            answer = answer,
            title = "First source",
            url = "https://example.com/first",
            ranges = arrayOf(0 until 6),
        )
        val second = citation(
            answer = answer,
            title = "Second source",
            url = "https://example.com/second",
            ranges = arrayOf(7 until 14),
        )

        val sliced = citationRecordsForAnswerSlice(
            citations = listOf(first, second),
            sliceStart = 7,
            sliceText = "Second.",
        )

        assertTrue(sliced[0].anchors.isEmpty())
        assertEquals(CitationAnchor(0, 7, "Second."), sliced[1].anchors.single())
        val projection = projectCitationMarkdown("Second.", sliced)
        assertEquals(2, projection.markers.single().number)
    }

    @Test
    fun sentinelBecomesInlineContentAnnotation() {
        val answer = "Claim"
        val source = citation(
            answer = answer,
            title = "Example",
            url = "https://example.com/c",
            ranges = arrayOf(0 until answer.length),
        )
        val marker = projectCitationMarkdown(answer, listOf(source)).markers.single()

        val replaced = AnnotatedString("A${marker.token}B")
            .replaceCitationInlineTokens(
                mapOf(
                    marker.token to CitationInlineToken(
                        inlineId = marker.inlineId,
                        alternateText = "[${marker.label}]",
                    ),
                ),
            )

        assertEquals("A[example.com]B", replaced.text)
        assertTrue(
            replaced.getStringAnnotations(start = 1, end = replaced.length - 1)
                .any { it.item == marker.inlineId },
        )
    }

    @Test
    fun chatLinksDelegatePressedFeedbackToTimedColorAnimation() {
        val color = Color(0xFF3367D6)
        val styles = chatLinkTextStyles(color)

        listOf(
            styles.style,
            styles.focusedStyle,
            styles.hoveredStyle,
        ).forEach { style ->
            assertEquals(color, style?.color)
            assertEquals(TextDecoration.None, style?.textDecoration)
        }
        assertEquals(TextDecoration.None, styles.pressedStyle?.textDecoration)
        assertEquals(color, styles.pressedStyle?.color)
        assertEquals(180, MarkdownLinkPressAnimationMillis)
        assertEquals(0.72f, MarkdownLinkPressedAlpha)
    }

    @Test
    fun sourceTitleSearchKeysMatchConversationSearchIdentity() {
        val answer = "Claim"
        val source = citation(
            answer = answer,
            title = "Example",
            url = "https://example.com/d",
            ranges = arrayOf(0 until answer.length),
        )

        assertEquals(
            listOf(
                "message:citation:${source.sourceId}:0:3",
                "message:citation:${source.sourceId}:5:8",
            ),
            citationSourceMatchKeys(
                messageId = "message",
                source = source,
                titleRanges = listOf(0 until 3, 5 until 8),
            ),
        )
    }

    @Test
    fun citationSegmentsDoNotEnterMessageDetailTimeline() {
        val merged = mergeAdjacentSegments(
            listOf(
                MessageSegment(type = "answer", content = "A"),
                MessageSegment(type = "citation", content = "metadata"),
                MessageSegment(type = "answer", content = "B"),
            ),
        )

        assertEquals(listOf(MessageSegment(type = "answer", content = "AB")), merged)
    }
}
