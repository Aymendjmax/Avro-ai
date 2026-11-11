package com.newoether.agora.ui.chat.interaction

import com.newoether.agora.viewmodel.AskUserController
import com.newoether.agora.viewmodel.RequestWorkController
import com.newoether.agora.viewmodel.ShellConfirmationController

/**
 * One pending request the user has to answer before the tool loop that asked can continue.
 *
 * Questions, shell confirmations, and work-mode requests occupy the same screen space, so the bar
 * treats them as one ordered list. [key] is stable per request and prefixed per kind.
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

    data class WorkTransition(
        val pending: RequestWorkController.PendingWorkRequest,
    ) : UserInteraction {
        override val key: String get() = "work:${pending.id}"
    }
}

/**
 * Collects the requests the conversation [conversationId] must show, oldest first.
 */
internal fun userInteractions(
    conversationId: String?,
    questions: List<AskUserController.Request>,
    shellCommand: ShellConfirmationController.PendingShellCommand?,
    workRequest: RequestWorkController.PendingWorkRequest? = null,
): List<UserInteraction> {
    val visibleQuestions = questions
        .filter { it.conversationId == null || it.conversationId == conversationId }
        .groupBy { request -> request.setId?.let { "set:$it" } ?: "single:${request.id}" }
        .values
        .map { UserInteraction.Question(it) }
    val shell = shellCommand
        ?.takeIf { it.conversationId == null || it.conversationId == conversationId }
        ?.let { UserInteraction.ShellCommand(it) }
    val work = workRequest
        ?.takeIf { it.conversationId == conversationId }
        ?.let { UserInteraction.WorkTransition(it) }

    val head = listOfNotNull(work, shell)
    return head + visibleQuestions
}
