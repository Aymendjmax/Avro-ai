package com.newoether.agora.viewmodel

import com.newoether.agora.util.DebugLog
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/**
 * Publishes one accepted Send without turning presentation callback failure into Send failure.
 * The event goes to the Send's origin client, or to every client showing the conversation when the
 * Send has no origin (queue drain, Loop cycle).
 */
internal class SendAcceptanceNotifier(
    private val clients: ChatClients,
) {
    suspend fun notify(
        acceptance: SendAcceptance,
        onAccepted: suspend (SendAcceptance) -> Unit,
        origin: ChatClient?,
        publishEvent: Boolean = true,
    ) {
        // Draft settlement is authoritative: callers must observe a failure instead of mistaking a
        // presentation callback catch for a successful acknowledgement.
        withContext(NonCancellable) { onAccepted(acceptance) }
        if (publishEvent) publish(acceptance, origin)
    }

    fun publish(acceptance: SendAcceptance, origin: ChatClient?) {
        clients.effectTargets(acceptance.conversationId, origin).forEach { client ->
            try {
                client.onSendAccepted(acceptance.conversationId, acceptance.messageId)
            } catch (error: Exception) {
                DebugLog.e(
                    "SendAcceptanceNotifier",
                    "Failed to publish accepted Send ${acceptance.messageId}",
                    error,
                )
            }
        }
    }
}
