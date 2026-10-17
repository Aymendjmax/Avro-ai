package com.newoether.agora.viewmodel

import com.newoether.agora.model.MessageStatus

internal data class GenerationCompletionEffectsRequest(
    val terminalPersisted: Boolean,
    val status: MessageStatus,
    val text: String,
    val notificationText: String,
    val conversationId: String,
    val modelMessageId: String,
    val foregroundLeaseAcquired: Boolean,
    val isContextCompact: Boolean,
    val conversationVisible: Boolean?,
    val hasPendingContinuation: Boolean = false,
)

internal data class GenerationCompletionEffectsCallbacks(
    val onMessagePersisted: ((messageId: String, text: String) -> Unit)?,
    val onStreamClear: () -> Unit,
    val onLoadingChange: (Boolean) -> Unit,
    val hasQueuedSends: () -> Boolean,
)

internal fun GenerationCallbacks.completionEffectsCallbacks(
    onMessagePersisted: ((messageId: String, text: String) -> Unit)?,
) = GenerationCompletionEffectsCallbacks(
    onMessagePersisted = onMessagePersisted,
    onStreamClear = onStreamClear,
    onLoadingChange = onLoadingChange,
    hasQueuedSends = hasQueuedSends,
)

/** Executes post-finalization presentation/resource effects without owning Run-state authority. */
internal class GenerationCompletionEffectsExecutor(
    private val isAppInForeground: () -> Boolean,
    private val releaseForegroundLease: (modelMessageId: String) -> Unit,
    private val notify: (text: String, conversationId: String, status: MessageStatus) -> Unit,
) {
    fun execute(
        request: GenerationCompletionEffectsRequest,
        callbacks: GenerationCompletionEffectsCallbacks,
    ) {
        try {
            if (request.terminalPersisted && request.text.isNotBlank()) {
                callbacks.onMessagePersisted?.invoke(request.modelMessageId, request.text)
            }
        } catch (_: Exception) {
            // Indexing is non-authoritative and must never break terminal cleanup.
        }
        if (request.terminalPersisted) {
            callbacks.onStreamClear()
            callbacks.onLoadingChange(false)
        }
        if (request.foregroundLeaseAcquired) {
            releaseForegroundLease(request.modelMessageId)
        }

        val hasPendingGuidance =
            request.hasPendingContinuation || callbacks.hasQueuedSends()
        val appInForeground = if (request.conversationVisible == null) {
            isAppInForeground()
        } else {
            false
        }
        if (
            request.terminalPersisted &&
            request.notificationText.isNotBlank() &&
            shouldPostGenerationTerminalNotification(
                messageStatus = request.status,
                hasPendingGuidance = hasPendingGuidance,
                isContextCompact = request.isContextCompact,
                appInForeground = appInForeground,
                conversationVisible = request.conversationVisible,
            )
        ) {
            notify(request.notificationText, request.conversationId, request.status)
        }
    }
}
