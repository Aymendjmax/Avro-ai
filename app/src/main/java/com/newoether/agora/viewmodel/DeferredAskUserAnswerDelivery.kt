package com.newoether.agora.viewmodel

import com.newoether.agora.util.DebugLog
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import java.util.UUID

/**
 * Delivers answers to non-blocking `ask_user` questions.
 *
 * A non-blocking question let the model keep working, so its answer cannot come back as a tool
 * result. It travels the same way a message typed during a generation does: into the conversation's
 * queue, drained into a real user turn when the current generation settles. That reuse is the point,
 * because the queue already owns ordering, merging and Run creation.
 *
 * Nothing is generating when the user answers after the asking run finished, and the drain that
 * normally follows a generation has already happened, so this asks for that drain itself.
 */
internal class DeferredAskUserAnswerDelivery(
    private val askUser: AskUserController,
    private val registry: ConversationStateRegistry,
    private val scope: CoroutineScope,
    /** Model selected in the answered conversation; the queue needs one to admit the send. */
    private val conversationModelId: suspend (String) -> String?,
    private val fallbackModelId: () -> String?,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    fun start() {
        scope.launch(ioDispatcher) {
            askUser.deferredAnswers.collect { answer -> deliver(answer) }
        }
    }

    private suspend fun deliver(answer: AskUserController.DeferredAnswer) {
        // Without a model the drain would reject the batch, so the send is not queued at all. The
        // answer is dropped in that case; record it instead of losing it without a trace.
        val modelId = conversationModelId(answer.conversationId)?.takeIf { it.isNotBlank() }
            ?: fallbackModelId()?.takeIf { it.isNotBlank() }
            ?: run {
                DebugLog.w(
                    "AskUser",
                    "Dropped a deferred answer for ${answer.conversationId}: no model is selected",
                )
                return
            }
        val state = registry.getOrCreate(answer.conversationId)
        state.queueMutationMutex.withLock {
            state.enqueueSend(
                QueuedSend(
                    id = UUID.randomUUID().toString(),
                    text = answer.text,
                    modelId = modelId,
                    attachments = emptyList(),
                    runId = UUID.randomUUID().toString(),
                    source = answer.source,
                ),
            )
        }
        if (!state.generating.value) state.onQueueDrainRequested?.invoke(state)
    }
}
