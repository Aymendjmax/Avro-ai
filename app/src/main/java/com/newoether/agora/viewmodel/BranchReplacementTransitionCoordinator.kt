package com.newoether.agora.viewmodel

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.atomic.AtomicLong

internal enum class BranchReplacementTransitionStage {
    ANIMATING,
    COMMITTED,
}

internal data class BranchReplacementTransitionRequest(
    val id: Long,
    val conversationId: String,
    val oldMessageId: String?,
    val sourceUserMessageId: String?,
    val targetUserMessageId: String?,
    val stage: BranchReplacementTransitionStage,
    val fadeFinished: Boolean = false,
    val scrollFinished: Boolean = false,
    val scrollSucceeded: Boolean? = null,
)

/**
 * Coordinates the visual half of a durable branch replacement without mutating its graph.
 *
 * Fade and scroll are intentionally independent when the target already exists, as in Regenerate.
 * Edit publishes its target only at commit, so the same request starts scrolling after that target
 * becomes visible. In both cases COMMITTED retains the transparent old composition until scrolling
 * finishes, preventing the selected path from collapsing during the targeted movement.
 */
internal class BranchReplacementTransitionCoordinator(
    private val fadeTimeoutMs: Long = 8_000L,
) {
    private data class ActiveTransition(
        val request: BranchReplacementTransitionRequest,
        val fadeFinished: CompletableDeferred<Boolean> = CompletableDeferred(),
    )

    private val lock = Any()
    private val ids = AtomicLong(0L)
    private var active: ActiveTransition? = null
    private val _request = MutableStateFlow<BranchReplacementTransitionRequest?>(null)
    val request: StateFlow<BranchReplacementTransitionRequest?> = _request.asStateFlow()

    fun begin(
        conversationId: String,
        oldMessageId: String?,
        sourceUserMessageId: String? = null,
        targetUserMessageId: String? = null,
    ): BranchReplacementTransitionRequest? = synchronized(lock) {
        if (active != null) return null
        val fadeAlreadyFinished = oldMessageId == null
        val request = BranchReplacementTransitionRequest(
            id = ids.incrementAndGet(),
            conversationId = conversationId,
            oldMessageId = oldMessageId,
            sourceUserMessageId = sourceUserMessageId,
            targetUserMessageId = targetUserMessageId,
            stage = BranchReplacementTransitionStage.ANIMATING,
            fadeFinished = fadeAlreadyFinished,
        )
        val transition = ActiveTransition(request)
        if (fadeAlreadyFinished) transition.fadeFinished.complete(true)
        active = transition
        _request.value = request
        request
    }

    fun acknowledgeFade(requestId: Long) {
        synchronized(lock) {
            val transition =
                active?.takeIf { candidate -> candidate.request.id == requestId } ?: return
            if (!transition.fadeFinished.complete(true)) return
            val updated = transition.request.copy(fadeFinished = true)
            active = transition.copy(request = updated)
            _request.value = updated
        }
    }

    fun acknowledgeScroll(requestId: Long, success: Boolean) {
        synchronized(lock) {
            val transition =
                active?.takeIf { candidate -> candidate.request.id == requestId } ?: return
            if (transition.request.scrollFinished) return
            val updated = transition.request.copy(
                scrollFinished = true,
                scrollSucceeded = success,
            )
            active = transition.copy(request = updated)
            _request.value = updated
        }
    }

    suspend fun awaitFade(requestId: Long): Boolean {
        val transition = synchronized(lock) {
            active?.takeIf { it.request.id == requestId }
        } ?: return false
        return withTimeoutOrNull(fadeTimeoutMs) {
            transition.fadeFinished.await()
        } == true
    }

    fun isAnimating(requestId: Long): Boolean = synchronized(lock) {
        active?.request?.let {
            it.id == requestId && it.stage == BranchReplacementTransitionStage.ANIMATING
        } == true
    }

    fun markCommitted(
        requestId: Long,
        targetUserMessageId: String? = null,
    ): Boolean = synchronized(lock) {
        val transition = active?.takeIf { it.request.id == requestId } ?: return false
        val committed = transition.request.copy(
            targetUserMessageId = targetUserMessageId ?: transition.request.targetUserMessageId,
            stage = BranchReplacementTransitionStage.COMMITTED,
        )
        active = transition.copy(request = committed)
        _request.value = committed
        true
    }

    fun complete(requestId: Long): Boolean = synchronized(lock) {
        val transition = active?.takeIf { it.request.id == requestId } ?: return false
        active = null
        _request.value = null
        // Harmless if already complete; wakes a waiter if navigation clears a pre-commit request.
        transition.fadeFinished.complete(false)
        true
    }

    fun abort(requestId: Long): Boolean = complete(requestId)

    fun abortCurrent() {
        val requestId = synchronized(lock) { active?.request?.id } ?: return
        complete(requestId)
    }
}
