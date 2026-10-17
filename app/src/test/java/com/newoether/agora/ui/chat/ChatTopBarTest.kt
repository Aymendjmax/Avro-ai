package com.newoether.agora.ui.chat

import android.app.Application
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertHeightIsEqualTo
import androidx.compose.ui.test.assertWidthIsEqualTo
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import com.newoether.agora.R
import com.newoether.agora.ui.motion.AgoraMotionPolicy
import com.newoether.agora.ui.motion.LocalAgoraMotionPolicy
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class ChatTopBarTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun menuGlyphIsCenteredOnTheVisible52DpCircleWithoutMovingTheHitTarget() {
        val resources = ApplicationProvider.getApplicationContext<Application>().resources
        compose.setContent {
            MaterialTheme {
                ChatTopBar(
                    isNewChatMode = true, conversations = emptyList(), currentConversationId = null,
                    totalTokens = 0, contextTokenBudget = 262144,
                    onOpenDrawer = {}, onSystemPromptClick = {}, onNewChat = {},
                )
            }
        }
        val button = compose.onNodeWithContentDescription(resources.getString(R.string.menu))
            .assertWidthIsEqualTo(44.dp).assertHeightIsEqualTo(44.dp).fetchSemanticsNode()
        val glyph = compose.onNodeWithContentDescription(resources.getString(R.string.menu), useUnmergedTree = true)
            .fetchSemanticsNode().boundsInRoot
        val density = compose.density.density
        val circleX = button.boundsInRoot.left - 5 * density + 26 * density
        assertEquals(circleX, glyph.center.x, .1f)
        assertEquals(button.boundsInRoot.center.y, glyph.center.y, .1f)
    }

    @Test
    fun newChatHasOnlyMenuAndReturningFromConversationRemovesTheTitle() {
        val resources = ApplicationProvider.getApplicationContext<Application>().resources
        var newChat by mutableStateOf(true)
        var reduced by mutableStateOf(false)
        var title by mutableStateOf<String?>("Conversation")
        var drawerOpens = 0
        compose.setContent {
            CompositionLocalProvider(LocalAgoraMotionPolicy provides AgoraMotionPolicy(reduced)) {
                MaterialTheme {
                    ChatTopBar(
                        isNewChatMode = newChat,
                        conversations = emptyList(), currentConversationId = if (newChat) null else "c",
                        currentConversationTitle = title, totalTokens = 100, contextTokenBudget = 262144,
                        onOpenDrawer = { drawerOpens++ }, onSystemPromptClick = {}, onNewChat = {},
                    )
                }
            }
        }
        compose.onNodeWithText("Agora").assertDoesNotExist()
        compose.onNodeWithText("Conversation").assertDoesNotExist()
        compose.onNodeWithContentDescription(resources.getString(R.string.menu)).performClick()
        assertEquals(1, drawerOpens)
        compose.runOnIdle { newChat = false }
        compose.onNodeWithText("Conversation").assertIsDisplayed()
        compose.runOnIdle { newChat = true }
        compose.onNodeWithText("Conversation").assertDoesNotExist()
        compose.onNodeWithText("Agora").assertDoesNotExist()
        compose.runOnIdle { reduced = true; newChat = false; title = null }
        compose.onNodeWithText("Agora").assertIsDisplayed()
        compose.runOnIdle { newChat = true }
        compose.onNodeWithText("Agora").assertDoesNotExist()
        compose.onNodeWithContentDescription(resources.getString(R.string.menu)).assertIsDisplayed()
        compose.onNodeWithContentDescription(resources.getString(R.string.new_chat)).assertIsDisplayed()
    }
}
