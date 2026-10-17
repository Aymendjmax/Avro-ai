package com.newoether.agora.ui.chat

import android.app.Application
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "w400dp-h800dp-mdpi")
class ChatLoadingOverlayTest {
    @get:Rule val compose = createComposeRule()
    private val progress = SemanticsMatcher.keyIsDefined(SemanticsProperties.ProgressBarRangeInfo)

    @OptIn(ExperimentalFoundationApi::class)
    @Test fun coverBlocksTapLongPressAndDragThroughExitThenReleasesContent() {
        var covered by mutableStateOf(true)
        var clicks = 0
        var longPresses = 0
        var travel = 0f
        compose.setContent {
            MaterialTheme {
                Box(Modifier.size(300.dp, 600.dp).testTag("body")) {
                    Box(Modifier.fillMaxSize()
                        .combinedClickable(onClick = { clicks++ }, onLongClick = { longPresses++ })
                        .draggable(rememberDraggableState { travel += it }, Orientation.Vertical))
                    ChatSwitchingOverlay(covered, false, 64.dp, 100.dp)
                }
            }
        }
        val body = compose.onNodeWithTag("body")
        body.performTouchInput { click(Offset(100f, 20f)); longClick(); swipeUp() }
        compose.runOnIdle {
            assertEquals(0, clicks)
            assertEquals(0, longPresses)
            assertEquals(0f, travel)
        }
        compose.mainClock.autoAdvance = false
        compose.runOnIdle { covered = false }
        compose.mainClock.advanceTimeBy(64)
        compose.onNode(progress).assertExists()
        body.performTouchInput { click() }
        compose.runOnIdle { assertEquals(0, clicks) }
        compose.mainClock.advanceTimeBy(240)
        compose.onNode(progress).assertDoesNotExist()
        compose.mainClock.autoAdvance = true
        body.performTouchInput { click(); longClick(); swipeUp() }
        compose.runOnIdle {
            assertEquals(1, clicks)
            assertEquals(1, longPresses)
            assertTrue(travel != 0f)
        }
    }

    @Test fun coverLetsAncestorDrawerSwipeThroughButStillBlocksCoveredContent() {
        var drawerTravel = 0f
        var contentTravel = 0f
        var clicks = 0
        compose.setContent {
            MaterialTheme {
                // The ancestor draggable stands in for the navigation drawer's open gesture.
                Box(Modifier.size(300.dp, 600.dp).testTag("body")
                    .draggable(rememberDraggableState { drawerTravel += it }, Orientation.Horizontal)) {
                    Box(Modifier.fillMaxSize()
                        .combinedClickable(onClick = { clicks++ })
                        .draggable(rememberDraggableState { contentTravel += it }, Orientation.Horizontal))
                    ChatSwitchingOverlay(true, false, 64.dp, 100.dp)
                }
            }
        }
        compose.onNodeWithTag("body").performTouchInput { click(); swipeRight() }
        compose.runOnIdle {
            assertEquals(0, clicks)
            assertEquals(0f, contentTravel)
            assertTrue(drawerTravel > 0f)
        }
    }

    @Test fun circleCentersBetweenLiveTopAndComposerBoundsAndNewChatHasNoCover() {
        var bottom by mutableStateOf(100.dp)
        var newChat by mutableStateOf(false)
        var density = 1f
        compose.setContent {
            density = LocalDensity.current.density
            MaterialTheme {
                Box(Modifier.size(300.dp, 600.dp).testTag("body")) {
                    ChatSwitchingOverlay(true, newChat, 64.dp, bottom)
                }
            }
        }
        val body = compose.onNodeWithTag("body").fetchSemanticsNode().boundsInRoot
        val first = compose.onNode(progress).fetchSemanticsNode().boundsInRoot
        assertEquals(body.center.x, first.center.x, 1f)
        assertEquals(body.center.y + (64f - 100f) * density / 2, first.center.y, 1f)
        assertEquals(48f * density, first.height, 1f)
        compose.runOnIdle { bottom = 260.dp }
        val withIme = compose.onNode(progress).fetchSemanticsNode().boundsInRoot
        assertEquals(first.center.y - 80f * density, withIme.center.y, 1f)
        compose.runOnIdle { newChat = true }
        compose.onNode(progress).assertDoesNotExist()
    }
}
