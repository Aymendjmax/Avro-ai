package com.newoether.agora

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TopLevelPresentationStateTest {
    @Test
    fun nestedPreviewReturnsToTheStillVisibleOverlay() {
        listOf(TopLevelPresentation.REMOTE, TopLevelPresentation.SETTINGS, TopLevelPresentation.TASKS).forEach { base ->
            val owners = mutableListOf<TopLevelPresentation>()
            val state = TopLevelPresentationState(onOwnerChanged = owners::add)
            state.present(base)
            state.present(TopLevelPresentation.MEDIA_PREVIEW)
            state.present(TopLevelPresentation.TEXT_PREVIEW)
            assertTrue(state.release(TopLevelPresentation.TEXT_PREVIEW))
            assertEquals(TopLevelPresentation.MEDIA_PREVIEW, state.owner)
            assertTrue(state.release(TopLevelPresentation.MEDIA_PREVIEW))
            assertEquals(base, state.owner)
            assertFalse(owners.drop(1).contains(TopLevelPresentation.CHAT))
            assertTrue(state.release(base))
            assertEquals(TopLevelPresentation.CHAT, state.owner)
        }
    }

    @Test
    fun exitedUnderlyingOverlayDoesNotReappearAfterPreview() {
        val state = TopLevelPresentationState(TopLevelPresentation.REMOTE)
        state.present(TopLevelPresentation.MEDIA_PREVIEW)
        assertFalse(state.release(TopLevelPresentation.REMOTE))
        assertEquals(TopLevelPresentation.MEDIA_PREVIEW, state.owner)
        assertTrue(state.release(TopLevelPresentation.MEDIA_PREVIEW))
        assertEquals(TopLevelPresentation.CHAT, state.owner)
    }

    @Test
    fun repeatedPresentationDoesNotLeaveAnInvisibleBlocker() {
        val state = TopLevelPresentationState()
        repeat(3) { state.present(TopLevelPresentation.REMOTE) }
        assertTrue(state.release(TopLevelPresentation.REMOTE))
        assertEquals(TopLevelPresentation.CHAT, state.owner)
        assertFalse(state.release(TopLevelPresentation.REMOTE))
    }

    @Test
    fun ownerIsRetainedUntilMatchingExitCompletes() {
        val owners = mutableListOf<TopLevelPresentation>()
        val state = TopLevelPresentationState(onOwnerChanged = owners::add)
        state.present(TopLevelPresentation.SETTINGS)

        assertEquals(TopLevelPresentation.SETTINGS, state.owner)
        assertTrue(state.release(TopLevelPresentation.SETTINGS))
        assertEquals(TopLevelPresentation.CHAT, state.owner)
        assertEquals(
            listOf(TopLevelPresentation.CHAT, TopLevelPresentation.SETTINGS, TopLevelPresentation.CHAT),
            owners,
        )
    }

    @Test
    fun restoredBlockingOwnerStartsFailClosed() {
        val owners = mutableListOf<TopLevelPresentation>()
        val state = TopLevelPresentationState(TopLevelPresentation.TASKS, owners::add)

        assertEquals(TopLevelPresentation.TASKS, state.owner)
        assertEquals(listOf(TopLevelPresentation.TASKS), owners)
    }

    @Test
    fun staleExitCannotReleaseNewerPresentation() {
        val owners = mutableListOf<TopLevelPresentation>()
        val state = TopLevelPresentationState(onOwnerChanged = owners::add)
        state.present(TopLevelPresentation.SETTINGS)
        state.present(TopLevelPresentation.MEDIA_PREVIEW)

        assertFalse(state.release(TopLevelPresentation.SETTINGS))
        assertEquals(TopLevelPresentation.MEDIA_PREVIEW, state.owner)
        assertEquals(
            listOf(TopLevelPresentation.CHAT, TopLevelPresentation.SETTINGS, TopLevelPresentation.MEDIA_PREVIEW),
            owners,
        )
    }
}
