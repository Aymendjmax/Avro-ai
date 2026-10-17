package com.newoether.agora.ui

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

internal class ChatMediaSourceContractTest : UiSourceContractFixture() {
    @Test
    fun mediaViewerAndClipboardImagesUseTheApprovedBoundaries() {
        val root = sourceRoot()
        val main = source(root, "com/newoether/agora/MainActivity.kt")
        val dialog = source(
            root,
            "com/newoether/agora/ui/chat/FullScreenMediaPreviewDialog.kt",
        )
        val composer = source(
            root,
            "com/newoether/agora/ui/chat/bottombar/ChatBottomBar.kt",
        )
        val composerState = source(
            root,
            "com/newoether/agora/ui/chat/bottombar/ChatComposerState.kt",
        )
        val preview = source(
            root,
            "com/newoether/agora/ui/chat/bottombar/AttachmentPreviewRow.kt",
        )
        val storedMessage = source(
            root,
            "com/newoether/agora/ui/chat/message/UserMessageBubble.kt",
        )
        val viewer = source(
            root,
            "com/newoether/agora/ui/chat/FullScreenMediaViewer.kt",
        )
        val payload = source(
            root,
            "com/newoether/agora/viewmodel/MessagePayloadBuilder.kt",
        )
        val generationManager = source(
            root,
            "com/newoether/agora/viewmodel/GenerationManager.kt",
        )
        val imageProcessor = source(
            root,
            "com/newoether/agora/viewmodel/ImageProcessor.kt",
        )
        val sendButton = source(
            root,
            "com/newoether/agora/ui/chat/bottombar/ComposerSendButton.kt",
        )
        val submission = source(
            root,
            "com/newoether/agora/viewmodel/ConversationComposerSubmissionController.kt",
        )
        val chatApp = source(
            root,
            "com/newoether/agora/ui/chat/ChatApp.kt",
        )
        val imageActions = source(
            root,
            "com/newoether/agora/ui/chat/ImageActions.kt",
        )

        assertTrue(main.contains("FullScreenMediaPreviewDialog("))
        assertTrue(dialog.contains("Dialog("))
        assertTrue(dialog.contains(".background(Color.Black)"))
        assertTrue(dialog.contains("visibilityTransition.AnimatedVisibility("))
        assertTrue(dialog.contains("visibilityTransition.animateFloat("))
        assertTrue(dialog.contains("DialogWindowNoSystemDim()"))
        assertTrue(imageActions.contains("DialogWindowNoSystemDim()"))
        assertTrue(dialog.indexOf("FullScreenMediaViewer(") > dialog.indexOf(".background(Color.Black)"))
        assertTrue(composer.contains(".contentReceiver(clipboardImageReceiver)"))
        assertTrue(composer.contains("transferableContent.consume"))
        assertTrue(composer.contains("hasMediaType(MediaType.Image)"))
        assertTrue(composer.contains("importUris(composerOwnerId, imageUris, \"image\", emitSuccessHaptic = false)"))
        assertTrue(composer.contains("inspectAttachmentIngress("))
        assertTrue(composer.contains(
            "composerController.importAttachment(ownerId, attachment) || imported",
        ))
        assertTrue(composer.contains("return remaining"))

        listOf(
            "selectedAttachments",
            "processingStates",
            "pendingSend",
            "attachmentCopyJobs",
            "videoExtractionJobs",
            "fun onPickImages",
            "fun onPickVideos",
            "fun onPickFiles",
            "fun confirmPendingPdfSelection",
            "fun addSlicedVideo",
        ).forEach { legacyOwner ->
            assertFalse(composerState.contains(legacyOwner))
        }
        assertTrue(composerState.contains("controller.importAttachment(ownerId, attachment)"))
        assertTrue(composerState.contains("localPath = file.absolutePath"))
        assertTrue(preview.contains(
            "mediaAttachments.mapIndexed { index, attachment -> attachment.localId to index }.toMap()",
        ))
        assertFalse(preview.contains("indexOf("))
        assertTrue(sendButton.contains("submissionController.submit("))
        assertTrue(sendButton.contains("text = textFieldState.text.toString()"))
        assertTrue(sendButton.contains("snapshot.attachments.map(SelectedAttachment::localId)"))
        assertTrue(sendButton.contains("strokeWidth = 3.dp"))
        assertTrue(sendButton.contains("targetState = icon"))
        assertTrue(sendButton.contains("ComposerActionIcon.BUSY"))
        assertTrue(sendButton.contains("enabled = isActionable"))
        assertTrue(sendButton.contains("val containerColor by animateColorAsState("))
        assertTrue(sendButton.contains("val contentColor by animateColorAsState("))
        assertEquals(
            2,
            sendButton.split("animationSpec = tween(durationMillis = 400)").size - 1,
        )
        assertTrue(sendButton.contains("label = \"fabContainer\""))
        assertTrue(sendButton.contains("label = \"fabContent\""))
        assertTrue(sendButton.contains("durationMillis = COMPOSER_ICON_CROSSFADE_DURATION_MS"))
        assertTrue(sendButton.contains("easing = LinearEasing"))
        assertFalse(sendButton.contains("LocalSoftwareKeyboardController"))
        assertFalse(chatApp.contains("BindDirectAcceptedComposerEffects"))
        assertFalse(
            File(root, "com/newoether/agora/ui/chat/DirectAcceptedComposerEffect.kt").exists(),
        )
        assertFalse(submission.contains("DirectAcceptedComposerEffect"))
        assertFalse(submission.contains("directAcceptedEffects"))
        assertFalse(submission.contains("publishDirectAcceptedEffect"))
        assertFalse(submission.contains("presentationDispatcher"))
        assertTrue(
            submission.contains(
                "request.accepted = acceptance\n" +
                    "                clearAccepted(owner, request)",
            ),
        )
        assertTrue(submission.contains("directAcceptedVersion = current.directAcceptedVersion +"))
        assertTrue(submission.contains("if (request.accepted is SendAcceptance.Direct) 1L else 0L"))
        assertTrue(composer.contains("submissionController.observeState(composerOwnerId)"))
        assertTrue(composer.contains("submissionController.releaseState(composerOwnerId)"))
        val textFieldBlock = source(root, "com/newoether/agora/ui/chat/bottombar/ChatComposerLayout.kt").substringAfter("TextField(")
            .substringBefore("placeholder =")
        assertFalse(textFieldBlock.contains("enabled ="))
        assertTrue(submission.contains("composers.freezeSubmission("))
        assertTrue(submission.contains("composers.awaitProcessing("))
        assertTrue(submission.contains("SelectedAttachment::hasCanonicalReadyArtifact"))
        assertTrue(submission.contains("attachment.storage.transferForSend()"))
        assertTrue(submission.contains("submissionId = request.id"))
        assertTrue(payload.contains("fun buildComposerPayload("))
        assertTrue(payload.contains("AttachmentImportState.READY"))
        assertTrue(payload.contains("val imageIndex = allImages.size"))
        listOf(
            "processImages(",
            "extractVideoFrames(",
            "PdfPageRenderer",
            "AttachmentSourceReader",
            "preparedOwnedPaths",
            "localPath ?:",
            ".uri",
        ).forEach { sendTimeFallback ->
            assertFalse(payload.contains(sendTimeFallback))
        }
        assertFalse(generationManager.contains("suspend fun processImages("))
        assertFalse(imageProcessor.contains("processImagesAndVideos("))
        assertTrue(storedMessage.contains("projectStoredMediaOccurrences("))
        assertFalse(storedMessage.contains("allMediaUrls.indexOf("))
        assertTrue(viewer.contains("initialIndex.coerceIn(0, pdfPages.size - 1)"))
        assertFalse(viewer.contains("pdfPages.indexOf("))
    }

    @Test
    fun streamingFadeKeysToolSummaryCrossfadeByPresentationState() {
        val root = sourceRoot()
        val fade = source(
            root,
            "com/newoether/agora/ui/chat/message/IncrementalStreamingMarkdown.kt",
        ) + source(
            root,
            "com/newoether/agora/ui/chat/message/StreamingGlyphFade.kt",
        )
        val assets = source(
            root,
            "com/newoether/agora/ui/chat/message/MessageBubbleAssets.kt",
        ) + source(
            root,
            "com/newoether/agora/ui/chat/message/ChatMarkdownCode.kt",
        )
        val timeline = source(
            root,
            "com/newoether/agora/ui/chat/message/MessageItemTimeline.kt",
        )
        val tool = source(
            root,
            "com/newoether/agora/ui/chat/message/ToolResultContent.kt",
        ) + source(
            root,
            "com/newoether/agora/ui/chat/message/GeneratedImageThumbnail.kt",
        )
        val stableText = source(
            root,
            "com/newoether/agora/ui/chat/message/StableStreamingText.kt",
        )
        val mutedText = source(
            root,
            "com/newoether/agora/ui/chat/message/StreamingMutedText.kt",
        )
        val lifecycle = source(
            root,
            "com/newoether/agora/ui/chat/message/GenerationLifecycleMotion.kt",
        )
        val messageItem = source(
            root,
            "com/newoether/agora/ui/chat/message/MessageItem.kt",
        )
        val assistant = source(
            root,
            "com/newoether/agora/ui/chat/message/AssistantMessageContent.kt",
        )
        val segments = source(
            root,
            "com/newoether/agora/ui/chat/message/MessageItemSegments.kt",
        )

        assertTrue(fade.contains("fun streamingTailAnnotatedString("))
        assertTrue(fade.contains("fun rememberStreamingGlyphFade("))
        assertFalse(fade.contains("fun Modifier.stableStreamingGlyphFade("))
        assertFalse(fade.contains("BlendMode.DstIn"))
        assertTrue(assets.contains("content = base,"))
        assertTrue(assets.contains("rememberStreamingGlyphFade("))
        assertFalse(assets.contains(".stableStreamingGlyphFade("))
        assertFalse(timeline.contains("StableStreamingText("))
        assertEquals(2, Regex("StreamingMutedText\\(").findAll(timeline).count())
        assertFalse(tool.contains("StableStreamingText("))
        assertFalse(timeline.contains("tailFadeEnabled ="))
        assertFalse(tool.contains("tailFadeEnabled ="))
        assertTrue(mutedText.contains("internal fun ToolSummaryText("))
        assertTrue(mutedText.contains("presentation: ToolPresentation"))
        assertTrue(mutedText.contains("streaming: Boolean"))
        assertEquals(2, Regex("ToolSummaryText\\(").findAll(timeline).count())
        assertEquals(1, Regex("ToolSummaryText\\(").findAll(mutedText).count())
        assertTrue(mutedText.contains("targetState = presentation.state"))
        assertFalse(mutedText.contains("targetState = summary"))
        assertTrue(mutedText.contains("text = renderedSummary"))
        assertTrue(mutedText.contains("!transition.isRunning"))
        val compactBlock = timeline
            .substringAfter("internal fun CompactSegmentBlock(")
            .substringBefore("internal fun retainExpandedLayoutDuringFade(")
        assertTrue(timeline.contains("targetState = collapsedTitle"))
        assertTrue(timeline.contains("compactSegmentTitle:\$expansionKey"))
        // Owner decision 2026-10-13: tool groups fade on expand/collapse like every other group.
        assertFalse(timeline.contains("containsToolSummary"))
        assertTrue(compactBlock.contains("shouldPresentInitiallyExpanded("))
        assertTrue(compactBlock.contains("groupedSegmentExpandedState("))
        assertTrue(compactBlock.contains("targetExpanded && initiallyAutoExpanded"))
        assertFalse(compactBlock.contains("forceOpaque = containsToolSummary"))
        assertTrue(Regex("forceOpaque = seg.type == \"tool\"").findAll(timeline).count() == 2)
        assertTrue(timeline.contains("allowSpatialTransitions -> fadeIn(tween(400)) + expandVertically(tween(400))"))
        assertTrue(timeline.contains("allowSpatialTransitions -> fadeOut(tween(400)) + shrinkVertically(tween(400))"))
        assertTrue(timeline.contains("EnterTransition.None"))
        assertTrue(timeline.contains("ExitTransition.None"))
        assertTrue(tool.contains("private fun ToolActiveContent(text: String, output: String?) {\n    Text("))
        assertTrue(lifecycle.contains("alpha = if (forceOpaque) 1f else value"))
        assertTrue(messageItem.contains(
            "forceOpaque = displayMessage.segments.orEmpty().any { it.type == \"tool\" }",
        ))
        assertTrue(assistant.contains("forceOpaque = detailSegments.any { it.type == \"tool\" }"))
        assertTrue(segments.contains("forceOpaque = forceOpaque"))
        assertTrue(stableText.contains("enabled = streaming && tailFadeEnabled"))
        assertTrue(stableText.contains("initialAlpha = tailFadeInitialAlpha"))
        assertTrue(stableText.contains("fadeCodePoints = tailFadeCodePoints"))
        assertTrue(stableText.contains("spatialBands = tailFadeSpatialBands"))
        assertTrue(mutedText.contains("MUTED_STREAM_TAIL_CODE_POINTS = 42"))
        assertTrue(mutedText.contains("MUTED_STREAM_TAIL_ALPHA_BANDS = 6"))
        assertTrue(mutedText.contains("MUTED_STREAM_TAIL_NEWEST_ALPHA = 0.38f"))
        val toolSummary = mutedText.substringAfter("internal fun ToolSummaryText(")
            .substringBefore("private fun thoughtPreviewTail(")
        assertTrue(toolSummary.contains("Crossfade("))
        assertFalse(toolSummary.contains("StableStreamingText("))
        assertFalse(fade.contains("TOOL_SUMMARY_"))
        assertFalse(fade.contains("toolSummaryTailAnnotatedString"))
        assertFalse(fade.contains("rememberToolSummaryGlyphFade"))
        // Document-level birth-time tracking survives node restructures, block promotion, and
        // subtree re-keying. Births begin only when a snapshot is first published, and the tracker
        // retains only the active not-yet-solid suffix with no fixed character-count cap.
        assertTrue(fade.contains("fadeSample: StreamingTailFadeSample?"))
        assertTrue(fade.contains("fun computeBlockFadeSpecs("))
        assertTrue(fade.contains("internal fun StreamingGlyphFadeSpec?.nodeFade("))
        assertTrue(fade.contains("fadeTracker.update("))
        assertTrue(fade.contains("text = preparedSource,"))
        assertTrue(fade.contains("nowMs = nowMs,"))
        assertTrue(fade.contains("isStreaming || !textDeltas.isNullOrEmpty()"))
        assertTrue(fade.contains("textDeltas = published.textDeltas,"))
        assertTrue(fade.contains("textDeltas = pending.textDeltas,"))
        assertTrue(fade.contains("publishedDeltaSequences"))
        assertFalse(fade.contains("positionDelaysMs"))
        assertFalse(fade.contains("STREAM_DELTA_POSITION_WINDOW_MS"))
        assertTrue(fade.contains("startAlpha + (1f - startAlpha) * progress"))
        assertTrue(fade.contains("spatialAlpha + ageAlpha"))
        assertFalse(fade.contains("STREAM_TAIL_FADE_CODE_POINTS"))
        assertFalse(fade.contains("ArrivalRecord"))
        assertFalse(fade.contains("distributeArrivalBirths"))
        assertFalse(fade.contains("lastVisibleSourceOffset"))
        assertTrue(assets.contains("fade = nodeFade,"))
        assertFalse(assets.contains("enabled = fadeThisNode"))
    }
}
