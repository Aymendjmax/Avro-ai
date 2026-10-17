package com.newoether.agora.ui.motion

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class IdentityClipWidthTest {
    @get:Rule val compose = createComposeRule()

    private var identity by mutableStateOf("a")
    private var target by mutableStateOf(100.dp)
    private var spatial by mutableStateOf(true)
    private var width: Dp = Dp.Unspecified

    private fun start() {
        compose.setContent { width = rememberIdentityClipWidth(identity, target, spatial) }
        compose.waitForIdle()
        compose.mainClock.autoAdvance = false
    }

    /** Frame by frame, idling in between so the clip coroutine runs on every frame. */
    private fun frames(millis: Long) {
        val end = compose.mainClock.currentTime + millis
        while (compose.mainClock.currentTime < end) {
            compose.mainClock.advanceTimeByFrame()
            compose.waitForIdle()
        }
    }

    @Test
    fun firstCompositionShowsTheTargetWithoutMotion() {
        start()
        assertEquals(100.dp, width)
    }

    @Test
    fun identityChangeMovesTheEdgeWithinOneDeadlineAndEndsExactlyOnTarget() {
        start()
        compose.runOnIdle { identity = "b"; target = 200.dp }
        frames(200)
        assertTrue("$width", width > 100.dp && width < 200.dp)
        frames(IDENTITY_CLIP_DURATION_MILLIS.toLong())
        assertEquals(200.dp, width)
    }

    @Test
    fun targetChangeDuringMotionIsAbsorbedWithoutOvershootingTheDeadline() {
        start()
        compose.runOnIdle { identity = "b"; target = 200.dp }
        frames(150)
        compose.runOnIdle { target = 160.dp }
        frames(IDENTITY_CLIP_DURATION_MILLIS - 100L)
        assertEquals(160.dp, width)
    }

    @Test
    fun targetChangeWithoutIdentityChangeAppliesAtOnce() {
        start()
        compose.runOnIdle { target = 140.dp }
        // The effect applies the edge after the frame that saw the change; allow the next frame.
        frames(48)
        assertEquals(140.dp, width)
    }

    @Test
    fun conversationToMenuCircleShrinksAndAnInterruptedReturnExpandsFromTheCurrentEdge() {
        start()
        compose.runOnIdle { identity = "new-chat"; target = 52.dp }
        frames(160)
        val interrupted = width
        assertTrue("$interrupted", interrupted > 52.dp && interrupted < 100.dp)
        compose.runOnIdle { identity = "conversation"; target = 200.dp }
        frames(48)
        val rebased = width
        assertTrue("$width", width > 52.dp && width < 200.dp)
        frames(48)
        assertTrue("$width", width > rebased && width < 200.dp)
        frames(IDENTITY_CLIP_DURATION_MILLIS.toLong())
        assertEquals(200.dp, width)
        compose.runOnIdle { identity = "new-chat"; target = 52.dp }
        frames(IDENTITY_CLIP_DURATION_MILLIS + 64L)
        assertEquals(52.dp, width)
    }
    @Test
    fun reducedMotionSnapsTheEdge() {
        start()
        compose.runOnIdle { spatial = false; identity = "b"; target = 60.dp }
        // The effect applies the edge after the frame that saw the change; allow the next frame.
        frames(48)
        assertEquals(60.dp, width)
    }
}
