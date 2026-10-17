package com.newoether.agora.service

import androidx.work.WorkInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EmbeddingCacheSchedulingTest {
    @Test
    fun reconcileReusesValidLegacyAndMatchingRowsWithoutRewritingThem() {
        assertTrue(
            shouldReuseReconciledEmbedding(
                embeddingId = 7L,
                dimension = 3,
                embeddingBytes = 12,
                existingFingerprint = null,
                expectedFingerprint = "current",
            ),
        )
        assertTrue(
            shouldReuseReconciledEmbedding(
                embeddingId = 7L,
                dimension = 3,
                embeddingBytes = 12,
                existingFingerprint = "current",
                expectedFingerprint = "current",
            ),
        )
    }

    @Test
    fun reconcileRegeneratesMissingCorruptOrStaleRows() {
        assertTrue(
            !shouldReuseReconciledEmbedding(
                embeddingId = null,
                dimension = null,
                embeddingBytes = null,
                existingFingerprint = null,
                expectedFingerprint = "current",
            ),
        )
        assertTrue(
            !shouldReuseReconciledEmbedding(
                embeddingId = 7L,
                dimension = 3,
                embeddingBytes = 8,
                existingFingerprint = null,
                expectedFingerprint = "current",
            ),
        )
        assertTrue(
            !shouldReuseReconciledEmbedding(
                embeddingId = 7L,
                dimension = 3,
                embeddingBytes = 12,
                existingFingerprint = "stale",
                expectedFingerprint = "current",
            ),
        )
    }

    @Test
    fun idleStartsOneWorker() {
        assertEquals(
            EmbeddingCacheScheduleDecision.KEEP_NEW,
            embeddingCacheScheduleDecision(emptyList()),
        )
    }

    @Test
    fun runningWorkerGetsAtMostOneFollower() {
        assertEquals(
            EmbeddingCacheScheduleDecision.APPEND_FOLLOWER,
            embeddingCacheScheduleDecision(listOf(WorkInfo.State.RUNNING)),
        )
        assertEquals(
            EmbeddingCacheScheduleDecision.NO_OP,
            embeddingCacheScheduleDecision(
                listOf(WorkInfo.State.RUNNING, WorkInfo.State.BLOCKED),
            ),
        )
    }

    @Test
    fun legacyOrImpossibleParallelChainsAreReplaced() {
        assertEquals(
            EmbeddingCacheScheduleDecision.REPLACE_LEGACY_CHAIN,
            embeddingCacheScheduleDecision(List(72) { WorkInfo.State.BLOCKED }),
        )
        assertEquals(
            EmbeddingCacheScheduleDecision.REPLACE_LEGACY_CHAIN,
            embeddingCacheScheduleDecision(
                listOf(WorkInfo.State.RUNNING, WorkInfo.State.RUNNING),
            ),
        )
    }

    @Test
    fun oneHundredRapidWakeupsNeverModelMoreThanTwoUnfinishedWorkers() {
        var states = emptyList<WorkInfo.State>()
        repeat(100) { index ->
            when (embeddingCacheScheduleDecision(states)) {
                EmbeddingCacheScheduleDecision.KEEP_NEW ->
                    states = listOf(WorkInfo.State.ENQUEUED)
                EmbeddingCacheScheduleDecision.APPEND_FOLLOWER ->
                    states = states + WorkInfo.State.BLOCKED
                EmbeddingCacheScheduleDecision.REPLACE_LEGACY_CHAIN ->
                    states = listOf(WorkInfo.State.ENQUEUED)
                EmbeddingCacheScheduleDecision.NO_OP -> Unit
            }
            if (index == 0) states = listOf(WorkInfo.State.RUNNING)
            assertTrue(states.count { !it.isFinished } <= 2)
        }
        assertEquals(
            listOf(WorkInfo.State.RUNNING, WorkInfo.State.BLOCKED),
            states,
        )
    }
}
