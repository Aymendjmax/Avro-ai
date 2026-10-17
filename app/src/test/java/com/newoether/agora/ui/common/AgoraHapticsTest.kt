package com.newoether.agora.ui.common

import android.os.Build
import android.view.HapticFeedbackConstants
import android.view.View
import android.content.Context
import android.os.Vibrator
import com.newoether.agora.service.AppForegroundTracker
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Test

class AgoraHapticsTest {
    @Test
    fun discreteFeedbackPreservesTheVisibleAnswerRequestAndStopCancelsItsResume() {
        val callbacks = mutableListOf<Runnable>()
        var vibrating = false
        var enabled = true
        val vibrator = mockk<Vibrator>(relaxed = true)
        every { vibrator.hasVibrator() } returns true
        every { vibrator.vibrate(any<LongArray>(), any()) } answers { vibrating = true }
        every { vibrator.cancel() } answers { vibrating = false }
        val context = mockk<Context>()
        every { context.applicationContext } returns context
        every { context.getSystemService(Context.VIBRATOR_SERVICE) } returns vibrator
        val view = mockk<View>(relaxed = true)
        every { view.context } returns context
        every { view.postDelayed(any(), any()) } answers { callbacks.add(firstArg()); true }
        every { view.removeCallbacks(any()) } answers { callbacks.remove(firstArg()) }
        val haptics = PlatformAgoraHaptics(view) { enabled }
        AppForegroundTracker.setInForeground(true)
        try {
            haptics.startAnsweringTexture()
            assertTrue(vibrating)
            listOf(haptics::selection, haptics::destructiveConfirmed, haptics::interrupt).forEach { feedback ->
                feedback()
                assertFalse(vibrating)
                assertEquals(1, callbacks.size)
                callbacks.removeAt(0).run()
                assertTrue(vibrating)
            }
            haptics.selection()
            val cancelledResume = callbacks.single()
            haptics.stopAnsweringTexture()
            assertTrue(callbacks.isEmpty())
            cancelledResume.run()
            assertFalse(vibrating)
            haptics.startAnsweringTexture()
            assertTrue(vibrating)
            haptics.selection()
            enabled = false
            callbacks.removeAt(0).run()
            assertFalse(vibrating)
            haptics.stopAnsweringTexture()
            enabled = true
            haptics.startAnsweringTexture()
            haptics.selection()
            AppForegroundTracker.setInForeground(false)
            callbacks.removeAt(0).run()
            assertFalse(vibrating)
        } finally {
            haptics.stopAnsweringTexture()
            AppForegroundTracker.setInForeground(false)
        }
    }

    @Test
    fun selectionUsesSemanticSegmentTickWhenAvailable() {
        assertEquals(
            HapticFeedbackConstants.SEGMENT_TICK,
            selectionFeedbackForSdk(Build.VERSION_CODES.UPSIDE_DOWN_CAKE),
        )
        assertEquals(
            HapticFeedbackConstants.CLOCK_TICK,
            selectionFeedbackForSdk(Build.VERSION_CODES.TIRAMISU),
        )
    }

    @Test
    fun togglePreservesOnAndOffMeaningWhenAvailable() {
        assertEquals(
            HapticFeedbackConstants.TOGGLE_ON,
            toggleFeedbackForSdk(Build.VERSION_CODES.UPSIDE_DOWN_CAKE, isOn = true),
        )
        assertEquals(
            HapticFeedbackConstants.TOGGLE_OFF,
            toggleFeedbackForSdk(Build.VERSION_CODES.UPSIDE_DOWN_CAKE, isOn = false),
        )
        assertEquals(
            HapticFeedbackConstants.CLOCK_TICK,
            toggleFeedbackForSdk(Build.VERSION_CODES.TIRAMISU, isOn = true),
        )
    }

    @Test
    fun confirmAndRejectUseTheirSemanticConstantsWhenAvailable() {
        assertEquals(
            HapticFeedbackConstants.CONFIRM,
            confirmFeedbackForSdk(Build.VERSION_CODES.R),
        )
        assertEquals(
            HapticFeedbackConstants.REJECT,
            rejectFeedbackForSdk(Build.VERSION_CODES.R),
        )
        assertEquals(
            HapticFeedbackConstants.VIRTUAL_KEY,
            confirmFeedbackForSdk(Build.VERSION_CODES.Q),
        )
        assertEquals(
            HapticFeedbackConstants.LONG_PRESS,
            rejectFeedbackForSdk(Build.VERSION_CODES.Q),
        )
    }
}
