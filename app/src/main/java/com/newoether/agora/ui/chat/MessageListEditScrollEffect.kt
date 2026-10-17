package com.newoether.agora.ui.chat

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

/** The existing edit seek stays keyed to the same conversation, row, turns, and motion policy. */
@Composable
internal fun MessageListEditScrollEffect(
    conversationId: String?,
    editingMessageIdState: MutableState<String?>,
    turns: List<MessageListTurn>,
    allowProgrammaticScrollMotion: Boolean,
    state: LazyListState,
    messageHeights: SnapshotStateMap<String, Int>,
    cancelMutationAnchoring: () -> Unit,
) {
    var editingMessageId by editingMessageIdState
    val density = LocalDensity.current
    LaunchedEffect(
        conversationId,
        editingMessageId,
        turns,
        allowProgrammaticScrollMotion,
    ) {
        val messageId = editingMessageId ?: return@LaunchedEffect
        val turnIndex = messageListTurnIndex(turns, messageId)
        if (turnIndex < 0) {
            editingMessageId = null
            return@LaunchedEffect
        }

        withFrameNanos { }
        cancelMutationAnchoring()
        val topInsetPx = with(density) { 140.dp.toPx() }
        if (!allowProgrammaticScrollMotion) {
            state.scrollToItem(
                index = turnIndex,
                scrollOffset = -topInsetPx.roundToInt(),
            )
            return@LaunchedEffect
        }

        val fallbackHeightPx = with(density) { 160.dp.toPx() }
        val estimatedTurnHeights = FloatArray(turns.size) { index ->
            estimateMessageListTurnHeightPx(
                turn = turns[index],
                messageHeights = messageHeights,
                fallbackHeightPx = fallbackHeightPx,
            )
        }
        val heightPrefix = FloatArray(turns.size + 1)
        for (index in estimatedTurnHeights.indices) {
            heightPrefix[index + 1] = heightPrefix[index] + estimatedTurnHeights[index]
        }
        state.smoothSeekToItem(
            targetIndex = { turnIndex },
            targetErrorPx = { visibleTarget -> visibleTarget.offset - topInsetPx },
            estimatedErrorPx = {
                val firstVisible = state.layoutInfo.visibleItemsInfo
                    .minByOrNull { item -> item.index }
                    ?: return@smoothSeekToItem null
                val firstIndex = firstVisible.index.coerceIn(0, turns.size)
                firstVisible.offset +
                    heightPrefix[turnIndex] -
                    heightPrefix[firstIndex] -
                    topInsetPx
            },
            exactTargetReady = { true },
            minimumStepPx = with(density) { 2.dp.toPx() },
        )
    }
}
