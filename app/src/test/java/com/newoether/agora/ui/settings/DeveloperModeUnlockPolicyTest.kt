package com.newoether.agora.ui.settings

import org.junit.Assert.assertEquals
import org.junit.Test

class DeveloperModeUnlockPolicyTest {
    @Test
    fun `unlock requires exactly seven taps`() {
        var taps = 0

        repeat(6) {
            val result = DeveloperModeUnlockPolicy.advance(taps, alreadyEnabled = false)
            taps = result.tapCount
            if (it < 3) {
                assertEquals(DeveloperUnlockFeedback.NONE, result.feedback)
            } else {
                assertEquals(DeveloperUnlockFeedback.REMAINING_TAPS, result.feedback)
                assertEquals(6 - it, result.remainingTaps)
            }
        }

        val enabled = DeveloperModeUnlockPolicy.advance(taps, alreadyEnabled = false)
        assertEquals(DeveloperUnlockFeedback.ENABLED, enabled.feedback)
        assertEquals(0, enabled.tapCount)
        assertEquals(0, enabled.remainingTaps)
    }

    @Test
    fun `already enabled reports status without accumulating taps`() {
        val result = DeveloperModeUnlockPolicy.advance(
            currentTapCount = 5,
            alreadyEnabled = true,
        )

        assertEquals(DeveloperUnlockFeedback.ALREADY_ENABLED, result.feedback)
        assertEquals(0, result.tapCount)
        assertEquals(0, result.remainingTaps)
    }

    @Test
    fun `invalid stored count is bounded before advancing`() {
        val result = DeveloperModeUnlockPolicy.advance(
            currentTapCount = Int.MAX_VALUE,
            alreadyEnabled = false,
        )

        assertEquals(DeveloperUnlockFeedback.ENABLED, result.feedback)
        assertEquals(0, result.tapCount)
    }
}
