package com.newoether.agora.ui.chat

import androidx.compose.animation.core.CubicBezierEasing
import com.newoether.agora.model.ChatMessage
import com.newoether.agora.model.Participant
import com.newoether.agora.model.isContextCompact

internal val SCROLL_EASING = CubicBezierEasing(0.3f, 0.0f, 0.0f, 1.0f)

internal fun resolveScrollTargetMessage(
    currentMessages: List<ChatMessage>,
    targetMessageId: String?,
): ChatMessage? = if (targetMessageId != null) {
    val message = currentMessages.find { it.id == targetMessageId }
    if (
        message?.participant == Participant.MODEL &&
        !message.isContextCompact() &&
        message.parentId != null
    ) {
        currentMessages.find { it.id == message.parentId }
    } else {
        message
    }
} else {
    currentMessages.lastOrNull { it.participant == Participant.USER }
}

internal fun resolveScrollTargetIndex(
    currentMessages: List<ChatMessage>,
    targetMessageId: String?,
): Int {
    val target = resolveScrollTargetMessage(currentMessages, targetMessageId) ?: return -1
    return messageListTurnIndex(buildMessageListTurns(currentMessages), target.id)
}
