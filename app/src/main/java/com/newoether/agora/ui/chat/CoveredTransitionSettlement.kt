package com.newoether.agora.ui.chat

internal class ConversationHydrationRegistry(
    private val conversationId: String?,
    private val hydratedMessageIds: MutableMap<String, Unit>,
) {
    fun record(callbackConversationId: String?, messageId: String): Boolean {
        if (conversationId == null || callbackConversationId != conversationId) return false
        hydratedMessageIds[messageId] = Unit
        return true
    }

    fun containsAll(messageIds: Collection<String>): Boolean =
        messageIds.all(hydratedMessageIds::containsKey)

}

internal data class CoveredAbsoluteBottomSample(
    val viewportHeightPx: Int,
    val totalItemsCount: Int,
    val canScrollForward: Boolean,
    val sentinelIndex: Int?,
    val sentinelKey: Any?,
) {
    val targetIndex: Int
        get() = totalItemsCount - 1

    private val sentinelMatchesTarget: Boolean
        get() =
            targetIndex >= 0 &&
                sentinelIndex == targetIndex &&
                sentinelKey == AbsoluteBottomSentinelKey

    val needsScroll: Boolean
        get() =
            viewportHeightPx > 0 &&
                targetIndex >= 0 &&
                (canScrollForward || !sentinelMatchesTarget)

    val ready: Boolean
        get() =
            viewportHeightPx > 0 &&
                targetIndex >= 0 &&
                !canScrollForward &&
                sentinelMatchesTarget
}

internal class CoveredLayoutStabilityTracker(
    private val requiredSamples: Int,
) {
    init {
        require(requiredSamples > 0)
    }

    private var previousSignature: Any? = null
    var sampleCount: Int = 0
        private set

    fun observe(ready: Boolean, signature: Any): Boolean {
        if (!ready) {
            reset()
            return false
        }
        if (signature == previousSignature) {
            sampleCount += 1
        } else {
            previousSignature = signature
            sampleCount = 1
        }
        return sampleCount >= requiredSamples
    }

    fun reset() {
        previousSignature = null
        sampleCount = 0
    }
}
