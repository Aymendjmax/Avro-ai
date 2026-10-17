package com.newoether.agora.webui

import com.newoether.agora.model.ChatMessage
import com.newoether.agora.viewmodel.BranchReplacementTransitionStage
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.flow.collect
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Protocol projection only; the session's ordinary coordinator owns transition lifetime. */
internal suspend fun forwardBranchReplacement(
    session: WebUiChatSession,
    target: WebUiChatSession.OpenTarget,
    connectionId: String,
    outbound: SendChannel<WebSyncEvent>,
) {
    val conversationId = target.conversationId ?: return
    session.branchTransitions.request.collect { request ->
        outbound.send(WebSyncEvent.BranchReplacement(
            connectionId, conversationId, target.browserSeq,
            request?.takeIf { it.conversationId == conversationId }?.let {
                buildJsonObject {
                    put("id", it.id)
                    put("oldMessageId", it.oldMessageId)
                    put("sourceUserMessageId", it.sourceUserMessageId)
                    put("targetUserMessageId", it.targetUserMessageId)
                    put("stage", it.stage.name)
                    put("fadeFinished", it.fadeFinished)
                    put("scrollFinished", it.scrollFinished)
                }
            },
        ))
    }
}

/** Reject acknowledgements that no longer belong to this connection and selected request. */
internal fun acknowledgeBranchReplacement(
    session: WebUiChatSession,
    command: WebSyncCommand,
    connectionId: String,
    visiblePath: List<ChatMessage>,
) {
    val target = session.openTarget.value
    val transitions = session.branchTransitions
    val request = transitions.request.value ?: return
    if (command.connectionId != connectionId || command.seq != target.browserSeq ||
        command.conversationId != target.conversationId || request.conversationId != target.conversationId ||
        command.actionId != request.id) return
    when (command.type) {
        "branch_fade" -> transitions.acknowledgeFade(request.id)
        "branch_scroll" -> command.enabled?.let { transitions.acknowledgeScroll(request.id, it) }
        "branch_complete" -> if (request.stage == BranchReplacementTransitionStage.COMMITTED &&
            request.scrollFinished && visiblePath.none { it.id == request.oldMessageId }) {
            transitions.complete(request.id)
        }
    }
}
