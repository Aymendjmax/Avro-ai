package com.newoether.agora.ui.chat

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.newoether.agora.model.ChatConversation
import com.newoether.agora.ui.motion.LocalAgoraMotionPolicy
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

// A rejected pin write never reaches the list, so the pending scroll gives up after this.
private const val PIN_SCROLL_WAIT_MS = 2_000L

/**
 * Pinning moves a conversation into the drawer's top section. The returned callback records the
 * pinned id; once the list shows it pinned, the drawer scrolls to the top with the same feedback
 * as a new chat's first send. Unpinning does not call this and keeps the scroll position.
 */
@Composable
internal fun rememberDrawerPinScroll(
    listState: LazyListState,
    conversations: List<ChatConversation>,
    itemCount: Int,
    searchActive: Boolean,
): (String) -> Unit {
    val density = LocalDensity.current
    val motionPolicy by rememberUpdatedState(LocalAgoraMotionPolicy.current)
    val latestConversations by rememberUpdatedState(conversations)
    val latestItemCount by rememberUpdatedState(itemCount)
    val latestSearchActive by rememberUpdatedState(searchActive)
    var pendingId by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(pendingId, listState) {
        val pinnedId = pendingId ?: return@LaunchedEffect
        val ready = withTimeoutOrNull(PIN_SCROLL_WAIT_MS) {
            snapshotFlow {
                latestConversations.any { it.id == pinnedId && it.isPinned } &&
                    listState.layoutInfo.totalItemsCount == latestItemCount
            }.first { it }
        }
        if (ready == true && !latestSearchActive) {
            if (motionPolicy.allowProgrammaticScrollMotion) {
                listState.animateToAbsoluteTop(
                    estimatedItemSizePx = with(density) { 44.dp.toPx() },
                    minimumStepPx = with(density) { 2.dp.toPx() },
                    feedbackSpec = SendFeedbackScrollSpec,
                )
            } else {
                listState.scrollToItem(0)
            }
        }
        pendingId = null
    }
    return remember { { id: String -> pendingId = id } }
}
