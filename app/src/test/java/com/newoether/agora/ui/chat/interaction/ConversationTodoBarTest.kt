package com.newoether.agora.ui.chat.interaction

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertHasNoClickAction
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Density
import com.newoether.agora.model.ConversationTodoItem
import com.newoether.agora.ui.motion.AgoraMotionPolicy
import com.newoether.agora.ui.motion.LocalAgoraMotionPolicy
import com.newoether.agora.ui.theme.ChatType
import com.newoether.agora.ui.theme.Typography
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ConversationTodoBarTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun capsuleIsCenteredAndReadOnlySheetClosesOnClearOrOwnerChange() {
        var owner by mutableStateOf("c")
        val tasks = listOf(ConversationTodoItem("Literal **task**", "completed"), ConversationTodoItem("Next"))
        var items by mutableStateOf(tasks)
        var height = 0f
        compose.setContent {
            MaterialTheme {
                Box(Modifier.fillMaxSize()) {
                    ConversationTodoBar(owner, items, onHeightChanged = { height = it })
                }
            }
        }
        val title = compose.onNodeWithText("Todo")
        title.assertExists()
        assertTrue(height > 0f)
        val capsule = title.fetchSemanticsNode().boundsInRoot
        val root = compose.onRoot().fetchSemanticsNode().boundsInRoot
        assertEquals(root.center.x, capsule.center.x, 1f)
        title.performClick()
        compose.onNodeWithText("Literal **task**").assertExists().assertHasNoClickAction()
        compose.runOnIdle { items = emptyList() }
        compose.onNodeWithText("Literal **task**").assertDoesNotExist()
        assertEquals(0f, height)
        compose.runOnIdle { items = tasks }
        compose.onNodeWithText("Literal **task**").assertDoesNotExist()
        compose.onNodeWithText("Todo").performClick()
        compose.onNodeWithText("Literal **task**").assertExists()
        compose.runOnIdle { owner = "other" }
        compose.onNodeWithText("Literal **task**").assertDoesNotExist()
    }

    @Test
    fun typographyMatchesInteractionCapsuleAndThinkingDetail() {
        showTodo(reduced = true)
        val capsuleTitle = compose.onNodeWithText("Todo", useUnmergedTree = true).textStyle()
        assertTypography(Typography.titleSmall.copy(fontWeight = FontWeight.Bold), capsuleTitle)
        assertTypography(Typography.labelMedium,
            compose.onNodeWithText("0 / 1", useUnmergedTree = true).textStyle())

        compose.onNodeWithText("Todo").performClick()
        val titles = compose.onAllNodesWithText("Todo", useUnmergedTree = true)
        assertTypography(ChatType.detailTitle, titles[1].textStyle())
        val counts = compose.onAllNodesWithText("0 / 1", useUnmergedTree = true)
        assertTypography(Typography.labelMedium, counts[1].textStyle())
        assertTypography(Typography.bodyMedium,
            compose.onNodeWithText("Short task", useUnmergedTree = true).textStyle())
    }

    @Test
    fun blankBodyDragsSharedSheetWithMotion() = assertBlankBodyDrags(reduced = false)

    @Test
    fun blankBodyDragsSharedSheetWithReducedMotion() = assertBlankBodyDrags(reduced = true)

    private fun assertBlankBodyDrags(reduced: Boolean) {
        showTodo(reduced)
        compose.onNodeWithText("Todo").performClick()
        val list = compose.onNode(hasScrollAction())
        val partialHeight = list.fetchSemanticsNode().boundsInRoot.height
        val dialog = compose.onNode(isDialog())
        dialog.performTouchInput {
            swipe(Offset(centerX, height - 40f), Offset(centerX, height * 0.3f), 500)
        }
        val expandedHeight = list.fetchSemanticsNode().boundsInRoot.height
        assertTrue("Dragging blank space expands the shared sheet", expandedHeight > partialHeight * 1.5f)
        compose.onNodeWithText("Short task").assertExists()
        dialog.performTouchInput {
            swipe(Offset(centerX, height * 0.6f), Offset(centerX, height - 40f), 500)
        }
        assertEquals(partialHeight, list.fetchSemanticsNode().boundsInRoot.height, 2f)
        dialog.performTouchInput {
            swipe(Offset(centerX, height * 0.75f), Offset(centerX, height - 16f), 500)
        }
        compose.onNodeWithText("Short task").assertDoesNotExist()
        compose.onNodeWithText("Todo").assertExists()
    }

    @Test
    fun ownerSwitchRetainsDisabledOldCapsuleThenEntersOnlyLatestOwner() {
        var owner by mutableStateOf("a")
        var items by mutableStateOf(listOf(ConversationTodoItem("A", "completed")))
        var height = 0f
        compose.setContent {
            MaterialTheme {
                ConversationTodoBar(owner, items, { height = it })
            }
        }
        compose.runOnIdle { }
        val fullHeight = height
        assertTrue(fullHeight > 0f)
        compose.mainClock.autoAdvance = false
        compose.runOnIdle {
            owner = "b"
            items = listOf(ConversationTodoItem("B"), ConversationTodoItem("B2"))
        }
        compose.mainClock.advanceTimeByFrame()
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(64)
        compose.waitForIdle()
        compose.onNodeWithText("1 / 1").assertExists().assertIsNotEnabled()
        compose.onNodeWithText("0 / 2").assertDoesNotExist()
        assertTrue("Outgoing lift is animated: $height", height > 0f && height < fullHeight)
        compose.runOnIdle {
            owner = "c"
            items = listOf(ConversationTodoItem("C"), ConversationTodoItem("C2"), ConversationTodoItem("C3"))
        }
        compose.mainClock.advanceTimeBy(1_000)
        compose.waitForIdle()
        compose.onNodeWithText("0 / 2").assertDoesNotExist()
        compose.onNodeWithText("0 / 3").assertExists()
        assertEquals(fullHeight, height, 0.1f)
        compose.runOnIdle { items = items.map { it.copy(status = "completed") } }
        compose.mainClock.advanceTimeByFrame()
        compose.waitForIdle()
        compose.mainClock.advanceTimeByFrame()
        compose.waitForIdle()
        compose.onNodeWithText("3 / 3").assertExists()
        assertEquals("Same-owner updates do not replay entrance", fullHeight, height, 0.1f)
    }

    @Test
    fun clearRetainsCapsuleUntilFadeEndsButClosesSheetImmediately() {
        var items by mutableStateOf(listOf(ConversationTodoItem("Short task")))
        var height = 0f
        compose.setContent { MaterialTheme { ConversationTodoBar("c", items, { height = it }) } }
        compose.onNodeWithText("Todo").performClick()
        compose.onNodeWithText("Short task").assertExists()
        val fullHeight = height
        compose.mainClock.autoAdvance = false
        compose.runOnIdle { items = emptyList() }
        compose.mainClock.advanceTimeByFrame()
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(64)
        compose.waitForIdle()
        compose.onNodeWithText("Short task").assertDoesNotExist()
        compose.onNodeWithText("Todo").assertExists().assertIsNotEnabled()
        assertTrue(height > 0f && height < fullHeight)
        compose.mainClock.advanceTimeBy(1_000)
        compose.waitForIdle()
        compose.onNodeWithText("Todo").assertDoesNotExist()
        assertEquals(0f, height)
    }

    @Test
    fun reducedMotionKeepsFadeLifetimeButSnapsLiftAndRapidRepopulationStaysClosed() {
        val tasks = listOf(ConversationTodoItem("Short task"))
        var items by mutableStateOf(tasks)
        var height = 0f
        compose.setContent {
            CompositionLocalProvider(LocalAgoraMotionPolicy provides AgoraMotionPolicy(true)) {
                MaterialTheme { ConversationTodoBar("c", items, { height = it }) }
            }
        }
        compose.onNodeWithText("Todo").performClick()
        compose.mainClock.autoAdvance = false
        compose.runOnIdle { items = emptyList() }
        compose.mainClock.advanceTimeByFrame()
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(64)
        compose.waitForIdle()
        compose.onNodeWithText("Todo").assertExists().assertIsNotEnabled()
        compose.onNodeWithText("Short task").assertDoesNotExist()
        assertEquals(0f, height)
        compose.runOnIdle { items = tasks }
        compose.mainClock.advanceTimeBy(1_000)
        compose.waitForIdle()
        compose.onNodeWithText("Todo").assertExists()
        compose.onNodeWithText("Short task").assertDoesNotExist()
        assertTrue(height > 0f)
    }

    @Test
    fun iconsAlignWithFirstLineAtNormalFontScale() = assertFirstLineAlignment(1f)

    @Test
    fun iconsAlignWithFirstLineAtLargeFontScale() = assertFirstLineAlignment(1.8f)

    private fun assertFirstLineAlignment(fontScale: Float) {
        val tasks = listOf(
            ConversationTodoItem("Short task", "pending"),
            ConversationTodoItem("First line\nSecond line", "in_progress"),
            ConversationTodoItem("A naturally wrapped task ".repeat(8), "completed"),
        )
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(
                LocalDensity provides Density(density.density, fontScale),
                LocalAgoraMotionPolicy provides AgoraMotionPolicy(true),
            ) {
                MaterialTheme(typography = Typography) { ConversationTodoBar("c", tasks, {}) }
            }
        }
        compose.onNodeWithText("Todo").performClick()
        tasks.zip(listOf("Pending", "In progress", "Completed")).forEach { (task, status) ->
            compose.onNode(hasScrollAction()).performScrollToIndex(tasks.indexOf(task))
            val text = compose.onNodeWithText(task.content, useUnmergedTree = true)
            val layout = text.textLayout()
            val firstCenter = text.fetchSemanticsNode().boundsInRoot.top +
                (layout.getLineTop(0) + layout.getLineBottom(0)) / 2f
            val icon = compose.onNodeWithContentDescription(status, useUnmergedTree = true)
                .fetchSemanticsNode().boundsInRoot
            assertEquals("$status at font scale $fontScale", firstCenter, icon.center.y, 1f)
            if (task != tasks.first()) {
                assertTrue(
                    "Fixture must wrap: status=$status, scale=$fontScale, " +
                        "size=${layout.size}, input=${layout.layoutInput.constraints}",
                    layout.lineCount > 1,
                )
            }
        }
    }

    private fun showTodo(reduced: Boolean) {
        compose.setContent {
            CompositionLocalProvider(LocalAgoraMotionPolicy provides AgoraMotionPolicy(reduced)) {
                MaterialTheme(typography = Typography) {
                    Box(Modifier.fillMaxSize()) {
                        ConversationTodoBar("c", listOf(ConversationTodoItem("Short task")), {})
                    }
                }
            }
        }
    }

    private fun SemanticsNodeInteraction.textStyle(): TextStyle = textLayout().layoutInput.style

    private fun SemanticsNodeInteraction.textLayout(): TextLayoutResult {
        val layouts = mutableListOf<TextLayoutResult>()
        performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        return layouts.single()
    }

    private fun assertTypography(expected: TextStyle, actual: TextStyle) {
        assertEquals(expected.fontFamily, actual.fontFamily)
        assertEquals(expected.fontSize, actual.fontSize)
        assertEquals(expected.lineHeight, actual.lineHeight)
        assertEquals(expected.fontWeight, actual.fontWeight)
    }
}
