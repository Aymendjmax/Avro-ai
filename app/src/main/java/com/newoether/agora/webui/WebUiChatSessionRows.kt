package com.newoether.agora.webui

import com.newoether.agora.automation.ConversationExecutionCoordinator
import com.newoether.agora.data.repository.ConversationRepository
import com.newoether.agora.model.ChatMessage
import com.newoether.agora.model.MessageGenerationBoundaryResolver
import com.newoether.agora.model.RunUiProjection
import com.newoether.agora.model.isContextCompact
import com.newoether.agora.viewmodel.ChatClient
import com.newoether.agora.viewmodel.ConversationStateRegistry
import com.newoether.agora.viewmodel.ConversationUiState
import com.newoether.agora.viewmodel.MessageGenerationController
import com.newoether.agora.viewmodel.toUiChatMessageStub
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.coroutines.resume

/**
 * One row-level command: the browser names a row, and the phone's own owners decide the branch.
 * [path] is the branch this connection is showing, because the phone resolves its generation
 * boundary inside the rows the browser can actually see.
 * Row dispatch is stateless; the session retains identity, admission and acknowledgement.
 */
internal suspend fun dispatchWebUiRowCommand(
    client: ChatClient,
    command: WebSyncCommand,
    path: List<ChatMessage>,
    conversationId: String,
    modelId: String,
    generation: MessageGenerationController,
    conversations: ConversationRepository,
    registry: ConversationStateRegistry,
    executionCoordinator: ConversationExecutionCoordinator,
    ownerMutex: Mutex,
    isCurrent: () -> Boolean,
    editorCommand: suspend (WebSyncCommand) -> Unit,
    reply: suspend (WebSyncEvent.RowAction) -> Unit = {},
) {
    val messageId = command.messageId ?: return
    val text = command.text.orEmpty()
    var success = false
    try {
        when (command.type) {
            "edit" -> if (modelId.isNotBlank() && text.isNotBlank()) generation.editMessage(
                client, conversationId, messageId, text, modelId, path,
            )
            "regenerate" -> if (modelId.isNotBlank()) generation.regenerate(
                client, conversationId, messageId, modelId, path,
            )
            "delete" -> success = suspendCancellableCoroutine<Boolean> { continuation ->
                generation.deleteMessage(client, conversationId, messageId, path) { deleted ->
                    if (continuation.isActive) continuation.resume(deleted)
                }
            }
            "recompact" -> if (path.any { it.id == messageId && it.isContextCompact() }) editorCommand(command)
            "branch" -> ownerMutex.withLock {
                if (command.direction !in setOf(-1, 1) || client.branchTransitions.request.value != null || !isCurrent()) return@withLock
                val state = registry.getOrCreate(conversationId)
                executionCoordinator.tryWithConversationLock(conversationId) {
                    state.queueMutationMutex.withLock mutation@ {
                        if (state.generating.value || state.stopping.value || !isCurrent()) return@mutation
                        val snapshot = conversations.getProviderContextTopologySnapshot(conversationId) ?: return@mutation
                        val all = snapshot.messages.map { it.toUiChatMessageStub() }
                        val selections = WebUiSync.decodeSelectedChildren(snapshot.selectedBranchesJson)
                        val current = ConversationUiState.resolvePath(all, null, selections)
                        if (current.map { it.id } != path.map { it.id }) return@mutation
                        val branch = RunUiProjection.project(current, all)[messageId] ?: return@mutation
                        if (!branch.showBranchSelector || branch.branchIndex != command.branchIndex ||
                            branch.totalBranches != command.totalBranches || branch.branchAnchorMessageId != command.branchAnchorMessageId ||
                            branch.branchAnchorParentId != command.branchAnchorParentId) return@mutation
                        val anchor = all.firstOrNull { it.id == branch.branchAnchorMessageId } ?: return@mutation
                        val siblings = all.filter { it.parentId == anchor.parentId && it.participant == anchor.participant &&
                            (MessageGenerationBoundaryResolver.isRealUser(it) || MessageGenerationBoundaryResolver.isOrdinaryAssistant(it)) }
                            .sortedWith(compareBy<ChatMessage> { it.timestamp }.thenBy { it.id })
                        val next = siblings.getOrNull(branch.branchIndex + command.direction) ?: return@mutation
                        val runId = next.runId?.takeIf(String::isNotBlank) ?: return@mutation
                        val parentRunId = anchor.parentId?.let { parent -> all.firstOrNull { it.id == parent }?.runId }
                        val transition = client.beginTreeMutation(conversationId, scrollToTarget = false)
                        try {
                            conversations.selectRunBranch(conversationId, parentRunId, runId, selections + (anchor.parentId to next.id))
                            client.settleTreeMutation(transition, next.id)
                            success = true
                        } catch (error: Exception) {
                            client.failTreeMutation(transition)
                            throw error
                        }
                    }
                }
            }
        }
    } finally {
        if (command.type in setOf("branch", "delete") && isCurrent()) {
            currentCoroutineContext().ensureActive()
            val snapshot = conversations.getProviderContextTopologySnapshot(conversationId)
            val all = snapshot?.messages?.map { it.toUiChatMessageStub() }.orEmpty()
            val current = ConversationUiState.resolvePath(all, null, WebUiSync.decodeSelectedChildren(snapshot?.selectedBranchesJson))
            val branches = RunUiProjection.project(current, all)
            // A receipt names the committed target; the ordinary path subscription still owns display.
            reply(WebSyncEvent.RowAction(conversationId, command.seq, command.actionId,
                current.map { it.toWebPathEntry(branches[it.id]) }, success))
        }
    }
}
