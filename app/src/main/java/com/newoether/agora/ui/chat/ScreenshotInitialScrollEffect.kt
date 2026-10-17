package com.newoether.agora.ui.chat

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.flow.first

@Composable
internal fun ScreenshotInitialScrollEffect(
    enabled: Boolean,
    currentConversationId: String?,
    loadedMessagesConversationId: String?,
    listState: LazyListState,
) {
    LaunchedEffect(enabled, currentConversationId, loadedMessagesConversationId) {
        if (
            enabled &&
            currentConversationId != null &&
            loadedMessagesConversationId == currentConversationId
        ) {
            snapshotFlow { listState.layoutInfo.totalItemsCount }
                .first { it > 0 }
            listState.scrollToItem(0)
        }
    }
}
