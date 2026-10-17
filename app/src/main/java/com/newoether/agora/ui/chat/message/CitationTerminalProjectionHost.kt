package com.newoether.agora.ui.chat.message

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier

internal fun citationProjectionRequiresTerminalHandoff(
    presentedProjection: CitationMarkdownProjection?,
    targetProjection: CitationMarkdownProjection?,
): Boolean = presentedProjection != targetProjection

/**
 * Keeps the currently presented projection mounted until the list anchor is armed, then commits a
 * terminal citation update through one persistent Markdown subtree. The host adopts the terminal
 * measured size immediately; ordinary streaming growth and terminalization never interpolate it.
 */
@Composable
internal fun CitationTerminalProjectionHost(
    animationKey: String,
    projection: CitationMarkdownProjection?,
    isStreaming: Boolean,
    onLayoutMutationStarted: (String) -> Unit,
    onLayoutMutationSettled: (String) -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable (
        projection: CitationMarkdownProjection?,
        isStreaming: Boolean,
    ) -> Unit,
) {
    val mutationKey = "$animationKey:terminal-citation-projection"
    val currentLayoutMutationStarted by rememberUpdatedState(onLayoutMutationStarted)
    val currentLayoutMutationSettled by rememberUpdatedState(onLayoutMutationSettled)
    var presentedProjection by remember(animationKey) { mutableStateOf(projection) }
    var mutationActive by remember(animationKey) { mutableStateOf(false) }

    SideEffect {
        if (isStreaming) {
            presentedProjection = projection
        }
    }

    LaunchedEffect(animationKey, isStreaming, projection) {
        if (isStreaming) {
            if (mutationActive) {
                mutationActive = false
                currentLayoutMutationSettled(mutationKey)
            }
            return@LaunchedEffect
        }
        if (!citationProjectionRequiresTerminalHandoff(presentedProjection, projection)) {
            if (mutationActive) {
                mutationActive = false
                currentLayoutMutationSettled(mutationKey)
            }
            return@LaunchedEffect
        }

        if (!mutationActive) {
            mutationActive = true
            currentLayoutMutationStarted(mutationKey)
        }
        withFrameNanos { }
        presentedProjection = projection
        withFrameNanos { }
        withFrameNanos { }
        if (mutationActive && presentedProjection == projection) {
            mutationActive = false
            currentLayoutMutationSettled(mutationKey)
        }
    }

    DisposableEffect(mutationKey) {
        onDispose {
            if (mutationActive) currentLayoutMutationSettled(mutationKey)
        }
    }

    val terminalPending =
        !isStreaming && citationProjectionRequiresTerminalHandoff(presentedProjection, projection)
    val displayedProjection = if (isStreaming) projection else presentedProjection
    val displayedIsStreaming = isStreaming || terminalPending
    Box(modifier = modifier) {
        content(displayedProjection, displayedIsStreaming)
    }
}
