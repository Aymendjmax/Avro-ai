package com.newoether.agora.ui.chat.interaction

import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import com.newoether.agora.service.AppForegroundTracker
import com.newoether.agora.viewmodel.ChatViewModel

/**
 * Binds the chat screen's controllers to [UserInteractionBar] and anchors it above the composer.
 *
 * Keeping the wiring here means the bar itself stays a plain view of a request list, and the chat
 * screen only needs to say where the bar goes and how tall it turned out.
 *
 * [onHeightChanged] carries the measured height in pixels back to the caller, which uses it to lift
 * the controls that would otherwise sit underneath the bar.
 */
@Composable
internal fun BoxScope.ChatUserInteractionBar(
    viewModel: ChatViewModel,
    conversationId: String?,
    autoWrapCodeBlocks: Boolean,
    bottomBarHeight: Dp,
    onHeightChanged: (Float) -> Unit,
) {
    val questions by viewModel.askUser.requests.collectAsState()
    val shellCommand by viewModel.shellConfirmation.pendingShellCommand.collectAsState()
    val interactions = remember(questions, shellCommand, conversationId) {
        userInteractions(conversationId, questions, shellCommand)
    }
    // Requests of other conversations are never shown here; the notifiers surface them instead,
    // so they need to know which conversation is on screen.
    DisposableEffect(conversationId) {
        AppForegroundTracker.setPresentedConversation(conversationId)
        onDispose { AppForegroundTracker.setPresentedConversation(null) }
    }
    // Folding and the current page are remembered per conversation and survive switching away,
    // until that conversation's card has left with nothing left to answer. They are cleared only
    // once the card is gone, because the leaving card still shows the page and fold it had.
    var minimizedIn by rememberSaveable(saver = MinimizedSaver) { mutableStateOf(emptySet<String>()) }
    val minimizedKey = conversationId.orEmpty()
    // Picks, typing and the current page belong to the requests, not to the card on screen, so they
    // live here for every conversation at once: switching away and back or rotating keeps them,
    // and a request that is answered, skipped or withdrawn takes its draft with it.
    val drafts = rememberSaveable(saver = QuestionDrafts.Saver) { QuestionDrafts() }
    drafts.retainOnly(questions)
    var pageIn by rememberSaveable(saver = PageSaver) { mutableStateOf(emptyMap<String, String>()) }
    UserInteractionBar(
        conversationId = minimizedKey,
        interactions = interactions,
        minimizedIn = minimizedIn,
        onMinimizedChange = { owner, folded ->
            minimizedIn = if (folded) minimizedIn + owner else minimizedIn - owner
        },
        autoWrapCodeBlocks = autoWrapCodeBlocks,
        drafts = drafts,
        pageIn = pageIn,
        onPageChange = { owner, key -> pageIn = pageIn + (owner to key) },
        onCardGone = { owner ->
            // A card that left because its conversation was switched away still has requests.
            if (owner == minimizedKey && interactions.isEmpty()) {
                minimizedIn = minimizedIn - owner
                pageIn = pageIn - owner
            }
        },
        onSubmitQuestions = { answers -> viewModel.askUser.submitAll(answers) },
        onSkipQuestion = { id -> viewModel.askUser.dismiss(id) },
        onShellDecision = { id, allow, alwaysAllowServer ->
            viewModel.shellConfirmation.resolve(id, allow, alwaysAllowServer)
        },
        onHeightChanged = onHeightChanged,
        modifier = Modifier
            .align(Alignment.BottomCenter)
            .padding(bottom = bottomBarHeight),
    )
}

// Alternating conversation id and page key, so the saved value is a plain list of strings.
private val PageSaver = listSaver<androidx.compose.runtime.MutableState<Map<String, String>>, String>(
    save = { state -> state.value.flatMap { (owner, key) -> listOf(owner, key) } },
    restore = { saved -> mutableStateOf(saved.chunked(2).associate { (owner, key) -> owner to key }) },
)

private val MinimizedSaver = listSaver<androidx.compose.runtime.MutableState<Set<String>>, String>(
    save = { it.value.toList() },
    restore = { mutableStateOf(it.toSet()) },
)
