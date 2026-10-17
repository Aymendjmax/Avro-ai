package com.newoether.agora.webui

import com.newoether.agora.viewmodel.ConversationLifecycleController
import com.newoether.agora.viewmodel.MessageGenerationController

/** Drawer protocol only; application controllers retain metadata and generation ownership. */
internal fun routeConversationMenu(
    command: WebSyncCommand,
    session: WebUiChatSession,
    lifecycle: ConversationLifecycleController,
    generation: MessageGenerationController,
    generating: Set<String>,
    deleted: (WebSyncEvent.ConversationDelete) -> Unit,
) {
    val id = command.conversationId?.takeIf(String::isNotBlank) ?: return
    when (command.type) {
        "pin" -> command.enabled?.let { lifecycle.setPinned(id, it) }
        "conversation_title" -> if (id !in generating) generation.generateTitle(id, session)
        "conversation_rename" -> command.text?.trim()?.takeIf(String::isNotEmpty)?.let { lifecycle.rename(id, it) }
        "conversation_delete" -> {
            if (command.actionId <= 0L) return
            val accepted = lifecycle.delete(session, id) { success ->
                deleted(WebSyncEvent.ConversationDelete(id, command.actionId, success))
            }
            if (!accepted) deleted(WebSyncEvent.ConversationDelete(id, command.actionId, false))
        }
    }
}
