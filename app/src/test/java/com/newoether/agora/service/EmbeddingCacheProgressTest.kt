package com.newoether.agora.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class EmbeddingCacheProgressTest {
    @Test
    fun valuesShareOneGenerationAndOneDenominator() {
        val progress = EmbeddingCacheProgress(
            generationRevision = 42L,
            kind = EmbeddingCacheWorkKind.RECONCILE,
            processed = 10,
            total = 40,
        )

        assertEquals(30, progress.remaining)
        assertEquals(250, progress.progressPermille)
    }

    @Test
    fun completionIsExactRatherThanArtificiallyCapped() {
        val progress = EmbeddingCacheProgress(
            generationRevision = 9L,
            kind = EmbeddingCacheWorkKind.EXACT,
            processed = 7,
            total = 7,
        )

        assertEquals(0, progress.remaining)
        assertEquals(1000, progress.progressPermille)
    }

    @Test
    fun incoherentCountersAreRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            EmbeddingCacheProgress(
                generationRevision = 1L,
                kind = EmbeddingCacheWorkKind.EXACT,
                processed = 3,
                total = 2,
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            EmbeddingCacheProgress(
                generationRevision = 1L,
                kind = EmbeddingCacheWorkKind.EXACT,
                processed = 0,
                total = 0,
            )
        }
    }
}
