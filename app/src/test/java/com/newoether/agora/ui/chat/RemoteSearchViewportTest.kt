package com.newoether.agora.ui.chat

import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import com.newoether.agora.model.ChatMessage
import com.newoether.agora.model.Participant
import com.newoether.agora.model.StableMessageList
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class RemoteSearchViewportTest {
    @get:Rule val compose = createComposeRule()

    @Test fun pagesDoNotPullTheActualMessageListBackAfterTheReaderMovesAway() {
        var rows by mutableStateOf((0..24).map { index ->
            ChatMessage(id = "row-$index", text = "needle row $index\n".repeat(4),
                participant = Participant.USER, displayPageId = "current")
        })
        val results = MutableStateFlow(listOf(ConversationSearchMatch("row-8", 0, 6, 0)))
        lateinit var state: LazyListState
        lateinit var interaction: ConversationInteractionProjection
        lateinit var scope: CoroutineScope
        compose.setContent {
            state = rememberLazyListState()
            scope = rememberCoroutineScope()
            val messageState = rememberUpdatedState(rows)
            val all = remember { { _: String -> results } }
            interaction = rememberConversationInteractionState("owner", messageState, state,
                searchAllMessages = all)
            MaterialTheme {
                MessageList(
                    messages = StableMessageList(rows),
                    conversationId = "owner",
                    modifier = Modifier.width(360.dp).height(500.dp),
                    state = state,
                    viewportHeight = 500,
                    initialMessage = { id -> rows.firstOrNull { it.id == id } },
                    observeMessage = { id -> flowOf(rows.firstOrNull { it.id == id }) },
                    searchQuery = interaction.searchQuery,
                    activeSearchMatch = interaction.searchMatches.getOrNull(interaction.searchMatchIndex),
                    searchScrollRequestKey = interaction.searchScrollRequestKey,
                    onSearchMatchDistance = interaction::recordSearchMatchDistance,
                    onSearchTurnsChanged = interaction::recordSearchTurns,
                )
            }
        }
        compose.runOnIdle {
            interaction.activateSearch()
            interaction.updateSearchQuery("needle")
        }
        compose.mainClock.advanceTimeBy(1500)
        // Search's glyph timeout also uses the Android main looper under Robolectric.
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idleFor(java.time.Duration.ofSeconds(1))
        compose.mainClock.advanceTimeBy(1500)
        compose.waitForIdle()
        compose.runOnIdle {
            assertNotNull("Search did not settle: active=${interaction.searchActive}, query=${interaction.searchQuery}, matches=${interaction.searchMatches.size}, index=${interaction.searchMatchIndex}", interaction.searchScrollRequestKey)
        }
        compose.mainClock.advanceTimeBy(4000)
        compose.waitForIdle()
        compose.runOnIdle { scope.launch { state.scrollToItem(18, 20) } }
        compose.waitForIdle()
        var anchor: Any? = null
        var offset = 0
        compose.runOnIdle {
            val first = state.layoutInfo.visibleItemsInfo.first()
            anchor = first.key
            offset = first.offset
        }
        repeat(3) { page ->
            compose.runOnIdle {
                val older = ChatMessage(id = "older-$page", text = "needle older",
                    participant = Participant.USER, displayPageId = "page-$page")
                rows = listOf(older) + rows
                results.value = listOf(ConversationSearchMatch(older.id, 0, 6, 0)) + results.value
            }
            compose.mainClock.advanceTimeBy(1000)
            compose.waitForIdle()
            compose.runOnIdle {
                val first = state.layoutInfo.visibleItemsInfo.first()
                assertEquals("Visible message changed after page $page", anchor, first.key)
                assertEquals("Visible message moved after page $page", offset, first.offset)
            }
        }
    }
}
