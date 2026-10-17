package com.newoether.agora.ui.chat

import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.newoether.agora.model.ChatMessage
import com.newoether.agora.model.MessageGenerationBoundaryResolver
import com.newoether.agora.ui.motion.AgoraMotionPolicy
import kotlinx.coroutines.flow.filter
internal suspend fun ChatScrollCoordinator.animateToUserMessage(
    messages: List<ChatMessage>,
    targetMessageId: String? = null,
    easing: Easing = FastOutSlowInEasing,
    density: Density,
    motionPolicy: AgoraMotionPolicy,
): Boolean {
    if (messages.isEmpty() || viewportHeightPx == 0) return false
    val layoutTurns = buildMessageListTurns(messages)
    val targetIndex = resolveScrollTargetIndex(messages, targetMessageId)
    if (targetIndex == -1) return false
    if (!motionPolicy.allowProgrammaticScrollMotion) {
        listState.scrollToItem(targetIndex, 0)
        return true
    }

    val firstVisibleIndex = listState.firstVisibleItemIndex
    val visibleSizes = listState.layoutInfo.visibleItemsInfo.associate {
        it.index to it.size
    }
    val fallbackHeight = visibleSizes.values
        .takeIf { it.isNotEmpty() }
        ?.average()
        ?.toFloat()
        ?: with(density) { 72.dp.toPx() }
    fun heightAt(index: Int): Float {
        visibleSizes[index]?.let { return it.toFloat() }
        val turn = layoutTurns.getOrNull(index) ?: return fallbackHeight
        return estimateMessageListTurnHeightPx(turn, messageHeights, fallbackHeight)
    }

    val distance = if (targetIndex >= firstVisibleIndex) {
        var value = -listState.firstVisibleItemScrollOffset.toFloat()
        for (index in firstVisibleIndex until targetIndex) value += heightAt(index)
        value
    } else {
        var value = -listState.firstVisibleItemScrollOffset.toFloat()
        for (index in targetIndex until firstVisibleIndex) value -= heightAt(index)
        value
    }
    if (kotlin.math.abs(distance) > 2f) {
        listState.animateScrollBy(distance, tween(600, easing = easing))
    }
    return true
}

internal fun ChatScrollCoordinator.estimateRemainingAbsoluteBottomDistance(
    messages: List<ChatMessage>,
    density: Density,
    bottomBarHeight: Dp,
    shareSelectionBarSpace: Dp,
): Float? {
    val layout = listState.layoutInfo
    val lastVisible = layout.visibleItemsInfo.maxByOrNull { item -> item.index }
        ?: return null
    val layoutTurns = buildMessageListTurns(messages)
    val visibleSizes = layout.visibleItemsInfo.associate { item -> item.index to item.size }
    val fallbackHeight = visibleSizes.values
        .filter { size -> size > 1 }
        .takeIf { sizes -> sizes.isNotEmpty() }
        ?.average()
        ?.toFloat()
        ?: with(density) { 72.dp.toPx() }
    val lastUserMessageId = messages
        .lastOrNull(MessageGenerationBoundaryResolver::isRealUser)
        ?.id
    val tailMinimumHeightPx = if (lastUserMessageId == null || viewportHeightPx == 0) {
        0f
    } else {
        calculateTailMinHeightPx(
            viewportHeightPx = viewportHeightPx,
            targetTopPx = with(density) { 140.dp.roundToPx() },
            bottomObstructionPx = with(density) {
                (bottomBarHeight + shareSelectionBarSpace + 8.dp).roundToPx()
            },
        ).toFloat()
    }
    val sentinelHeightPx = with(density) { 1.dp.toPx() }

    fun estimatedItemSize(index: Int): Float {
        visibleSizes[index]?.let { size -> return size.toFloat() }
        val turn = layoutTurns.getOrNull(index) ?: return sentinelHeightPx
        val estimated = estimateMessageListTurnHeightPx(
            turn = turn,
            messageHeights = messageHeights,
            fallbackHeightPx = fallbackHeight,
        )
        return if (turn.key == lastUserMessageId) {
            maxOf(estimated, tailMinimumHeightPx)
        } else {
            estimated
        }
    }

    return estimateAbsoluteBottomDistancePx(
        lastVisibleIndex = lastVisible.index,
        lastVisibleEndOffsetPx = lastVisible.offset + lastVisible.size,
        viewportEndOffsetPx = layout.viewportEndOffset,
        afterContentPaddingPx = layout.afterContentPadding,
        totalItemsCount = layout.totalItemsCount,
        estimatedItemSizePx = ::estimatedItemSize,
    )
}
