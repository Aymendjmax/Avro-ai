package com.newoether.agora.ui.chat

import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.isActive
import kotlin.math.abs

/** Effects use MessageList's existing state and scroll authority, with unchanged keys and order. */
@Composable
internal fun MessageListTailInteractionEffects(
    state: LazyListState,
    conversationId: String?,
    isSwitching: Boolean,
    streamingTailFollowModeState: State<StreamingTailFollowMode>,
    userDragInProgressState: MutableState<Boolean>,
    streamingTailController: StreamingTailController,
    cancelMutationAnchoring: () -> Unit,
    setStreamingTailFollowMode: (StreamingTailFollowMode) -> Unit,
) {
    val streamingTailFollowMode by streamingTailFollowModeState
    var userDragInProgress by userDragInProgressState
    SideEffect {
        streamingTailController.isAttached =
            streamingTailFollowMode == StreamingTailFollowMode.ATTACHED ||
                streamingTailFollowMode == StreamingTailFollowMode.SETTLING
    }
    LaunchedEffect(isSwitching) {
        if (isSwitching) cancelMutationAnchoring()
    }
    LaunchedEffect(state, conversationId) {
        state.interactionSource.interactions.collect { interaction ->
            when (interaction) {
                is DragInteraction.Start -> {
                    cancelMutationAnchoring()
                    userDragInProgress = true
                    // A real gesture is authoritative. Clear the externally-observed flag before
                    // changing mode so the scroll-to-bottom button can react in the same frame.
                    streamingTailController.isAutoFollowing = false
                    setStreamingTailFollowMode(
                        reduceStreamingTailFollow(
                            streamingTailFollowMode,
                            StreamingTailFollowEvent.UserDragStarted,
                        ),
                    )
                }

                is DragInteraction.Stop,
                is DragInteraction.Cancel -> {
                    userDragInProgress = false
                }
            }
        }
    }
    DisposableEffect(state, conversationId) {
        onDispose { cancelMutationAnchoring() }
    }

}

@Composable
internal fun MessageListTailFollowEffects(
    state: LazyListState,
    conversationId: String?,
    isLoading: Boolean,
    streamingAutoFollowEnabled: Boolean,
    streamingAutoFollowPaused: Boolean,
    lastUserMessageId: String?,
    streamingTailWithinAttachThreshold: Boolean,
    streamingTailFollowModeState: State<StreamingTailFollowMode>,
    userDragInProgressState: State<Boolean>,
    streamingTailController: StreamingTailController,
    cancelMutationAnchoring: () -> Unit,
    setStreamingTailFollowMode: (StreamingTailFollowMode) -> Unit,
) {
    val streamingTailFollowMode by streamingTailFollowModeState
    val userDragInProgress by userDragInProgressState
    val density = LocalDensity.current
    val tailTolerancePx = with(density) { 2.dp.toPx() }
    val latestIsLoading by rememberUpdatedState(isLoading)
    val latestAutoFollowEnabled by rememberUpdatedState(streamingAutoFollowEnabled)
    LaunchedEffect(
        state,
        conversationId,
        isLoading,
        streamingAutoFollowEnabled,
        streamingAutoFollowPaused,
        lastUserMessageId,
    ) {
        if (!isLoading || streamingAutoFollowPaused || !streamingAutoFollowEnabled) {
            setStreamingTailFollowMode(
                reduceStreamingTailGenerationAvailability(
                    current = streamingTailFollowMode,
                    active = isLoading,
                    autoFollowEnabled = streamingAutoFollowEnabled,
                    autoFollowPaused = streamingAutoFollowPaused,
                ),
            )
            return@LaunchedEffect
        }
        val nextMode = reduceStreamingTailGenerationAvailability(
            current = streamingTailFollowMode,
            active = isLoading,
            autoFollowEnabled = streamingAutoFollowEnabled,
            autoFollowPaused = streamingAutoFollowPaused,
        )
        if (nextMode == StreamingTailFollowMode.ATTACHED) {
            cancelMutationAnchoring()
        }
        setStreamingTailFollowMode(nextMode)
    }

    LaunchedEffect(
        state,
        conversationId,
        isLoading,
        streamingAutoFollowEnabled,
        streamingAutoFollowPaused,
        streamingTailWithinAttachThreshold,
    ) {
        snapshotFlow {
            state.isScrollInProgress to streamingTailFollowMode
        }
            .distinctUntilChanged()
            .collect { (scrollInProgress, _) ->
                if (
                    !isLoading ||
                    !streamingAutoFollowEnabled ||
                    streamingAutoFollowPaused
                ) {
                    return@collect
                }
                val nextMode = reduceStreamingTailFollow(
                    streamingTailFollowMode,
                    StreamingTailFollowEvent.ViewportProximityChanged(
                        withinAttachThreshold = streamingTailWithinAttachThreshold,
                        scrollInProgress = scrollInProgress,
                    ),
                )
                if (
                    nextMode == StreamingTailFollowMode.ATTACHED &&
                    streamingTailFollowMode != StreamingTailFollowMode.ATTACHED
                ) {
                    cancelMutationAnchoring()
                }
                setStreamingTailFollowMode(nextMode)
            }
    }

    // One frame-driven actor owns attached scrolling. It reads the newest cumulative geometry on
    // every display frame, coalesces all token/layout deltas into one critically damped correction,
    // and is cancelled immediately by a real drag or any competing transition.
    LaunchedEffect(
        state,
        conversationId,
        isLoading,
        streamingAutoFollowEnabled,
        streamingTailFollowMode,
    ) {
        val followingActiveGeneration =
            isLoading &&
                streamingAutoFollowEnabled &&
                streamingTailFollowMode == StreamingTailFollowMode.ATTACHED
        val settlingCompletedGeneration =
            !isLoading &&
                streamingTailFollowMode == StreamingTailFollowMode.SETTLING
        if (!followingActiveGeneration && !settlingCompletedGeneration) {
            streamingTailController.isAutoFollowing = false
            return@LaunchedEffect
        }
        cancelMutationAnchoring()
        streamingTailController.isAutoFollowing = true
        val minimumStepPx = with(density) { 2.dp.toPx() }
        var previousFrameNanos = withFrameNanos { frameTimeNanos -> frameTimeNanos }
        val settlingStartNanos = previousFrameNanos
        var stableFrames = 0
        try {
            // Attachment is a layout correction, not a user-visible scroll gesture. Raw one-frame
            // deltas deliberately avoid LazyList's MutatorMutex and isScrollInProgress, so an
            // attached list never cancels taps or competes with the horizontal drawer recognizer.
            // A real vertical drag still emits DragInteraction.Start above and detaches first.
            while (
                currentCoroutineContext().isActive &&
                (
                    (
                        streamingTailFollowMode == StreamingTailFollowMode.ATTACHED &&
                            latestIsLoading &&
                            latestAutoFollowEnabled
                    ) ||
                        (
                            streamingTailFollowMode == StreamingTailFollowMode.SETTLING &&
                                !latestIsLoading
                        )
                ) &&
                !userDragInProgress
            ) {
                val frameNanos = withFrameNanos { frameTimeNanos -> frameTimeNanos }
                val elapsedSeconds =
                    ((frameNanos - previousFrameNanos).coerceAtLeast(1L) / 1_000_000_000f)
                        .coerceAtMost(0.05f)
                previousFrameNanos = frameNanos
                val absoluteBottom = absoluteBottomLayoutSnapshot(
                    layoutInfo = state.layoutInfo,
                    canScrollForward = state.canScrollForward,
                )
                // Attachment has exactly one authority: the page's physical end sentinel.
                // The visual tail dot is deliberately absent from this calculation.
                val error = absoluteBottom.remainingDistancePx
                    ?: if (state.canScrollForward) {
                        absoluteBottom.viewportSizePx * 0.5f
                    } else {
                        0f
                    }
                if (error > 0.5f) {
                    val step = coalescedScrollStep(
                        errorPx = error,
                        elapsedSeconds = elapsedSeconds,
                        timeConstantSeconds = 0.055f,
                        maximumVelocityPxPerSecond = 2_800f,
                        minimumStepPx = minimumStepPx,
                    )
                    if (abs(step) > 0.05f) {
                        val modeStillOwnsAttachment =
                            streamingTailFollowMode == StreamingTailFollowMode.ATTACHED ||
                                streamingTailFollowMode == StreamingTailFollowMode.SETTLING
                        if (!userDragInProgress && modeStillOwnsAttachment) {
                            state.dispatchRawDelta(step)
                        }
                    }
                }

                if (streamingTailFollowMode == StreamingTailFollowMode.SETTLING) {
                    stableFrames = if (error <= tailTolerancePx) stableFrames + 1 else 0
                    val settlingElapsedMs =
                        (frameNanos - settlingStartNanos).coerceAtLeast(0L) / 1_000_000L
                    val settledAfterFinalAnimations =
                        settlingElapsedMs >= 700L && stableFrames >= 8
                    val settlingTimedOut = settlingElapsedMs >= 1_600L
                    if (settledAfterFinalAnimations || settlingTimedOut) {
                        setStreamingTailFollowMode(
                            reduceStreamingTailFollow(
                                streamingTailFollowMode,
                                StreamingTailFollowEvent.SettlingFinished,
                            ),
                        )
                    }
                }
            }
        } finally {
            streamingTailController.isAutoFollowing = false
        }
    }

}
