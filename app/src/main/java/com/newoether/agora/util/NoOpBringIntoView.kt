package com.newoether.agora.util

import androidx.compose.foundation.text.selection.DisableSelection
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.movableContentOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.platform.InspectorInfo
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.relocation.BringIntoViewModifierNode
import com.newoether.agora.ui.components.FormulaSourceClipboard

private class NoOpBringIntoViewNode : Modifier.Node(), BringIntoViewModifierNode {
    override suspend fun bringIntoView(
        childCoordinates: LayoutCoordinates,
        boundsProvider: () -> Rect?
    ) {
        // Swallow the request; do not propagate to parent.
    }
}

private class NoOpBringIntoViewElement : ModifierNodeElement<NoOpBringIntoViewNode>() {
    override fun create(): NoOpBringIntoViewNode = NoOpBringIntoViewNode()
    override fun update(node: NoOpBringIntoViewNode) {}
    override fun InspectorInfo.inspectableProperties() {
        name = "noOpBringIntoView"
    }
    override fun equals(other: Any?): Boolean = this === other
    override fun hashCode(): Int = System.identityHashCode(this)
}

fun Modifier.noOpBringIntoView(): Modifier = this then NoOpBringIntoViewElement()

/**
 * Selection host for content inside a scroll container.
 *
 * Compose owns selection handles at the [SelectionContainer] boundary, so an interceptor placed
 * only on the content below it can be bypassed when selection begins. Keeping the interceptor on
 * the host itself guarantees that selection never repositions the surrounding conversation while
 * preserving normal user-driven scrolling.
 */
@Composable
fun NoAutoScrollSelectionContainer(
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable () -> Unit,
) {
    val latestContent = rememberUpdatedState(content)
    val movableContent = remember { movableContentOf { latestContent.value() } }
    // Copying from a selection must give the original message text, including line breaks
    // inside formula sources (see FormulaAlternateText.kt).
    val baseClipboard = LocalClipboard.current
    val clipboard = remember(baseClipboard) { FormulaSourceClipboard(baseClipboard) }
    CompositionLocalProvider(LocalClipboard provides clipboard) {
        SelectionContainer(modifier = modifier.noOpBringIntoView()) {
            if (enabled) {
                movableContent()
            } else {
                DisableSelection(content = movableContent)
            }
        }
    }
}
