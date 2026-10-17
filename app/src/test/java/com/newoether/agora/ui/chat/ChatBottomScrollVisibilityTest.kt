package com.newoether.agora.ui.chat

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class ChatBottomScrollVisibilityTest {
    @get:Rule val compose = createComposeRule()

    @Test fun directionLatchAccumulatesThresholdAndRestartsOnReversal() {
        val latch = ScrollDirectionLatch(32f)
        assertFalse(latch.headingToTail)
        latch.onDelta(31f)
        assertFalse(latch.headingToTail)
        latch.onDelta(-1f)
        latch.onDelta(31f)
        assertFalse(latch.headingToTail)
        latch.onDelta(1f)
        assertTrue(latch.headingToTail)
        latch.onDelta(-31f)
        assertTrue(latch.headingToTail)
        latch.onDelta(-1f)
        assertFalse(latch.headingToTail)
    }

    @Test fun canonicalOwnerStartsHiddenLatchesAndRespectsEveryScrollExclusion() {
        var owner by mutableStateOf("a")
        var loaded by mutableStateOf<String?>("a")
        var newChat by mutableStateOf(false)
        var switching by mutableStateOf(false)
        var share by mutableStateOf(false)
        var nearBottom by mutableStateOf(false)
        var phase by mutableStateOf(AbsoluteBottomScrollPhase.IDLE)
        var send by mutableStateOf(false)
        var ime by mutableStateOf(false)
        val tail = StreamingTailController()
        lateinit var list: LazyListState
        lateinit var scope: CoroutineScope
        lateinit var visible: State<Boolean>
        var threshold = 32
        compose.setContent {
            threshold = with(LocalDensity.current) { 32.dp.roundToPx() }
            list = rememberLazyListState(initialFirstVisibleItemIndex = 1)
            scope = rememberCoroutineScope()
            visible = rememberAbsoluteBottomButtonVisible(owner, loaded, newChat, switching, share,
                nearBottom, phase, list, tail, send, ime)
            LazyColumn(state = list, modifier = Modifier.height(200.dp)) {
                items(20) { Box(Modifier.height(300.dp)) { Text("row $it") } }
            }
            Text("visible=${visible.value}")
        }
        compose.runOnIdle { assertFalse(visible.value) }
        compose.runOnIdle { scope.launch { list.scrollToItem(1, threshold - 1) } }
        compose.runOnIdle { assertFalse(visible.value) }
        compose.runOnIdle { scope.launch { list.scrollToItem(1, threshold) } }
        compose.runOnIdle { assertTrue(visible.value) }
        val exclusions = listOf<() -> Unit>(
            { loaded = null }, { newChat = true }, { switching = true }, { share = true },
            { nearBottom = true }, { phase = AbsoluteBottomScrollPhase.SEEKING },
            { tail.isAutoFollowing = true }, { send = true }, { ime = true },
        )
        exclusions.forEach { exclude ->
            compose.runOnIdle { exclude() }
            compose.runOnIdle { assertFalse(visible.value) }
            compose.runOnIdle {
                loaded = owner; newChat = false; switching = false; share = false; nearBottom = false
                phase = AbsoluteBottomScrollPhase.IDLE; tail.isAutoFollowing = false; send = false; ime = false
            }
            compose.runOnIdle { assertTrue(visible.value) }
        }
        compose.runOnIdle { scope.launch { list.scrollToItem(1, 1) } }
        compose.runOnIdle { assertTrue(visible.value) }
        compose.runOnIdle { scope.launch { list.scrollToItem(1, 0) } }
        compose.runOnIdle { assertFalse(visible.value) }
        compose.runOnIdle { scope.launch { list.scrollToItem(2) } }
        compose.runOnIdle { assertTrue(visible.value) }
        compose.runOnIdle { owner = "b"; loaded = "b" }
        compose.runOnIdle { assertFalse(visible.value) }
    }
}
