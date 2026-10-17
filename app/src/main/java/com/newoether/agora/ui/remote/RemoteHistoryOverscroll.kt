package com.newoether.agora.ui.remote

import androidx.compose.foundation.OverscrollEffect
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.rememberOverscrollEffect
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.unit.Velocity

@Composable
internal fun rememberRemoteHistoryOverscroll(state: LazyListState): OverscrollEffect? {
    val original = rememberOverscrollEffect()
    return remember(state, original) {
        original?.let { RemoteHistoryOverscroll(it) { !state.canScrollBackward } }
    }
}

/** Keep the original stretch during the same held pull when history makes the edge scrollable.
 * The platform effect still owns all drawing, destretch and release physics. The wrapper only
 * fences consumption into newly prepended items, and never delays publishing those items.
 */
internal class RemoteHistoryOverscroll(
    private val original: OverscrollEffect,
    private val atStart: () -> Boolean,
) : OverscrollEffect {
    private var holdingStart = false
    override val node get() = original.node
    override val isInProgress get() = original.isInProgress

    override fun applyToScroll(
        delta: Offset,
        source: NestedScrollSource,
        performScroll: (Offset) -> Offset,
    ): Offset {
        if (!original.isInProgress) holdingStart = false
        val pullingStart = source == NestedScrollSource.UserInput && delta.y > 0f
        val startedAtEdge = pullingStart && atStart()
        val consumed = original.applyToScroll(delta, source) { remaining ->
            // Android releases existing stretch as soon as performScroll consumes the pull.
            // A page becoming available is not the user's release or reverse gesture.
            if (holdingStart && pullingStart && remaining.y > 0f && original.isInProgress) {
                performScroll(remaining.copy(y = 0f)).copy(y = 0f)
            } else performScroll(remaining)
        }
        if (startedAtEdge && original.isInProgress) holdingStart = true
        if (!original.isInProgress) holdingStart = false
        return consumed
    }

    override suspend fun applyToFling(velocity: Velocity, performFling: suspend (Velocity) -> Velocity) {
        holdingStart = false
        original.applyToFling(velocity, performFling)
    }
}
