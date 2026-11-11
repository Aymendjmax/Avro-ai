package com.newoether.agora.viewmodel

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * Coordinates `request_work` tool calls between the generation pipeline and the chat interaction bar.
 *
 * When an LLM in Ask mode determines that the user's task requires local terminal execution,
 * file creation/editing, or Alpine Linux tools, it invokes `request_work(reason)`.
 * This controller suspends the asking tool call and displays an interactive Work mode transition
 * card in [UserInteractionBar].
 *
 * If the user approves, the conversation mode switches to [ConversationMode.WORK], the sandbox is
 * prepared if necessary, and the tool returns an accepted status so the model can continue seamlessly
 * in Work mode.
 *
 * If the user declines, the tool returns a declined status so the model stays in Ask mode.
 * To prevent repetitive tool spamming, a declined request marks the turn as declined.
 */
class RequestWorkController {

    data class PendingWorkRequest(
        val id: Long,
        val conversationId: String,
        val reason: String,
        val deferred: CompletableDeferred<Boolean>,
    )

    private val _pendingWorkRequest = MutableStateFlow<PendingWorkRequest?>(null)
    val pendingWorkRequest: StateFlow<PendingWorkRequest?> = _pendingWorkRequest.asStateFlow()

    private val nextRequestId = AtomicLong(1)
    private val declinedInTurn = ConcurrentHashMap<String, Boolean>()

    suspend fun requestWork(conversationId: String, reason: String): Boolean {
        if (declinedInTurn[conversationId] == true) {
            return false
        }
        val id = nextRequestId.getAndIncrement()
        val deferred = CompletableDeferred<Boolean>()
        val pending = PendingWorkRequest(
            id = id,
            conversationId = conversationId,
            reason = reason,
            deferred = deferred,
        )
        _pendingWorkRequest.value = pending
        return try {
            val approved = deferred.await()
            if (!approved) {
                declinedInTurn[conversationId] = true
            }
            approved
        } finally {
            if (_pendingWorkRequest.value?.id == id) {
                _pendingWorkRequest.value = null
            }
        }
    }

    fun resolve(id: Long, allow: Boolean) {
        val current = _pendingWorkRequest.value ?: return
        if (current.id == id) {
            if (allow) {
                declinedInTurn.remove(current.conversationId)
            }
            current.deferred.complete(allow)
            _pendingWorkRequest.value = null
        }
    }

    fun resetTurn(conversationId: String) {
        declinedInTurn.remove(conversationId)
    }

    fun abandon(id: Long) {
        val current = _pendingWorkRequest.value ?: return
        if (current.id == id) {
            current.deferred.complete(false)
            _pendingWorkRequest.value = null
        }
    }
}
