package com.newoether.agora.ui.chat

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CoveredTransitionSettlementTest {
    @Test
    fun emptyConversationRequiresMeasuredViewportAndExactBottomSentinel() {
        val ready = bottomSample(
            totalItemsCount = 1,
            sentinelIndex = 0,
            sentinelKey = AbsoluteBottomSentinelKey,
        )

        assertTrue(ready.ready)
        assertFalse(ready.needsScroll)
        assertFalse(ready.copy(viewportHeightPx = 0).ready)
        assertFalse(ready.copy(sentinelIndex = null, sentinelKey = null).ready)
    }

    @Test
    fun bottomSentinelMustMatchBothKeyAndFinalIndex() {
        val wrongIndex = bottomSample(
            totalItemsCount = 4,
            sentinelIndex = 2,
            sentinelKey = AbsoluteBottomSentinelKey,
        )
        val wrongKey = bottomSample(
            totalItemsCount = 4,
            sentinelIndex = 3,
            sentinelKey = "not-the-bottom-sentinel",
        )

        assertFalse(wrongIndex.ready)
        assertTrue(wrongIndex.needsScroll)
        assertFalse(wrongKey.ready)
        assertTrue(wrongKey.needsScroll)
    }

    @Test
    fun physicalBottomReadinessDoesNotDependOnPayloadHydration() {
        val ready = bottomSample()

        assertTrue(ready.ready)
        assertFalse(ready.needsScroll)
    }

    @Test
    fun veryTallLastTurnCanSettleOnceItsPhysicalSentinelIsVisible() {
        val tallTailAtPhysicalEnd = bottomSample(
            totalItemsCount = 4,
            sentinelIndex = 3,
            sentinelKey = AbsoluteBottomSentinelKey,
            canScrollForward = false,
        )

        assertTrue(tallTailAtPhysicalEnd.ready)
        assertFalse(tallTailAtPhysicalEnd.needsScroll)
    }

    @Test
    fun coverRequiresThreeIdenticalReadyLayoutSamples() {
        val tracker = CoveredLayoutStabilityTracker(requiredSamples = 3)
        val signature = listOf("messages", 1_000, 4, 3)

        assertFalse(tracker.observe(ready = true, signature = signature))
        assertFalse(tracker.observe(ready = true, signature = signature))
        assertTrue(tracker.observe(ready = true, signature = signature))
        assertEquals(3, tracker.sampleCount)
    }

    @Test
    fun invalidOrChangedLayoutRestartsCoveredSettlement() {
        val tracker = CoveredLayoutStabilityTracker(requiredSamples = 3)

        tracker.observe(ready = true, signature = "first")
        tracker.observe(ready = true, signature = "first")
        assertFalse(tracker.observe(ready = false, signature = "ignored"))
        assertEquals(0, tracker.sampleCount)
        assertFalse(tracker.observe(ready = true, signature = "second"))
        assertEquals(1, tracker.sampleCount)
    }

    @Test
    fun hydrationCallbacksAreScopedToTheOwningConversation() {
        val hydrated = mutableMapOf<String, Unit>()
        val registry = ConversationHydrationRegistry("conversation-a", hydrated)

        assertFalse(registry.record("conversation-b", "message-b"))
        assertFalse(registry.record(null, "message-null"))
        assertTrue(registry.record("conversation-a", "message-a"))
        assertEquals(setOf("message-a"), hydrated.keys)
        assertTrue(registry.containsAll(listOf("message-a")))
        assertFalse(registry.containsAll(listOf("message-a", "message-b")))
    }

    @Test
    fun nullConversationRegistryFailsClosed() {
        val hydrated = mutableMapOf<String, Unit>()
        val registry = ConversationHydrationRegistry(null, hydrated)

        assertFalse(registry.record(null, "message"))
        assertTrue(hydrated.isEmpty())
    }

    @Test
    fun coveredSettlementKeepsItsBoundedTimeoutAndChecksBottomBeforeEmptyContent() {
        val source = File(
            locateMainSourceRoot(),
            "com/newoether/agora/ui/chat/ChatScrollCoordinator.kt",
        ).readText()
        val settleStart = source.indexOf("private suspend fun settleCoveredTransition(")
        val settleEnd = source.lastIndexOf("\n}")
        val settle = source.substring(settleStart, settleEnd)
        val bottomBranch = settle.indexOf("if (scrollToAbsoluteBottom)")

        assertTrue(settle.contains("withTimeoutOrNull(SCROLL_SETTLE_TIMEOUT_MS)"))
        assertTrue(settle.contains("} == true"))
        assertTrue(bottomBranch >= 0)
        assertFalse(settle.substring(0, bottomBranch).contains("currentMessages.isEmpty()"))
        assertTrue(settle.contains("CoveredAbsoluteBottomSample("))
        assertFalse(settle.contains("hydrationRegistry.containsAll"))
    }

    private fun bottomSample(
        viewportHeightPx: Int = 1_000,
        totalItemsCount: Int = 4,
        canScrollForward: Boolean = false,
        sentinelIndex: Int? = totalItemsCount - 1,
        sentinelKey: Any? = AbsoluteBottomSentinelKey,
    ) = CoveredAbsoluteBottomSample(
        viewportHeightPx = viewportHeightPx,
        totalItemsCount = totalItemsCount,
        canScrollForward = canScrollForward,
        sentinelIndex = sentinelIndex,
        sentinelKey = sentinelKey,
    )

    private fun locateMainSourceRoot(): File {
        var directory = File(requireNotNull(System.getProperty("user.dir"))).absoluteFile
        repeat(8) {
            listOf(
                File(directory, "app/src/main/java"),
                File(directory, "src/main/java"),
            ).firstOrNull(File::isDirectory)?.let { return it }
            directory = directory.parentFile ?: error("Reached filesystem root")
        }
        error("Unable to locate the main Java source directory")
    }
}
