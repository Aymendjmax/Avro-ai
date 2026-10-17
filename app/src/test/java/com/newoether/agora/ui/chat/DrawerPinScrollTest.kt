package com.newoether.agora.ui.chat

import android.app.Application
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import com.newoether.agora.model.ChatConversation
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "w400dp-h800dp-mdpi")
class DrawerPinScrollTest {
    @get:Rule val compose = createComposeRule()

    private fun conversation(index: Int, pinned: Boolean = false) =
        ChatConversation(id = "c$index", title = "Chat $index", isPinned = pinned)

    @Test fun pinningScrollsDrawerToTopOnceListShowsThePin() {
        var conversations by mutableStateOf((0 until 60).map { conversation(it) })
        lateinit var state: LazyListState
        lateinit var requestPinScroll: (String) -> Unit
        compose.setContent {
            state = rememberLazyListState()
            val keys = drawerConversationKeys(conversations)
            requestPinScroll = rememberDrawerPinScroll(state, conversations, keys.size, false)
            Box(Modifier.size(300.dp, 400.dp)) {
                LazyColumn(state = state) {
                    items(keys, key = { it }) { Text(it, Modifier.fillMaxWidth().height(44.dp)) }
                }
            }
        }
        compose.runOnIdle { runBlocking { state.scrollToItem(40) } }
        compose.runOnIdle { assertEquals(40, state.firstVisibleItemIndex) }

        compose.runOnIdle {
            requestPinScroll("c45")
            conversations = conversations.map { if (it.id == "c45") it.copy(isPinned = true) else it }
        }
        // The feedback scroll advances once per frame, so drive frames on the test clock.
        compose.mainClock.advanceTimeBy(10_000)
        compose.runOnIdle {
            assertEquals(0, state.firstVisibleItemIndex)
            // animateToAbsoluteTop settles within its 1.5 px target tolerance.
            assertTrue(state.firstVisibleItemScrollOffset <= 2)
        }
    }
}
