package com.newoether.agora.ui.settings

internal enum class DeveloperUnlockFeedback {
    NONE,
    REMAINING_TAPS,
    ENABLED,
    ALREADY_ENABLED,
}

internal data class DeveloperUnlockResult(
    val tapCount: Int,
    val remainingTaps: Int,
    val feedback: DeveloperUnlockFeedback,
)

/** Pure Android-style release unlock policy shared by the About UI and unit tests. */
internal object DeveloperModeUnlockPolicy {
    const val REQUIRED_TAPS = 7
    private const val FEEDBACK_THRESHOLD = 3

    fun advance(currentTapCount: Int, alreadyEnabled: Boolean): DeveloperUnlockResult {
        if (alreadyEnabled) {
            return DeveloperUnlockResult(
                tapCount = 0,
                remainingTaps = 0,
                feedback = DeveloperUnlockFeedback.ALREADY_ENABLED,
            )
        }

        val nextTapCount = currentTapCount.coerceIn(0, REQUIRED_TAPS - 1) + 1
        val remaining = REQUIRED_TAPS - nextTapCount
        if (remaining == 0) {
            return DeveloperUnlockResult(
                tapCount = 0,
                remainingTaps = 0,
                feedback = DeveloperUnlockFeedback.ENABLED,
            )
        }

        return DeveloperUnlockResult(
            tapCount = nextTapCount,
            remainingTaps = remaining,
            feedback = if (remaining <= FEEDBACK_THRESHOLD) {
                DeveloperUnlockFeedback.REMAINING_TAPS
            } else {
                DeveloperUnlockFeedback.NONE
            },
        )
    }
}
