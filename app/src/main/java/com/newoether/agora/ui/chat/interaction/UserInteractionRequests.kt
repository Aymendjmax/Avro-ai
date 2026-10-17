package com.newoether.agora.ui.chat.interaction

import com.newoether.agora.viewmodel.AskUserController
import com.newoether.agora.viewmodel.ShellConfirmationController

/**
 * One pending request the user has to answer before the tool loop that asked can continue.
 *
 * Questions and shell confirmations come from different controllers but occupy the same screen
 * space, so the bar treats them as one ordered list. [key] is stable per request and prefixed per
 * kind, because the two controllers number their requests independently.
 */
internal sealed interface UserInteraction {
    val key: String

    /** Everything one `ask_user` call asked: one question, or a set shown as one paged card. */
    data class Question(val requests: List<AskUserController.Request>) : UserInteraction {
        init {
            require(requests.isNotEmpty())
        }

        override val key: String
            get() = "question:${requests.first().let { it.setId ?: it.id }}"
    }

    data class ShellCommand(
        val pending: ShellConfirmationController.PendingShellCommand,
    ) : UserInteraction {
        override val key: String get() = "shell:${pending.id}"
    }
}

/**
 * Collects the requests the conversation [conversationId] must show, oldest first.
 *
 * A request is shown only in the conversation that made it; requests of other conversations are
 * surfaced as notifications. One without a conversation id came from a surface that has no chat of
 * its own (a task run, for example), so every conversation may answer it. The shell confirmation
 * comes first, because its command is held until it is decided.
 */
internal fun userInteractions(
    conversationId: String?,
    questions: List<AskUserController.Request>,
    shellCommand: ShellConfirmationController.PendingShellCommand?,
): List<UserInteraction> {
    // Questions of one set stay together in one card, placed where the set's first question is.
    val visibleQuestions = questions
        .filter { it.conversationId == null || it.conversationId == conversationId }
        .groupBy { request -> request.setId?.let { "set:$it" } ?: "single:${request.id}" }
        .values
        .map { UserInteraction.Question(it) }
    val shell = shellCommand
        ?.takeIf { it.conversationId == null || it.conversationId == conversationId }
        ?.let { UserInteraction.ShellCommand(it) }
    return if (shell == null) visibleQuestions else listOf(shell) + visibleQuestions
}
