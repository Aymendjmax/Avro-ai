package com.newoether.agora.ui.chat

import com.newoether.agora.model.ChatMessage
import com.newoether.agora.model.MessageGenerationBoundaryResolver
import com.newoether.agora.model.MessageStatus
import com.newoether.agora.model.Participant
import com.newoether.agora.ui.chat.message.AssistantInlineActivityMode
import com.newoether.agora.ui.chat.message.assistantInlineActivityMode
import com.newoether.agora.ui.chat.message.isInfoSegment
import com.newoether.agora.ui.chat.message.isVisibleAnswerSegment

internal data class RunProjectionMessageKey(
    val id: String,
    val parentId: String?,
    val participant: Participant,
    val timestamp: Long,
    val runId: String?,
    val runSequence: Long?,
)

internal fun ChatMessage.toRunProjectionKey(): RunProjectionMessageKey =
    RunProjectionMessageKey(
        id = id,
        parentId = parentId,
        participant = participant,
        timestamp = timestamp,
        runId = runId,
        runSequence = runSequence,
    )

internal fun compactMessageActionsEnabled(
    isLoading: Boolean,
    isStopping: Boolean,
    isCompacting: Boolean,
): Boolean = !isLoading && !isStopping && !isCompacting

internal fun userBubbleSizeAnimationReady(hydrationPending: Boolean): Boolean =
    !hydrationPending

internal fun ChatMessage.hasAuthoritativeRenderPayload(): Boolean =
    text.isNotEmpty() ||
        images.isNotEmpty() ||
        thoughts != null ||
        thoughtTitle != null ||
        tokenUsage != null ||
        thoughtTimeMs != null ||
        toolCall != null ||
        segments != null ||
        attachmentMeta != null ||
        retryText != null

internal fun resolveMessagePayloadForRender(
    messageStub: ChatMessage,
    streamingMessage: ChatMessage?,
    observedMessage: ChatMessage?,
    cachedMessage: ChatMessage?,
): ChatMessage = when {
    messageStub.id == streamingMessage?.id -> streamingMessage
    messageStub.hasAuthoritativeRenderPayload() -> messageStub
    else -> observedMessage ?: cachedMessage ?: messageStub
}

internal data class StreamingTailPresentation(
    val visible: Boolean,
    val retainLayout: Boolean,
)

internal fun streamingTailPresentation(
    isLoading: Boolean,
    isStopping: Boolean,
    message: ChatMessage?,
): StreamingTailPresentation {
    val ownsTail = message?.let {
        val segments = it.segments.orEmpty()
        val generationActive = it.status == MessageStatus.SENDING ||
            it.status == MessageStatus.THINKING ||
            it.status == MessageStatus.TOOL_CALLING ||
            it.status == MessageStatus.TRANSCRIBING
        (isLoading || isStopping) && generationActive &&
            MessageGenerationBoundaryResolver.isOrdinaryAssistant(it) &&
            (segments.lastOrNull { segment ->
                segment.isVisibleAnswerSegment() || segment.isInfoSegment()
            }?.isVisibleAnswerSegment() ?: it.text.isNotBlank()) &&
            assistantInlineActivityMode(
                generationActive = generationActive,
                hasAnswer = it.text.isNotBlank() ||
                    segments.any { segment -> segment.isVisibleAnswerSegment() },
                hasVisibleInfoSegment = segments.any { segment -> segment.isInfoSegment() },
                retryText = it.retryText,
            ) == AssistantInlineActivityMode.NONE
    } == true
    return StreamingTailPresentation(
        visible = ownsTail && !isStopping,
        retainLayout = ownsTail && isStopping,
    )
}

internal fun shouldShowStreamingTailIndicator(
    isLoading: Boolean,
    isStopping: Boolean,
    message: ChatMessage?,
): Boolean = streamingTailPresentation(isLoading, isStopping, message).visible
