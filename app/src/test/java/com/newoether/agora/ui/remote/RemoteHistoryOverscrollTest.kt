package com.newoether.agora.ui.remote

import androidx.compose.foundation.OverscrollEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.unit.Velocity
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class RemoteHistoryOverscrollTest {
    /** Models Android's release when a stretched edge becomes consumable after prepend. */
    private class NativeEffect : OverscrollEffect {
        override val node = object : Modifier.Node() {}
        var stretch = 0f
        var implicitReleases = 0
        var releases = 0
        override val isInProgress get() = stretch > 0f
        override fun applyToScroll(delta: Offset, source: NestedScrollSource, performScroll: (Offset) -> Offset): Offset {
            val destretched = if (delta.y < 0f) minOf(stretch, -delta.y) else 0f
            stretch -= destretched
            val remaining = delta.copy(y = delta.y + destretched)
            val consumed = performScroll(remaining)
            if (stretch > 0f && consumed.y != 0f) { implicitReleases++; stretch = 0f }
            if (source == NestedScrollSource.UserInput) stretch += (remaining.y - consumed.y).coerceAtLeast(0f)
            return consumed + Offset(0f, -destretched + (remaining.y - consumed.y).coerceAtLeast(0f))
        }
        override suspend fun applyToFling(velocity: Velocity, performFling: suspend (Velocity) -> Velocity) {
            performFling(velocity)
            stretch = 0f
            releases++
        }
    }

    @Test fun newlyPublishedHistoryDoesNotReleaseHeldStretchOrMoveTheList() {
        val native = NativeEffect()
        var atEdge = true
        val effect = RemoteHistoryOverscroll(native) { atEdge }
        effect.applyToScroll(Offset(0f, 40f), NestedScrollSource.UserInput) { Offset.Zero }
        assertTrue(effect.isInProgress)
        assertSame(native.node, effect.node)
        atEdge = false // History is published while the finger is still down.
        var calls = 0
        var listTravel = 0f
        repeat(100) {
            assertEquals(Offset(0f, 2f), effect.applyToScroll(Offset(0f, 2f), NestedScrollSource.UserInput) { delta ->
                calls++; listTravel += delta.y; delta
            })
        }
        assertEquals(100, calls)
        assertEquals(0f, listTravel, 0f)
        assertEquals(240f, native.stretch, 0f)
        assertEquals(0, native.implicitReleases)
    }

    @Test fun reverseDestretchesThenScrollsNormallyWithinTheSameGesture() {
        val native = NativeEffect()
        var atEdge = true
        val effect = RemoteHistoryOverscroll(native) { atEdge }
        effect.applyToScroll(Offset(0f, 40f), NestedScrollSource.UserInput) { Offset.Zero }
        atEdge = false
        var calls = 0
        var travel = Offset.Zero
        effect.applyToScroll(Offset(0f, -55f), NestedScrollSource.UserInput) { delta -> calls++; travel = delta; delta }
        assertEquals(1, calls)
        assertEquals(Offset(0f, -15f), travel)
        assertFalse(effect.isInProgress)
        effect.applyToScroll(Offset(0f, 10f), NestedScrollSource.UserInput) { delta -> travel = delta; delta }
        assertEquals(Offset(0f, 10f), travel)
    }

    @Test fun releaseUsesOriginalFlingAndTheNextPullCanReadPublishedHistory() = runTest {
        val native = NativeEffect()
        var atEdge = true
        val effect = RemoteHistoryOverscroll(native) { atEdge }
        effect.applyToScroll(Offset(0f, 40f), NestedScrollSource.UserInput) { Offset.Zero }
        atEdge = false
        var calls = 0
        effect.applyToFling(Velocity(0f, 70f)) { velocity ->
            calls++; assertEquals(Velocity(0f, 70f), velocity); velocity
        }
        assertEquals(1, calls)
        assertEquals(1, native.releases)
        var travel = Offset.Zero
        effect.applyToScroll(Offset(0f, 20f), NestedScrollSource.UserInput) { delta -> travel = delta; delta }
        assertEquals(Offset(0f, 20f), travel)
    }

    @Test fun normalScrollingAndSideEffectsKeepTheirOriginalConsumption() {
        val native = NativeEffect()
        val effect = RemoteHistoryOverscroll(native) { false }
        for (source in listOf(NestedScrollSource.UserInput, NestedScrollSource.SideEffect)) {
            var calls = 0
            val delta = Offset(3f, 20f)
            assertEquals(delta, effect.applyToScroll(delta, source) { calls++; it })
            assertEquals(1, calls)
        }
        assertFalse(effect.isInProgress)
    }
}
