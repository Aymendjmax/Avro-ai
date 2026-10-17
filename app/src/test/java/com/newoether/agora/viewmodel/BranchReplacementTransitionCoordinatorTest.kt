package com.newoether.agora.viewmodel

import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BranchReplacementTransitionCoordinatorTest {
    @Test
    fun generationStartWaitsForFadeButNotScroll() = runTest {
        val coordinator = BranchReplacementTransitionCoordinator()
        val request = coordinator.begin(
            conversationId = "conversation",
            oldMessageId = "old-model",
            targetUserMessageId = "user",
        )!!
        val result = async { coordinator.awaitFade(request.id) }

        coordinator.acknowledgeScroll(request.id, success = true)
        runCurrent()
        assertFalse(result.isCompleted)

        coordinator.acknowledgeFade(request.id)
        assertTrue(result.await())
        assertTrue(coordinator.request.value?.fadeFinished == true)
        assertTrue(coordinator.request.value?.scrollFinished == true)
        assertTrue(coordinator.markCommitted(request.id))
        assertEquals(
            BranchReplacementTransitionStage.COMMITTED,
            coordinator.request.value?.stage,
        )

        assertTrue(coordinator.complete(request.id))
        assertNull(coordinator.request.value)
    }

    @Test
    fun failedScrollDoesNotBlockCommitAndAbortWakesFadeWaiter() = runTest {
        val coordinator = BranchReplacementTransitionCoordinator()
        val request = coordinator.begin(
            conversationId = "conversation",
            oldMessageId = "old-model",
            targetUserMessageId = "user",
        )!!
        val fade = async { coordinator.awaitFade(request.id) }
        coordinator.acknowledgeFade(request.id)
        coordinator.acknowledgeScroll(request.id, success = false)
        assertTrue(fade.await())
        assertTrue(coordinator.markCommitted(request.id))
        assertTrue(coordinator.request.value?.scrollFinished == true)
        assertFalse(coordinator.request.value?.scrollSucceeded ?: true)

        coordinator.abort(request.id)
        assertNull(coordinator.request.value)

        val second = coordinator.begin(
            conversationId = "conversation",
            oldMessageId = "old-model",
            targetUserMessageId = "user",
        )!!
        val aborted = async { coordinator.awaitFade(second.id) }
        coordinator.abort(second.id)
        assertFalse(aborted.await())
    }

    @Test
    fun editPublishesItsTargetOnlyAtCommit() = runTest {
        val coordinator = BranchReplacementTransitionCoordinator()
        val request = coordinator.begin(
            conversationId = "conversation",
            oldMessageId = "old-model",
            sourceUserMessageId = "old-user",
        )!!
        val fade = async { coordinator.awaitFade(request.id) }

        runCurrent()
        assertFalse(fade.isCompleted)
        assertNull(coordinator.request.value?.targetUserMessageId)

        coordinator.acknowledgeFade(request.id)
        assertTrue(fade.await())
        assertTrue(coordinator.markCommitted(request.id, targetUserMessageId = "new-user"))
        assertEquals("old-user", coordinator.request.value?.sourceUserMessageId)
        assertEquals("new-user", coordinator.request.value?.targetUserMessageId)
        coordinator.complete(request.id)
    }

    @Test
    fun replacementWithoutOldOutputDoesNotWaitForFade() = runTest {
        val coordinator = BranchReplacementTransitionCoordinator()
        val request = coordinator.begin(
            conversationId = "conversation",
            oldMessageId = null,
            sourceUserMessageId = "old-user",
        )!!

        assertTrue(request.fadeFinished)
        assertTrue(coordinator.awaitFade(request.id))
        coordinator.abort(request.id)
    }
}
