package com.newoether.agora.ui.chat

import com.newoether.agora.model.ChatMessage
import com.newoether.agora.model.MessageSegment
import com.newoether.agora.model.MessageStatus
import com.newoether.agora.model.Participant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class MessageListLayoutTest {
    @Test
    fun prependingPagesNeverReparentsOrRecreatesAnExistingLazyItem() {
        val cache = MessageListTurnCache()
        val first = message("visible-answer", Participant.MODEL).copy(displayPageId = "page-current")
        val tailUser = message("tail-user", Participant.USER).copy(displayPageId = "page-current")
        var messages = listOf(first, tailUser)
        val initial = cache.update(messages)
        repeat(200) { index ->
            val page = "older-$index"
            messages = listOf(
                message("$page-user", Participant.USER).copy(displayPageId = page),
                message("$page-answer", Participant.MODEL).copy(displayPageId = page),
            ) + messages
            val turns = cache.update(messages)
            assertSame(initial.first(), turns.first { it.key == first.id })
            assertSame(initial.last(), turns.first { it.key == tailUser.id })
            assertEquals(listOf(first), turns.first { it.key == first.id }.messages)
        }
    }

    @Test
    fun lifecycleRegistryMarksOnlyActuallyComposedMessages() {
        val registry = MessageLifecycleAppearanceRegistry()

        assertFalse(registry.isKnown("projected-but-not-composed"))
        registry.markKnown("composed")

        assertTrue(registry.isKnown("composed"))
        assertFalse(registry.isKnown("projected-but-not-composed"))
    }

    @Test
    fun appendingUserKeepsPreviousAssistantInTheSameTurn() {
        val user1 = message("user-1", Participant.USER)
        val assistant1 = message("assistant-1", Participant.MODEL)
        val user2 = message("user-2", Participant.USER)

        val beforeSend = buildMessageListTurns(listOf(user1, assistant1))
        val afterSend = buildMessageListTurns(listOf(user1, assistant1, user2))

        assertEquals(beforeSend.single(), afterSend.first())
        assertEquals("user-1", afterSend.first().key)
        assertEquals(listOf("user-1", "assistant-1"), afterSend.first().messages.map { it.id })
        assertEquals(listOf("user-2"), afterSend.last().messages.map { it.id })
    }

    @Test
    fun turnCache_reusesHistoryAndReplacesOnlyTheStreamingTurn() {
        val cache = MessageListTurnCache()
        val user1 = message("user-1", Participant.USER)
        val assistant1 = message("assistant-1", Participant.MODEL)
        val user2 = message("user-2", Participant.USER)
        val firstStream = message("assistant-2", Participant.MODEL).copy(text = "a")
        val before = cache.update(listOf(user1, assistant1, user2, firstStream))

        val nextStream = firstStream.copy(text = "ab")
        val after = cache.update(listOf(user1, assistant1, user2, nextStream))

        assertSame(before.first(), after.first())
        assertNotSame(before.last(), after.last())
        assertEquals("ab", after.last().messages.last().text)
    }

    @Test
    fun everyMessageInATurnMapsToTheSameLazyItemIndex() {
        val turns = buildMessageListTurns(
            listOf(
                message("user-1", Participant.USER),
                message("assistant-1", Participant.MODEL),
                message("error-1", Participant.ERROR),
                message("user-2", Participant.USER),
                message("assistant-2", Participant.MODEL),
            ),
        )

        assertEquals(0, messageListTurnIndex(turns, "user-1"))
        assertEquals(0, messageListTurnIndex(turns, "assistant-1"))
        assertEquals(0, messageListTurnIndex(turns, "error-1"))
        assertEquals(1, messageListTurnIndex(turns, "user-2"))
        assertEquals(1, messageListTurnIndex(turns, "assistant-2"))
        assertEquals(-1, messageListTurnIndex(turns, "missing"))
    }

    @Test
    fun turnHeightEstimateSumsChildrenForForcedAnimatedScroll() {
        val turn = buildMessageListTurns(
            listOf(
                message("user-1", Participant.USER),
                message("assistant-1", Participant.MODEL),
                message("error-1", Participant.ERROR),
            ),
        ).single()

        assertEquals(
            372f,
            estimateMessageListTurnHeightPx(
                turn = turn,
                messageHeights = mapOf("user-1" to 120, "assistant-1" to 180),
                fallbackHeightPx = 72f,
            ),
            0f,
        )
    }

    @Test
    fun leadingNonUserMessagesRemainStableSingletonItems() {
        val turns = buildMessageListTurns(
            listOf(
                message("error-1", Participant.ERROR),
                message("assistant-0", Participant.MODEL),
                message("user-1", Participant.USER),
                message("assistant-1", Participant.MODEL),
            ),
        )

        assertEquals(
            listOf(
                listOf("error-1"),
                listOf("assistant-0"),
                listOf("user-1", "assistant-1"),
            ),
            turns.map { turn -> turn.messages.map { it.id } },
        )
    }

    @Test
    fun shortTailUsesTheAvailableViewportAsItsMinimumHeight() {
        val viewport = 1_000
        val top = 140
        val bottom = 180
        val content = 260

        val minimum = calculateTailMinHeightPx(viewport, top, bottom)
        val layoutHeight = calculateTailLayoutHeightPx(minimum, content)

        assertEquals(680, minimum)
        assertEquals(680, layoutHeight)
        assertEquals(viewport - top, layoutHeight + bottom)
    }

    @Test
    fun postAnchorGrowthAndShrinkKeepAllBlankCapacityAtThePhysicalEnd() {
        val turns = buildMessageListTurns(
            listOf(
                message("user", Participant.USER),
                message("assistant", Participant.MODEL),
                message("compact_boundary", Participant.MODEL),
                message("later-assistant", Participant.MODEL),
            ),
        )
        val baseMinimum = calculateTailMinHeightPx(1_000, 140, 180)

        fun tailRegionHeight(compactHeight: Int, assistantHeight: Int): Int {
            val holderMinimum = calculateTailHolderMinHeightPx(
                turns = turns,
                semanticAnchorKey = messageListTailAnchorKey(turns),
                baseMinimumHeightPx = baseMinimum,
                messageHeights = mapOf("compact_boundary" to compactHeight),
            )
            return compactHeight + calculateTailLayoutHeightPx(holderMinimum, assistantHeight)
        }

        assertEquals(baseMinimum, tailRegionHeight(compactHeight = 80, assistantHeight = 220))
        assertEquals(baseMinimum, tailRegionHeight(compactHeight = 240, assistantHeight = 220))
        assertEquals(920, tailRegionHeight(compactHeight = 240, assistantHeight = 680))
    }

    @Test
    fun bottomBarGrowthReducesTheTailMinimumDirectly() {
        val beforeBottom = 120
        val afterBottom = 260
        val beforeMinimum = calculateTailMinHeightPx(1_000, 140, beforeBottom)
        val afterMinimum = calculateTailMinHeightPx(1_000, 140, afterBottom)

        assertEquals(140, beforeMinimum - afterMinimum)
    }

    @Test
    fun longTailGrowsNaturallyPastTheMinimum() {
        val minimum = calculateTailMinHeightPx(1_000, 140, 180)

        assertEquals(
            2_000,
            calculateTailLayoutHeightPx(minimum, contentHeightPx = 2_000),
        )
    }

    @Test
    fun embeddedTailAnchorLeavesBlankSpaceAfterCurrentContent() {
        val viewport = 1_000
        val top = 140
        val composer = 180

        val messageTail = calculateTailMinHeightPx(
            viewportHeightPx = viewport,
            targetTopPx = top,
            bottomObstructionPx = composer,
        )

        assertEquals(viewport - top, messageTail + composer)
    }

    @Test
    fun absoluteBottomStateRetargetsWhenStreamingExtentChanges() {
        var phase = reduceAbsoluteBottomScroll(
            AbsoluteBottomScrollPhase.IDLE,
            AbsoluteBottomScrollEvent.Requested,
        )
        assertEquals(AbsoluteBottomScrollPhase.SEEKING, phase)

        phase = reduceAbsoluteBottomScroll(
            phase,
            AbsoluteBottomScrollEvent.TargetAvailable,
        )
        assertEquals(AbsoluteBottomScrollPhase.FOLLOWING, phase)

        phase = reduceAbsoluteBottomScroll(
            phase,
            AbsoluteBottomScrollEvent.BottomReached,
        )
        assertEquals(AbsoluteBottomScrollPhase.SETTLING, phase)

        phase = reduceAbsoluteBottomScroll(
            phase,
            AbsoluteBottomScrollEvent.ExtentChanged,
        )
        assertEquals(AbsoluteBottomScrollPhase.FOLLOWING, phase)

        phase = reduceAbsoluteBottomScroll(
            phase,
            AbsoluteBottomScrollEvent.BottomReached,
        )
        phase = reduceAbsoluteBottomScroll(
            phase,
            AbsoluteBottomScrollEvent.Finished,
        )
        assertEquals(AbsoluteBottomScrollPhase.IDLE, phase)
    }

    @Test
    fun userCancellationReleasesEveryAbsoluteBottomPhase() {
        AbsoluteBottomScrollPhase.entries
            .filter { phase -> phase.isActive }
            .forEach { phase ->
                assertEquals(
                    AbsoluteBottomScrollPhase.IDLE,
                    reduceAbsoluteBottomScroll(
                        phase,
                        AbsoluteBottomScrollEvent.Cancelled,
                    ),
                )
        }
    }

    @Test
    fun imeRiseAnchorsOnlyAPreviouslyBottomAlignedViewport() {
        var state = ImeBottomAnchorState(
            observedInsetPx = 0,
            bottomEligibleBeforeInsetChange = false,
        )
        state = reduceImeBottomAnchor(
            state,
            ImeBottomAnchorEvent.InsetsObserved(
                insetPx = 0,
                bottomEligibleNow = true,
            ),
        )
        state = reduceImeBottomAnchor(
            state,
            ImeBottomAnchorEvent.InsetsObserved(
                insetPx = 120,
                bottomEligibleNow = false,
            ),
        )
        assertTrue(state.active)

        state = reduceImeBottomAnchor(
            state,
            ImeBottomAnchorEvent.CorrectionSettled,
        )
        assertFalse(state.active)
        state = reduceImeBottomAnchor(
            state,
            ImeBottomAnchorEvent.InsetsObserved(
                insetPx = 240,
                bottomEligibleNow = false,
            ),
        )
        assertTrue(state.active)

        val detached = reduceImeBottomAnchor(
            ImeBottomAnchorState(
                observedInsetPx = 0,
                bottomEligibleBeforeInsetChange = false,
            ),
            ImeBottomAnchorEvent.InsetsObserved(
                insetPx = 240,
                bottomEligibleNow = false,
            ),
        )
        assertFalse(detached.active)
    }

    @Test
    fun imeRiseCannotAnchorWithoutComposerFocusAuthorization() {
        var state = ImeBottomAnchorState(
            observedInsetPx = 0,
            bottomEligibleBeforeInsetChange = true,
        )
        state = reduceImeBottomAnchor(
            state,
            ImeBottomAnchorEvent.InsetsObserved(
                insetPx = 120,
                bottomEligibleNow = true,
                anchorAllowed = false,
            ),
        )

        assertEquals(120, state.observedInsetPx)
        assertFalse(state.bottomEligibleBeforeInsetChange)
        assertFalse(state.active)

        state = reduceImeBottomAnchor(
            state.copy(active = true),
            ImeBottomAnchorEvent.InsetsObserved(
                insetPx = 160,
                bottomEligibleNow = true,
                anchorAllowed = false,
            ),
        )
        assertFalse(state.active)
    }

    @Test
    fun userDragSuppressesImeReattachmentUntilTheInsetFalls() {
        var state = ImeBottomAnchorState(
            observedInsetPx = 80,
            bottomEligibleBeforeInsetChange = true,
            active = true,
        )
        state = reduceImeBottomAnchor(
            state,
            ImeBottomAnchorEvent.UserDragStarted,
        )
        state = reduceImeBottomAnchor(
            state,
            ImeBottomAnchorEvent.InsetsObserved(
                insetPx = 160,
                bottomEligibleNow = true,
            ),
        )
        assertFalse(state.active)
        assertTrue(state.suppressedUntilInsetFalls)

        state = reduceImeBottomAnchor(
            state,
            ImeBottomAnchorEvent.InsetsObserved(
                insetPx = 0,
                bottomEligibleNow = false,
            ),
        )
        assertFalse(state.active)
        assertFalse(state.suppressedUntilInsetFalls)
    }

    @Test
    fun userDragWhileImeIsClosedCanRearmAtTheBottomThreshold() {
        var state = ImeBottomAnchorState(
            observedInsetPx = 0,
            bottomEligibleBeforeInsetChange = true,
        )

        state = reduceImeBottomAnchor(
            state,
            ImeBottomAnchorEvent.UserDragStarted,
        )
        assertFalse(state.active)
        assertFalse(state.suppressedUntilInsetFalls)

        // Once the drag settles inside the threshold, the normal proximity observation arms
        // anchoring without requiring an explicit scroll-to-bottom button click.
        state = reduceImeBottomAnchor(
            state,
            ImeBottomAnchorEvent.InsetsObserved(
                insetPx = 0,
                bottomEligibleNow = true,
            ),
        )
        state = reduceImeBottomAnchor(
            state,
            ImeBottomAnchorEvent.InsetsObserved(
                insetPx = 120,
                bottomEligibleNow = false,
            ),
        )

        assertTrue(state.active)
    }

    @Test
    fun absoluteBottomDistanceIncludesAfterContentPadding() {
        val snapshot = AbsoluteBottomLayoutSnapshot(
            totalItemsCount = 4,
            canScrollForward = true,
            viewportStartOffsetPx = -8,
            viewportEndOffsetPx = 1_000,
            afterContentPaddingPx = 24,
            sentinelOffsetPx = 1_040,
            sentinelSizePx = 1,
        )

        assertEquals(65f, snapshot.remainingDistancePx ?: -1f, 0f)
    }

    @Test
    fun absoluteBottomThresholdUsesTheFinalPreSentinelGap() {
        val snapshot = AbsoluteBottomLayoutSnapshot(
            totalItemsCount = 4,
            canScrollForward = true,
            viewportStartOffsetPx = 0,
            viewportEndOffsetPx = 1_000,
            afterContentPaddingPx = 24,
            sentinelOffsetPx = null,
            sentinelSizePx = null,
            lastVisibleIndex = 2,
            lastVisibleEndOffsetPx = 1_030,
        )
        val remaining = snapshot.estimatedRemainingDistancePx(3f)

        assertEquals(57f, remaining ?: -1f, 0f)
        assertTrue(
            isWithinAbsoluteBottomAttachThreshold(
                snapshot = snapshot,
                remainingDistancePx = remaining,
                thresholdPx = 64f,
            ),
        )
    }

    @Test
    fun coarseAbsoluteBottomEstimateTargetsThePhysicalEnd() {
        assertEquals(
            421f,
            estimateAbsoluteBottomDistancePx(
                lastVisibleIndex = 2,
                lastVisibleEndOffsetPx = 900,
                viewportEndOffsetPx = 1_000,
                afterContentPaddingPx = 20,
                totalItemsCount = 6,
                estimatedItemSizePx = { index ->
                    when (index) {
                        3 -> 200f
                        4 -> 300f
                        else -> 1f
                    }
                },
            ),
            0f,
        )
    }

    @Test
    fun scrollToBottomButtonUsesRealScrollableExtentAndLocksDuringSeek() {
        assertTrue(
            shouldShowAbsoluteBottomButton(
                isNewChatMode = false,
                isSwitching = false,
                conversationContentReady = true,
                shareSelectionActive = false,
                hasItems = true,
                canScrollForward = true,
                isNearBottom = false,
                isStreamingAutoFollowing = false,
                scrollPhase = AbsoluteBottomScrollPhase.IDLE,
            ),
        )
        assertFalse(
            shouldShowAbsoluteBottomButton(
                isNewChatMode = false,
                isSwitching = false,
                conversationContentReady = true,
                shareSelectionActive = false,
                hasItems = true,
                canScrollForward = true,
                isNearBottom = false,
                isStreamingAutoFollowing = false,
                scrollPhase = AbsoluteBottomScrollPhase.SEEKING,
            ),
        )
        assertFalse(
            shouldShowAbsoluteBottomButton(
                isNewChatMode = false,
                isSwitching = false,
                conversationContentReady = true,
                shareSelectionActive = false,
                hasItems = true,
                canScrollForward = false,
                isNearBottom = true,
                isStreamingAutoFollowing = false,
                scrollPhase = AbsoluteBottomScrollPhase.IDLE,
            ),
        )
        assertFalse(
            shouldShowAbsoluteBottomButton(
                isNewChatMode = false,
                isSwitching = false,
                conversationContentReady = true,
                shareSelectionActive = false,
                hasItems = true,
                canScrollForward = true,
                isNearBottom = false,
                isStreamingAutoFollowing = true,
                scrollPhase = AbsoluteBottomScrollPhase.IDLE,
            ),
        )
        assertFalse(
            shouldShowAbsoluteBottomButton(
                isNewChatMode = false,
                isSwitching = true,
                conversationContentReady = true,
                shareSelectionActive = false,
                hasItems = true,
                canScrollForward = true,
                isNearBottom = false,
                isStreamingAutoFollowing = false,
                scrollPhase = AbsoluteBottomScrollPhase.IDLE,
            ),
        )
        assertFalse(
            shouldShowAbsoluteBottomButton(
                isNewChatMode = false,
                isSwitching = false,
                conversationContentReady = false,
                shareSelectionActive = false,
                hasItems = true,
                canScrollForward = true,
                isNearBottom = false,
                isStreamingAutoFollowing = false,
                scrollPhase = AbsoluteBottomScrollPhase.IDLE,
            ),
        )
        assertFalse(
            shouldShowAbsoluteBottomButton(
                isNewChatMode = false,
                isSwitching = false,
                conversationContentReady = true,
                shareSelectionActive = false,
                hasItems = true,
                canScrollForward = true,
                isNearBottom = false,
                isStreamingAutoFollowing = false,
                scrollPhase = AbsoluteBottomScrollPhase.IDLE,
                competingProgrammaticScrollActive = true,
            ),
        )
    }

    @Test
    fun scrollToBottomProximityUsesHysteresis() {
        var nearBottom = reduceAbsoluteBottomProximity(
            wasNearBottom = false,
            canScrollForward = true,
            remainingDistancePx = 63f,
            hideThresholdPx = 64f,
            showThresholdPx = 96f,
        )
        assertTrue(nearBottom)

        nearBottom = reduceAbsoluteBottomProximity(
            wasNearBottom = nearBottom,
            canScrollForward = true,
            remainingDistancePx = 80f,
            hideThresholdPx = 64f,
            showThresholdPx = 96f,
        )
        assertTrue(nearBottom)

        nearBottom = reduceAbsoluteBottomProximity(
            wasNearBottom = nearBottom,
            canScrollForward = true,
            remainingDistancePx = 97f,
            hideThresholdPx = 64f,
            showThresholdPx = 96f,
        )
        assertFalse(nearBottom)
    }

    @Test
    fun scrollStateMachineOnlyCorrectsStableVisibleLayouts() {
        assertEquals(
            MessageListLayoutMode.STABLE,
            messageListLayoutMode(isSwitching = false, isScrollInProgress = false),
        )
        assertEquals(
            MessageListLayoutMode.ACTIVE_SCROLL,
            messageListLayoutMode(isSwitching = false, isScrollInProgress = true),
        )
        assertEquals(
            MessageListLayoutMode.COVERED_TRANSITION,
            messageListLayoutMode(isSwitching = true, isScrollInProgress = false),
        )
    }

    @Test
    fun reversingMutationKeepsTheOriginalPreChangeAnchor() {
        val lock = MessageListMutationAnchorLock()
        val original = MessageListViewportAnchor("message-a", 37)

        assertEquals(original, lock.begin("thinking-card", original))
        assertEquals(original, lock.begin(
            "thinking-card",
            MessageListViewportAnchor("already-shifted", 91),
        ))

        assertEquals(1, lock.activeMutationCount)
        assertTrue(lock.isActive("thinking-card"))
        assertFalse(lock.isActive("other-card"))
        assertEquals(original, lock.anchor)
        assertEquals(original, lock.finish("thinking-card"))
        assertFalse(lock.isActive("thinking-card"))
        assertNull(lock.anchor)
    }

    @Test
    fun overlappingMutationsReleaseOnlyAfterTheLastAnimationSettles() {
        val lock = MessageListMutationAnchorLock()
        val original = MessageListViewportAnchor("message-a", 12)

        lock.begin("card-a", original)
        lock.begin("card-b", MessageListViewportAnchor("message-b", 99))

        assertNull(lock.finish("card-a"))
        assertEquals(original, lock.anchor)
        assertEquals(original, lock.finish("card-b"))
    }

    @Test
    fun userScrollCancelsPendingMutationCorrection() {
        val lock = MessageListMutationAnchorLock()
        lock.begin(
            "thinking-card",
            MessageListViewportAnchor("message-a", 12),
        )

        lock.cancel()

        assertEquals(0, lock.activeMutationCount)
        assertNull(lock.anchor)
        assertNull(lock.finish("thinking-card"))
    }

    @Test
    fun appendOnlyTextCanBeCoalescedDuringActiveScroll() {
        val before = message("assistant", Participant.MODEL).copy(
            status = MessageStatus.SENDING,
            text = "a",
            segments = listOf(MessageSegment(type = "answer", content = "a")),
        )
        val after = before.copy(
            text = "append-only",
            segments = listOf(MessageSegment(type = "answer", content = "append-only")),
        )

        assertEquals(
            true,
            sameStreamingRenderStructure(listOf(before), listOf(after)),
        )
    }

    @Test
    fun newToolSegmentCannotBeDeferredDuringActiveScroll() {
        val before = message("assistant", Participant.MODEL).copy(
            status = MessageStatus.THINKING,
            segments = listOf(MessageSegment(type = "thought", content = "reasoning")),
        )
        val after = before.copy(
            status = MessageStatus.TOOL_CALLING,
            segments = checkNotNull(before.segments) + MessageSegment(
                type = "tool",
                toolName = "arbitrary_tool",
                toolCallId = "call",
            ),
        )

        assertEquals(
            false,
            sameStreamingRenderStructure(listOf(before), listOf(after)),
        )
    }

    @Test
    fun terminalStateCannotBeDeferredDuringActiveScroll() {
        val before = message("assistant", Participant.MODEL).copy(
            status = MessageStatus.SENDING,
            text = "complete",
        )

        assertEquals(
            false,
            sameStreamingRenderStructure(
                listOf(before),
                listOf(before.copy(status = MessageStatus.SUCCESS)),
            ),
        )
    }

    private fun message(id: String, participant: Participant) = ChatMessage(
        id = id,
        text = id,
        participant = participant,
    )
}
