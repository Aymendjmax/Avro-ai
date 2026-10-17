package com.newoether.agora.ui.chat

import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
@Composable
internal fun rememberChatScrollCoordinator(
    currentConversationId: String?,
    imeBottomPx: Int,
): ChatScrollCoordinator {
    val listState = rememberLazyListState()
    val absoluteBottomScrollPhaseState = remember(currentConversationId) {
        mutableStateOf(AbsoluteBottomScrollPhase.IDLE)
    }
    val absoluteBottomRequestTokenState = remember(currentConversationId) {
        mutableLongStateOf(0L)
    }
    val absoluteBottomRequestFeedbackSpecState = remember(currentConversationId) {
        mutableStateOf(DefaultFeedbackScrollSpec)
    }
    val isNearAbsoluteBottomState = remember(currentConversationId) { mutableStateOf(true) }
    val isWithinAbsoluteBottomAttachThresholdState = remember(currentConversationId) {
        mutableStateOf(false)
    }
    val composerInputFocusedState = remember { mutableStateOf(false) }
    val imeBottomAnchorStateHolder = remember(currentConversationId) {
        mutableStateOf(
            ImeBottomAnchorState(
                observedInsetPx = imeBottomPx,
                bottomEligibleBeforeInsetChange = false,
            )
        )
    }
    val viewportHeightState = remember { mutableIntStateOf(0) }
    val messageHeights = remember(currentConversationId) { mutableStateMapOf<String, Int>() }
    val hydratedMessageIds = remember(currentConversationId) { mutableStateMapOf<String, Unit>() }
    val messageLifecycleAppearanceRegistry = remember { MessageLifecycleAppearanceRegistry() }
    val streamingTailController = rememberStreamingTailController(currentConversationId)

    return remember(
        listState,
        absoluteBottomScrollPhaseState,
        absoluteBottomRequestTokenState,
        absoluteBottomRequestFeedbackSpecState,
        isNearAbsoluteBottomState,
        isWithinAbsoluteBottomAttachThresholdState,
        composerInputFocusedState,
        imeBottomAnchorStateHolder,
        viewportHeightState,
        messageHeights,
        hydratedMessageIds,
        messageLifecycleAppearanceRegistry,
        streamingTailController,
    ) {
        ChatScrollCoordinator(
            listState = listState,
            absoluteBottomScrollPhaseState = absoluteBottomScrollPhaseState,
            absoluteBottomRequestTokenState = absoluteBottomRequestTokenState,
            absoluteBottomRequestFeedbackSpecState = absoluteBottomRequestFeedbackSpecState,
            isNearAbsoluteBottomState = isNearAbsoluteBottomState,
            isWithinAbsoluteBottomAttachThresholdState =
                isWithinAbsoluteBottomAttachThresholdState,
            composerInputFocusedState = composerInputFocusedState,
            imeBottomAnchorStateHolder = imeBottomAnchorStateHolder,
            viewportHeightState = viewportHeightState,
            messageHeights = messageHeights,
            hydrationRegistry = ConversationHydrationRegistry(
                conversationId = currentConversationId,
                hydratedMessageIds = hydratedMessageIds,
            ),
            messageLifecycleAppearanceRegistry = messageLifecycleAppearanceRegistry,
            streamingTailController = streamingTailController,
        )
    }
}
