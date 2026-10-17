package com.newoether.agora.ui.chat

import androidx.compose.animation.core.FastOutSlowInEasing
import com.newoether.agora.model.ChatMessage
import com.newoether.agora.model.MessageSegment
import com.newoether.agora.model.MessageStatus
import com.newoether.agora.model.Participant
import com.newoether.agora.ui.chat.message.AssistantInlineActivityMode
import com.newoether.agora.ui.chat.message.assistantInlineActivityMode
import com.newoether.agora.ui.chat.message.resolveSegmentDetailMessage
import com.newoether.agora.ui.chat.message.segmentDetailIndicesForSnapshot
import com.newoether.agora.ui.chat.message.userBubbleSizeAnimationEnabled
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class MessageListStreamingTailTest {
    @Test
    fun userBubbleSizeAnimationStartsOnlyAfterPayloadHydration() {
        assertFalse(userBubbleSizeAnimationReady(hydrationPending = true))
        assertTrue(userBubbleSizeAnimationReady(hydrationPending = false))
        assertFalse(
            userBubbleSizeAnimationEnabled(
                sizeAnimationReady = true,
                allowSpatialTransitions = false,
            ),
        )
        assertTrue(
            userBubbleSizeAnimationEnabled(
                sizeAnimationReady = true,
                allowSpatialTransitions = true,
            ),
        )
    }

    @Test
    fun attachedStreamingTailSurvivesContentGrowth() {
        var mode = reduceStreamingTailFollow(
            StreamingTailFollowMode.INACTIVE,
            StreamingTailFollowEvent.GenerationChanged(
                active = true,
            ),
        )
        mode = reduceStreamingTailFollow(
            mode,
            StreamingTailFollowEvent.ViewportProximityChanged(
                withinAttachThreshold = true,
                scrollInProgress = false,
            ),
        )
        assertEquals(StreamingTailFollowMode.ATTACHED, mode)

        mode = reduceStreamingTailFollow(
            mode,
            StreamingTailFollowEvent.GenerationChanged(active = true),
        )
        assertEquals(StreamingTailFollowMode.ATTACHED, mode)
    }

    @Test
    fun realUserDragDetachesUntilAnExplicitBottomRequestCompletes() {
        var mode = reduceStreamingTailFollow(
            StreamingTailFollowMode.INACTIVE,
            StreamingTailFollowEvent.GenerationChanged(
                active = true,
            ),
        )
        mode = reduceStreamingTailFollow(
            mode,
            StreamingTailFollowEvent.ViewportProximityChanged(
                withinAttachThreshold = true,
                scrollInProgress = false,
            ),
        )
        mode = reduceStreamingTailFollow(
            mode,
            StreamingTailFollowEvent.UserDragStarted,
        )
        assertEquals(StreamingTailFollowMode.DETACHED, mode)

        mode = reduceStreamingTailFollow(
            mode,
            StreamingTailFollowEvent.GenerationChanged(active = true),
        )
        assertEquals(StreamingTailFollowMode.DETACHED, mode)

        mode = reduceStreamingTailFollow(
            mode,
            StreamingTailFollowEvent.ViewportProximityChanged(
                withinAttachThreshold = true,
                scrollInProgress = true,
            ),
        )
        assertEquals(StreamingTailFollowMode.DETACHED, mode)

        mode = reduceStreamingTailFollow(
            mode,
            StreamingTailFollowEvent.ViewportProximityChanged(
                withinAttachThreshold = true,
                scrollInProgress = false,
            ),
        )
        assertEquals(StreamingTailFollowMode.ATTACHED, mode)
    }

    @Test
    fun detachedTailIgnoresStreamingGeometryUntilExplicitUserReturn() {
        var mode = reduceStreamingTailFollow(
            StreamingTailFollowMode.DETACHED,
            StreamingTailFollowEvent.GenerationChanged(
                active = true,
            ),
        )
        assertEquals(StreamingTailFollowMode.DETACHED, mode)
    }

    @Test
    fun attachedGenerationSettlesFinalLayoutBeforeReleasingTailFollow() {
        var mode = reduceStreamingTailFollow(
            StreamingTailFollowMode.ATTACHED,
            StreamingTailFollowEvent.GenerationChanged(
                active = false,
            ),
        )

        assertEquals(StreamingTailFollowMode.SETTLING, mode)
        mode = reduceStreamingTailFollow(
            mode,
            StreamingTailFollowEvent.SettlingFinished,
        )
        assertEquals(StreamingTailFollowMode.INACTIVE, mode)
    }

    @Test
    fun detachedGenerationDoesNotReattachWhileFinishing() {
        val mode = reduceStreamingTailFollow(
            StreamingTailFollowMode.DETACHED,
            StreamingTailFollowEvent.GenerationChanged(
                active = false,
            ),
        )

        assertEquals(StreamingTailFollowMode.INACTIVE, mode)
    }

    @Test
    fun streamingTailAttachesOnlyAfterNearBottomMotionSettles() {
        var mode = reduceStreamingTailFollow(
            StreamingTailFollowMode.INACTIVE,
            StreamingTailFollowEvent.GenerationChanged(
                active = true,
            ),
        )
        assertEquals(StreamingTailFollowMode.ARMED, mode)

        mode = reduceStreamingTailFollow(
            mode,
            StreamingTailFollowEvent.ViewportProximityChanged(
                withinAttachThreshold = true,
                scrollInProgress = true,
            ),
        )
        assertEquals(StreamingTailFollowMode.ARMED, mode)

        mode = reduceStreamingTailFollow(
            mode,
            StreamingTailFollowEvent.ViewportProximityChanged(
                withinAttachThreshold = true,
                scrollInProgress = false,
            ),
        )
        assertEquals(StreamingTailFollowMode.ATTACHED, mode)
    }

    @Test
    fun programmaticSendScrollPausesTailWithoutCreatingASecondScrollOwner() {
        var mode = reduceStreamingTailGenerationAvailability(
            current = StreamingTailFollowMode.INACTIVE,
            active = true,
            autoFollowEnabled = false,
            autoFollowPaused = true,
        )
        assertEquals(StreamingTailFollowMode.INACTIVE, mode)

        mode = reduceStreamingTailGenerationAvailability(
            current = mode,
            active = true,
            autoFollowEnabled = true,
            autoFollowPaused = false,
        )
        assertEquals(StreamingTailFollowMode.ARMED, mode)
    }

    @Test
    fun absoluteBottomScrollIsAFollowHandoffRatherThanDetachment() {
        val availability = streamingTailAvailability(
            generationActive = true,
            blocked = false,
            programmaticHandoff = true,
        )

        assertFalse(availability.enabled)
        assertTrue(availability.paused)
        assertEquals(
            StreamingTailFollowMode.ATTACHED,
            reduceStreamingTailGenerationAvailability(
                current = StreamingTailFollowMode.ATTACHED,
                active = true,
                autoFollowEnabled = availability.enabled,
                autoFollowPaused = availability.paused,
            ),
        )
    }

    @Test
    fun realCompetingUiStillDisablesStreamingFollow() {
        val availability = streamingTailAvailability(
            generationActive = true,
            blocked = true,
            programmaticHandoff = true,
        )

        assertFalse(availability.enabled)
        assertFalse(availability.paused)
    }

    @Test
    fun nonScrollCompetitionStillDetachesStreamingTail() {
        val mode = reduceStreamingTailGenerationAvailability(
            current = StreamingTailFollowMode.ATTACHED,
            active = true,
            autoFollowEnabled = false,
            autoFollowPaused = false,
        )

        assertEquals(StreamingTailFollowMode.DETACHED, mode)
    }

    @Test
    fun inlineAndAnswerStatesHaveExactlyOneWhiteDotOwner() {
        val empty = ChatMessage(
            id = "empty",
            text = "",
            status = MessageStatus.SENDING,
            participant = Participant.MODEL,
        )
        val retryBeforeOutput = empty.copy(
            id = "retry-before-output",
            retryText = "Retrying 1/5",
        )
        val retryAfterPartialAnswer = empty.copy(
            id = "retry-after-answer",
            text = "Partial answer",
            segments = listOf(MessageSegment(type = "answer", content = "Partial answer")),
            retryText = "Retrying 1/5",
        )
        val answer = empty.copy(
            id = "answer",
            text = "Answer",
            segments = listOf(MessageSegment(type = "answer", content = "Answer")),
        )
        val answerWithCitation = answer.copy(
            id = "answer-with-citation",
            segments = checkNotNull(answer.segments) +
                MessageSegment(type = "citation", content = "metadata"),
        )
        val cardThenAnswer = answer.copy(
            id = "card-then-answer",
            segments = listOf(
                MessageSegment(type = "thought", content = "Reasoning"),
                MessageSegment(type = "answer", content = "Answer"),
            ),
        )

        val activeOwners = listOf(
            Triple(
                "empty",
                assistantInlineActivityMode(true, false, false, null) !=
                    AssistantInlineActivityMode.NONE,
                shouldShowStreamingTailIndicator(true, false, empty),
            ),
            Triple(
                "retry before output",
                assistantInlineActivityMode(true, false, false, retryBeforeOutput.retryText) !=
                    AssistantInlineActivityMode.NONE,
                shouldShowStreamingTailIndicator(true, false, retryBeforeOutput),
            ),
            Triple(
                "retry after partial answer",
                assistantInlineActivityMode(true, true, false, retryAfterPartialAnswer.retryText) !=
                    AssistantInlineActivityMode.NONE,
                shouldShowStreamingTailIndicator(true, false, retryAfterPartialAnswer),
            ),
            Triple(
                "answer",
                assistantInlineActivityMode(true, true, false, null) !=
                    AssistantInlineActivityMode.NONE,
                shouldShowStreamingTailIndicator(true, false, answer),
            ),
            Triple(
                "answer with citation",
                assistantInlineActivityMode(true, true, false, null) !=
                    AssistantInlineActivityMode.NONE,
                shouldShowStreamingTailIndicator(true, false, answerWithCitation),
            ),
            Triple(
                "card followed by answer",
                assistantInlineActivityMode(true, true, true, null) !=
                    AssistantInlineActivityMode.NONE,
                shouldShowStreamingTailIndicator(true, false, cardThenAnswer),
            ),
        )

        activeOwners.forEach { (label, inlineVisible, tailVisible) ->
            assertEquals(label, 1, listOf(inlineVisible, tailVisible).count { it })
        }
    }

    @Test
    fun visibleCardTailsHaveNoWhiteDotOwner() {
        val empty = ChatMessage(
            id = "empty",
            text = "",
            status = MessageStatus.SENDING,
            participant = Participant.MODEL,
        )
        val thinking = empty.copy(
            id = "thinking",
            status = MessageStatus.THINKING,
            segments = listOf(MessageSegment(type = "thought", content = "Reasoning")),
        )
        val tool = empty.copy(
            id = "tool",
            status = MessageStatus.TOOL_CALLING,
            segments = listOf(MessageSegment(type = "tool", toolState = "running")),
        )
        val transcription = empty.copy(
            id = "transcription",
            status = MessageStatus.TRANSCRIBING,
            segments = listOf(MessageSegment(type = "transcription", content = "Image text")),
        )
        val answerSegment = MessageSegment(type = "answer", content = "Earlier answer")
        val cardTailMessages = listOf(
            thinking,
            tool,
            transcription,
            thinking.copy(id = "sending-thinking", status = MessageStatus.SENDING),
            tool.copy(id = "sending-tool", status = MessageStatus.SENDING),
            transcription.copy(id = "sending-transcription", status = MessageStatus.SENDING),
            thinking.copy(
                id = "answer-then-thinking",
                text = answerSegment.content,
                status = MessageStatus.SENDING,
                segments = listOf(answerSegment) + checkNotNull(thinking.segments),
            ),
            tool.copy(
                id = "answer-then-tool",
                text = answerSegment.content,
                status = MessageStatus.SENDING,
                segments = listOf(answerSegment) + checkNotNull(tool.segments),
            ),
            transcription.copy(
                id = "answer-then-transcription",
                text = answerSegment.content,
                status = MessageStatus.SENDING,
                segments = listOf(answerSegment) + checkNotNull(transcription.segments),
            ),
        )

        cardTailMessages.forEach { message ->
            assertEquals(
                message.id,
                AssistantInlineActivityMode.NONE,
                assistantInlineActivityMode(
                    generationActive = true,
                    hasAnswer = message.text.isNotBlank(),
                    hasVisibleInfoSegment = true,
                    retryText = null,
                ),
            )
            assertFalse(message.id, shouldShowStreamingTailIndicator(true, false, message))
        }
    }

    @Test
    fun stoppingAndTerminalGenerationStatesHaveNoWhiteDotOwner() {
        val activeAnswer = ChatMessage(
            id = "active-answer",
            text = "Answer",
            status = MessageStatus.SENDING,
            participant = Participant.MODEL,
            segments = listOf(MessageSegment(type = "answer", content = "Answer")),
        )
        val terminalMessages = listOf(
            activeAnswer.copy(id = "success", status = MessageStatus.SUCCESS),
            activeAnswer.copy(id = "stopped", status = MessageStatus.STOPPED),
            activeAnswer.copy(id = "error", status = MessageStatus.ERROR),
        )

        assertEquals(
            AssistantInlineActivityMode.NONE,
            assistantInlineActivityMode(true, true, false, null),
        )
        assertFalse(shouldShowStreamingTailIndicator(true, true, activeAnswer))
        terminalMessages.forEach { message ->
            assertEquals(
                AssistantInlineActivityMode.NONE,
                assistantInlineActivityMode(false, true, false, null),
            )
            assertFalse(shouldShowStreamingTailIndicator(true, false, message))
        }
    }

    @Test
    fun stickToBottomOnKeepsExistingAutoFollowAvailability() {
        val stickToBottom = true
        val availability = streamingTailAvailability(
            generationActive = true,
            blocked = false,
            programmaticHandoff = false,
        )

        assertEquals(
            StreamingTailFollowMode.ARMED,
            reduceStreamingTailGenerationAvailability(
                current = StreamingTailFollowMode.INACTIVE,
                active = true,
                autoFollowEnabled = availability.enabled && stickToBottom,
                autoFollowPaused = availability.paused,
            ),
        )
    }

    @Test
    fun stickToBottomOffWaitsForProgrammaticHandoffThenDetaches() {
        val stickToBottom = false
        val handoff = streamingTailAvailability(
            generationActive = true,
            blocked = false,
            programmaticHandoff = true,
        )
        var mode = reduceStreamingTailGenerationAvailability(
            current = StreamingTailFollowMode.ATTACHED,
            active = true,
            autoFollowEnabled = handoff.enabled && stickToBottom,
            autoFollowPaused = handoff.paused,
        )
        assertEquals(StreamingTailFollowMode.ATTACHED, mode)

        val available = streamingTailAvailability(
            generationActive = true,
            blocked = false,
            programmaticHandoff = false,
        )
        mode = reduceStreamingTailGenerationAvailability(
            current = mode,
            active = true,
            autoFollowEnabled = available.enabled && stickToBottom,
            autoFollowPaused = available.paused,
        )

        assertEquals(StreamingTailFollowMode.DETACHED, mode)
    }

    @Test
    fun stoppingHidesTheAnswerTailWhileRetainingOnlyItsStatusSlot() {
        val answer = ChatMessage(
            id = "answer-tail",
            text = "Answer",
            status = MessageStatus.SENDING,
            participant = Participant.MODEL,
            segments = listOf(MessageSegment(type = "answer", content = "Answer")),
        )
        val active = streamingTailPresentation(true, false, answer)
        val stopping = streamingTailPresentation(false, true, answer)
        val stopped = streamingTailPresentation(
            isLoading = false,
            isStopping = false,
            message = answer.copy(status = MessageStatus.STOPPED),
        )

        assertTrue(active.visible)
        assertFalse(active.retainLayout)
        assertFalse(stopping.visible)
        assertTrue(stopping.retainLayout)
        assertTrue(stopping.retainLayout && !stopping.visible)
        assertFalse(stopped.visible)
        assertFalse(stopped.retainLayout)
    }

    @Test
    fun activeStreamingPayloadAlwaysUsesLatestRuntimeSnapshot() {
        val stub = ChatMessage(
            id = "active",
            text = "",
            participant = Participant.MODEL,
            status = MessageStatus.TOOL_CALLING,
        )
        val firstCall = stub.copy(
            segments = listOf(
                MessageSegment(
                    type = "tool",
                    toolCallId = "call-stream-1",
                    toolArgs = "",
                ),
            ),
        )
        val secondCall = firstCall.copy(
            segments = firstCall.segments.orEmpty() +
                MessageSegment(
                    type = "tool",
                    toolCallId = "call-stream-2",
                    toolArgs = "",
                ),
        )

        assertSame(
            firstCall,
            resolveMessagePayloadForRender(stub, firstCall, stub, stub),
        )
        val latest = resolveMessagePayloadForRender(stub, secondCall, firstCall, firstCall)
        assertSame(secondCall, latest)
        assertEquals(
            listOf("call-stream-1", "call-stream-2"),
            latest.segments.orEmpty().map(MessageSegment::toolCallId),
        )
        assertTrue(latest.segments.orEmpty().all { it.toolName == null && it.toolArgs.isNullOrEmpty() })
    }

    @Test
    fun authoritativeTerminalPayloadWinsOverStaleHydration() {
        val terminal = ChatMessage(
            id = "terminal",
            text = "partial answer",
            participant = Participant.MODEL,
            status = MessageStatus.STOPPED,
            segments = listOf(MessageSegment(type = "thought", content = "terminal")),
        )
        val observed = terminal.copy(
            text = "older room payload",
            status = MessageStatus.SENDING,
            segments = null,
        )
        val cached = observed.copy(text = "older cached payload")

        assertSame(
            terminal,
            resolveMessagePayloadForRender(terminal, null, observed, cached),
        )
    }

    @Test
    fun historicalPayloadRetainsLazyHydrationPriority() {
        val stub = ChatMessage(id = "history", text = "", participant = Participant.MODEL)
        val cached = stub.copy(text = "cached")
        val observed = stub.copy(text = "observed")

        assertSame(observed, resolveMessagePayloadForRender(stub, null, observed, cached))
        assertSame(cached, resolveMessagePayloadForRender(stub, null, null, cached))
        assertSame(stub, resolveMessagePayloadForRender(stub, null, null, null))
    }

    @Test
    fun segmentDetailUsesRuntimeThenTerminalHandoffThenRoomAfterOffload() {
        val room = ChatMessage(
            id = "detail",
            participant = Participant.MODEL,
            text = "",
            status = MessageStatus.SUCCESS,
            segments = listOf(MessageSegment(type = "thought", content = "room")),
        )
        val terminalHandoff = room.copy(
            segments = listOf(MessageSegment(type = "thought", content = "terminal")),
        )
        val streaming = room.copy(
            status = MessageStatus.THINKING,
            segments = listOf(MessageSegment(type = "thought", content = "streaming")),
        )

        assertSame(
            streaming,
            resolveSegmentDetailMessage("detail", streaming, terminalHandoff, room),
        )
        assertSame(
            terminalHandoff,
            resolveSegmentDetailMessage("detail", null, terminalHandoff, room),
        )
        assertSame(
            room,
            resolveSegmentDetailMessage(
                messageId = "detail",
                streamingMessage = null,
                authoritativeMessage = room.copy(text = "topology stub", segments = null),
                roomMessage = room,
            ),
        )
        assertSame(
            room,
            resolveSegmentDetailMessage("detail", null, null, room),
        )
    }

    @Test
    fun groupedSegmentDetailSelectionTracksNewAuthoritativeSegments() {
        val initial = ChatMessage(
            id = "detail",
            participant = Participant.MODEL,
            text = "",
            segments = listOf(
                MessageSegment(type = "thought", content = "reasoning"),
                MessageSegment(type = "answer", content = "partial"),
                MessageSegment(type = "tool", toolName = "shell"),
            ),
        )
        val grown = initial.copy(
            segments = initial.segments.orEmpty() +
                MessageSegment(type = "transcription", content = "image text"),
        )

        assertEquals(
            listOf(0, 1),
            segmentDetailIndicesForSnapshot(initial, listOf(0), showSegmentListFirst = true),
        )
        assertEquals(
            listOf(0, 1, 2),
            segmentDetailIndicesForSnapshot(grown, listOf(0), showSegmentListFirst = true),
        )
        assertEquals(
            listOf(1),
            segmentDetailIndicesForSnapshot(grown, listOf(1), showSegmentListFirst = false),
        )
    }

    @Test
    fun coalescedTailStepIsBoundedAndMovesTowardTarget() {
        assertEquals(
            32f,
            coalescedScrollStep(
                errorPx = 500f,
                elapsedSeconds = 0.016f,
                timeConstantSeconds = 0.055f,
                maximumVelocityPxPerSecond = 2_000f,
                minimumStepPx = 2f,
            ),
            0.001f,
        )
        assertTrue(
            coalescedScrollStep(
                errorPx = -20f,
                elapsedSeconds = 0.016f,
                timeConstantSeconds = 0.055f,
                maximumVelocityPxPerSecond = 2_000f,
                minimumStepPx = 2f,
            ) < 0f,
        )
    }

    @Test
    fun sendEasingOnlyShapesStartupThenReturnsTheAdaptiveTailUnchanged() {
        val adaptiveStep = 120f
        val startupSpec = FeedbackScrollStartupSpec(
            durationMillis = 240L,
            easing = FastOutSlowInEasing,
        )
        val sendSpec = DefaultFeedbackScrollSpec.copy(startup = startupSpec)
        val initial = applyFeedbackScrollStartup(
            adaptiveStepPx = adaptiveStep,
            elapsedNanos = 0L,
            startup = sendSpec.startup,
        )
        val startup = applyFeedbackScrollStartup(
            adaptiveStepPx = adaptiveStep,
            elapsedNanos = 120_000_000L,
            startup = sendSpec.startup,
        )
        val adaptiveTail = applyFeedbackScrollStartup(
            adaptiveStepPx = adaptiveStep,
            elapsedNanos = 240_000_000L,
            startup = sendSpec.startup,
        )
        val bottomButtonStep = applyFeedbackScrollStartup(
            adaptiveStepPx = -adaptiveStep,
            elapsedNanos = 0L,
            startup = DefaultFeedbackScrollSpec.startup,
        )

        assertEquals(
            DefaultFeedbackScrollSpec,
            sendSpec.copy(startup = null),
        )
        assertEquals(0f, initial, 0.001f)
        assertTrue(startup in 0f..adaptiveStep)
        assertEquals(adaptiveStep, adaptiveTail, 0.001f)
        assertEquals(-adaptiveStep, bottomButtonStep, 0.001f)
    }

}
