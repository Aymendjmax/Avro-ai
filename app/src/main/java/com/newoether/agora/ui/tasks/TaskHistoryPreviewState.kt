package com.newoether.agora.ui.tasks

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect

internal enum class TaskHistoryPreviewPhase {
    IDLE,
    VIEWING,
    RETURNING,
}

/**
 * Navigation state for a Task History conversation.
 *
 * A history conversation is a transient preview. Return ownership is retained until both the
 * Tasks overlay fully covers Chat and a generation-fenced, non-haptic restoration request has
 * settled on the captured origin or reported failure. Opening another history item supersedes a return
 * without replacing that origin.
 */
internal data class TaskHistoryPreviewState(
    val phase: TaskHistoryPreviewPhase = TaskHistoryPreviewPhase.IDLE,
    val generation: Long = 0L,
    val taskId: String? = null,
    val originConversationId: String? = null,
    val originWasNewChat: Boolean = true,
    val previewConversationId: String? = null,
    val destinationObserved: Boolean = false,
    val restoreRequested: Boolean = false,
    val returnOverlayCovered: Boolean = false,
    val returnDestinationObserved: Boolean = false,
    val returnRestoreFailed: Boolean = false,
) {
    val active: Boolean
        get() = phase != TaskHistoryPreviewPhase.IDLE

    /** A retained preview cannot own Back on an ordinary or newly selected destination. */
    fun backTaskId(currentConversationId: String?, isNewChatMode: Boolean): String? =
        taskId?.takeIf {
            phase == TaskHistoryPreviewPhase.VIEWING && !isNewChatMode &&
                previewConversationId != null && currentConversationId == previewConversationId
        }

    fun open(
        taskId: String,
        previewConversationId: String,
        currentConversationId: String?,
        isNewChatMode: Boolean,
    ): TaskHistoryPreviewState =
        if (active) {
            copy(
                phase = TaskHistoryPreviewPhase.VIEWING,
                generation = generation + 1L,
                taskId = taskId,
                previewConversationId = previewConversationId,
                destinationObserved = false,
                restoreRequested = false,
                returnOverlayCovered = false,
                returnDestinationObserved = false,
                returnRestoreFailed = false,
            )
        } else {
            TaskHistoryPreviewState(
                phase = TaskHistoryPreviewPhase.VIEWING,
                generation = generation + 1L,
                taskId = taskId,
                originConversationId = currentConversationId,
                originWasNewChat = isNewChatMode || currentConversationId == null,
                previewConversationId = previewConversationId,
            )
        }

    fun observeDestination(
        currentConversationId: String?,
        isNewChatMode: Boolean,
        isSwitching: Boolean,
    ): TaskHistoryPreviewState {
        return when (phase) {
            TaskHistoryPreviewPhase.IDLE -> this
            TaskHistoryPreviewPhase.VIEWING -> {
                val previewId = previewConversationId ?: return this
                val previewIsSelected = !isNewChatMode && currentConversationId == previewId
                when {
                    !destinationObserved && previewIsSelected ->
                        copy(destinationObserved = true)
                    !destinationObserved -> this
                    previewIsSelected -> this
                    else -> settledIdle()
                }
            }
            TaskHistoryPreviewPhase.RETURNING -> {
                if (!restoreRequested || isSwitching) return this
                val originIsRestored = if (originWasNewChat) {
                    isNewChatMode && currentConversationId == null
                } else {
                    !isNewChatMode &&
                        originConversationId != null &&
                        currentConversationId == originConversationId
                }
                if (!originIsRestored) {
                    this
                } else {
                    copy(returnDestinationObserved = true).settleReturnIfComplete()
                }
            }
        }
    }

    fun requestReturn(): TaskHistoryPreviewState =
        if (phase == TaskHistoryPreviewPhase.VIEWING) {
            copy(
                phase = TaskHistoryPreviewPhase.RETURNING,
                generation = generation + 1L,
                restoreRequested = false,
                returnOverlayCovered = false,
                returnDestinationObserved = false,
                returnRestoreFailed = false,
            )
        } else {
            this
        }

    fun beginReturnRestore(): TaskHistoryPreviewState =
        if (phase == TaskHistoryPreviewPhase.RETURNING && !restoreRequested) {
            copy(restoreRequested = true)
        } else {
            this
        }

    fun markReturnOverlayCovered(expectedGeneration: Long): TaskHistoryPreviewState =
        if (
            phase == TaskHistoryPreviewPhase.RETURNING &&
            generation == expectedGeneration
        ) {
            copy(returnOverlayCovered = true).settleReturnIfComplete()
        } else {
            this
        }

    fun markReturnRestoreFailed(expectedGeneration: Long): TaskHistoryPreviewState =
        if (
            phase == TaskHistoryPreviewPhase.RETURNING && restoreRequested &&
            generation == expectedGeneration
        ) {
            copy(returnRestoreFailed = true).settleReturnIfComplete()
        } else {
            this
        }

    private fun settleReturnIfComplete(): TaskHistoryPreviewState =
        if (returnOverlayCovered && (returnDestinationObserved || returnRestoreFailed)) {
            settledIdle()
        } else {
            this
        }

    private fun settledIdle(): TaskHistoryPreviewState = copy(
        phase = TaskHistoryPreviewPhase.IDLE,
        taskId = null,
        originConversationId = null,
        originWasNewChat = true,
        previewConversationId = null,
        destinationObserved = false,
        restoreRequested = false,
        returnOverlayCovered = false,
        returnDestinationObserved = false,
        returnRestoreFailed = false,
    )

    companion object {
        val Idle = TaskHistoryPreviewState()
    }
}

/** Observes both the destination and switching settlement for the current return generation. */
@Composable
internal fun TaskHistoryDestinationEffect(
    editorSession: TaskEditorSessionViewModel,
    currentConversationId: String?,
    isNewChatMode: Boolean,
    isSwitching: Boolean,
) {
    val preview = editorSession.historyPreview
    LaunchedEffect(editorSession, preview, currentConversationId, isNewChatMode, isSwitching) {
        editorSession.observeHistoryDestination(
            currentConversationId = currentConversationId,
            isNewChatMode = isNewChatMode,
            isSwitching = isSwitching,
        )
    }
}
