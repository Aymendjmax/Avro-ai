package com.newoether.agora.ui.chat

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import com.newoether.agora.data.CustomProviderConfig
import com.newoether.agora.data.forDisplay
import com.newoether.agora.model.ChatMessage
import com.newoether.agora.viewmodel.ChatViewModel
import kotlinx.coroutines.flow.Flow

internal data class ChatMessageHydrationBindings(
    val observeMessage: (String) -> Flow<ChatMessage?>,
    val searchMessages: suspend (String, List<String>) -> List<ChatMessage>,
)

@Composable
internal fun rememberChatMessageHydrationBindings(
    viewModel: ChatViewModel,
    customProviders: List<CustomProviderConfig>,
): ChatMessageHydrationBindings = remember(viewModel, customProviders) {
    ChatMessageHydrationBindings(
        observeMessage = { messageId ->
            viewModel.messagePayloadHydration.observeMessage(messageId) { message ->
                message.forDisplay(customProviders)
            }
        },
        searchMessages = { conversationId, messageIds ->
            viewModel.messagePayloadHydration.loadMessages(conversationId, messageIds) { message ->
                message.forDisplay(customProviders)
            }
        },
    )
}
