package com.newoether.agora.ui.chat

import com.newoether.agora.model.ChatMessage
import com.newoether.agora.model.Participant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class HydratedMessagePayloadLruTest {
    @Test
    fun entryLimitEvictsTheLeastRecentlyUsedMessage() {
        val cache = cache(maxEntries = 2, maxWeightBytes = 100)
        val first = message("first", 1)
        val second = message("second", 1)
        val third = message("third", 1)

        cache.put(first)
        cache.put(second)
        cache.put(third)

        assertFalse(cache.contains(first.id))
        assertSame(second, cache[second.id])
        assertSame(third, cache[third.id])
        assertEquals(2, cache.size)
    }

    @Test
    fun byteLimitEvictsUntilTheCacheIsWithinBudget() {
        val cache = cache(maxEntries = 8, maxWeightBytes = 10)

        cache.put(message("first", 6))
        cache.put(message("second", 6))

        assertFalse(cache.contains("first"))
        assertTrue(cache.contains("second"))
        assertEquals(6L, cache.totalWeightBytes)
    }

    @Test
    fun readRefreshesRecency() {
        val cache = cache(maxEntries = 2, maxWeightBytes = 100)
        val first = message("first", 1)
        val second = message("second", 1)

        cache.put(first)
        cache.put(second)
        assertSame(first, cache[first.id])
        cache.put(message("third", 1))

        assertTrue(cache.contains(first.id))
        assertFalse(cache.contains(second.id))
        assertTrue(cache.contains("third"))
    }

    @Test
    fun oversizedMessageIsNotCached() {
        val cache = cache(maxEntries = 8, maxWeightBytes = 10)

        cache.put(message("oversized", 11))

        assertNull(cache["oversized"])
        assertEquals(0, cache.size)
        assertEquals(0L, cache.totalWeightBytes)
    }

    @Test
    fun replacingTheSameIdRecalculatesWeightWithoutDoubleCounting() {
        val cache = cache(maxEntries = 8, maxWeightBytes = 100)

        cache.put(message("same", 8))
        cache.put(message("other", 5))
        cache.put(message("same", 3))

        assertEquals(2, cache.size)
        assertEquals(8L, cache.totalWeightBytes)
        assertEquals(3, cache["same"]?.tokenCount)
    }

    private fun cache(
        maxEntries: Int,
        maxWeightBytes: Long,
    ) = HydratedMessagePayloadLru(
        maxEntries = maxEntries,
        maxWeightBytes = maxWeightBytes,
        weightOf = { message -> message.tokenCount.toLong() },
    )

    private fun message(id: String, weight: Int) = ChatMessage(
        id = id,
        text = id,
        tokenCount = weight,
        participant = Participant.MODEL,
    )
}
