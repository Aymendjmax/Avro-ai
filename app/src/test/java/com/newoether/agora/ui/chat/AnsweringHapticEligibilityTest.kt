package com.newoether.agora.ui.chat

import com.newoether.agora.TopLevelPresentation
import com.newoether.agora.model.ChatMessage
import com.newoether.agora.model.MessageSegment
import com.newoether.agora.model.MessageStatus
import com.newoether.agora.model.Participant
import com.newoether.agora.viewmodel.ConversationGenerationSnapshot
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import android.os.Trace
import androidx.compose.runtime.AbstractApplier
import androidx.compose.runtime.BroadcastFrameClock
import androidx.compose.runtime.Composition
import androidx.compose.runtime.Recomposer
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshots.Snapshot
import com.newoether.agora.service.AppForegroundTracker
import com.newoether.agora.ui.common.AgoraHaptics
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals

class AnsweringHapticEligibilityTest {
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Test
    fun effectConvergesToCurrentSnapshotAcrossRapidNavigationAndLifecycleChanges() = runTest {
        mockkStatic(Trace::class)
        every { Trace.beginSection(any()) } answers { }
        every { Trace.endSection() } answers { }
        val snapshot = mutableStateOf(activeSnapshot())
        val page = mutableStateOf(TopLevelPresentation.CHAT)
        val enabled = mutableStateOf(true)
        val windowAvailable = mutableStateOf(true)
        val viewModel = mockk<com.newoether.agora.viewmodel.ChatViewModel>(relaxed = true)
        var accepted: ((String, String) -> Unit)? = null
        every { viewModel.onSendAccepted = any() } answers { accepted = firstArg() }
        var confirmations = 0
        var active = false
        val haptics = mockk<AgoraHaptics>()
        every { haptics.startAnsweringTexture() } answers { active = true }
        every { haptics.stopAnsweringTexture() } answers { active = false }
        every { haptics.confirm() } answers { confirmations++ }
        val clock = BroadcastFrameClock()
        val recomposer = Recomposer(backgroundScope.coroutineContext + clock)
        val composition = Composition(object : AbstractApplier<Unit>(Unit) {
            override fun insertTopDown(index: Int, instance: Unit) = Unit
            override fun insertBottomUp(index: Int, instance: Unit) = Unit
            override fun remove(index: Int, count: Int) = Unit
            override fun move(from: Int, to: Int, count: Int) = Unit
            override fun onClear() = Unit
        }, recomposer)
        backgroundScope.launch(clock) { recomposer.runRecomposeAndApplyChanges() }
        var frame = 0L
        fun settle(expected: Boolean) {
            repeat(2) {
                Snapshot.sendApplyNotifications()
                runCurrent()
                clock.sendFrame(++frame * 16_000_000)
                runCurrent()
            }
            assertEquals(expected, active)
        }
        AppForegroundTracker.setInForeground(true)
        try {
            composition.setContent {
                AnsweringHapticEffect(snapshot.value, page.value, enabled.value && windowAvailable.value, haptics)
                SendAcceptedHapticBindingEffect(viewModel, haptics,
                    windowAvailable.value && page.value == TopLevelPresentation.CHAT)
            }
            settle(true)
            accepted!!.invoke("conversation", "before-overlay")
            assertEquals(1, confirmations)
            // A sheet/menu owns the window; background confirmations are consumed silently.
            windowAvailable.value = false
            settle(false)
            accepted!!.invoke("conversation", "covered")
            assertEquals(1, confirmations)
            windowAvailable.value = true
            settle(true)
            assertEquals(1, confirmations)
            accepted!!.invoke("conversation", "after-overlay")
            assertEquals(2, confirmations)
            repeat(3) {
                snapshot.value = ConversationGenerationSnapshot(conversationId = "other")
                settle(false)
                snapshot.value = activeSnapshot()
                settle(true)
            }
            snapshot.value = ConversationGenerationSnapshot(conversationId = "other")
            snapshot.value = activeSnapshot()
            settle(true)
            TopLevelPresentation.entries.forEach { destination ->
                page.value = destination
                settle(destination == TopLevelPresentation.CHAT)
                val before = confirmations
                accepted!!.invoke("conversation", "accepted-on-$destination")
                assertEquals(before + if (destination == TopLevelPresentation.CHAT) 1 else 0, confirmations)
            }
            page.value = TopLevelPresentation.CHAT
            settle(true)
            enabled.value = false
            settle(false)
            enabled.value = true
            settle(true)
            AppForegroundTracker.setInForeground(false)
            settle(false)
            AppForegroundTracker.setInForeground(true)
            settle(true)
            snapshot.value = activeSnapshot().copy(streamingMessage = answer.copy(
                segments = answer.segments!! + MessageSegment(type = "citation", content = ""),
            ))
            settle(true)
            snapshot.value = activeSnapshot().copy(streamingMessage = answer.copy(status = MessageStatus.SUCCESS))
            settle(false)
            snapshot.value = activeSnapshot()
            settle(true)
        } finally {
            composition.dispose()
            assertFalse(active)
            assertEquals(null, accepted)
            recomposer.close()
            AppForegroundTracker.setInForeground(false)
            unmockkStatic(Trace::class)
        }
    }

    private val answer = ChatMessage(
        id = "answer",
        text = "hello",
        participant = Participant.MODEL,
        status = MessageStatus.SENDING,
        segments = listOf(MessageSegment(type = "answer", content = "hello")),
    )

    @Test
    fun ordinaryAnswerOnChatIsEligible() {
        assertTrue(answeringHapticEligible(activeSnapshot(), TopLevelPresentation.CHAT))
    }

    @Test
    fun citationMetadataDoesNotEndAnsweringButToolsAndThoughtsDo() {
        val citation = MessageSegment(type = "citation", content = "")
        fun eligible(tail: List<MessageSegment>) = answeringHapticEligible(
            activeSnapshot().copy(streamingMessage = answer.copy(segments = answer.segments!! + tail)),
            TopLevelPresentation.CHAT,
        )
        assertTrue(eligible(listOf(citation)))
        assertTrue(eligible(listOf(citation, MessageSegment(type = "answer", content = "more"), citation)))
        assertFalse(eligible(listOf(MessageSegment(type = "tool", toolName = "google_search"), citation)))
        assertFalse(eligible(listOf(MessageSegment(type = "thought", content = "thinking"), citation)))
    }

    @Test
    fun compactAndEveryBlockingPresentationAreIneligible() {
        assertFalse(
            answeringHapticEligible(
                activeSnapshot().copy(
                    streamingMessage = answer.copy(id = "compact_stream"),
                ),
                TopLevelPresentation.CHAT,
            ),
        )
        TopLevelPresentation.entries
            .filterNot { it == TopLevelPresentation.CHAT }
            .forEach { presentation ->
                assertFalse(answeringHapticEligible(activeSnapshot(), presentation))
            }
    }

    @Test
    fun inactiveSnapshotIsIneligible() {
        assertFalse(
            answeringHapticEligible(
                ConversationGenerationSnapshot(),
                TopLevelPresentation.CHAT,
            ),
        )
    }

    @Test
    fun nonAnswerSegmentsAndTerminalMessagesAreIneligible() {
        assertFalse(
            answeringHapticEligible(
                activeSnapshot().copy(
                    streamingMessage = answer.copy(
                        text = "",
                        segments = listOf(MessageSegment(type = "thought", content = "thinking")),
                    ),
                ),
                TopLevelPresentation.CHAT,
            ),
        )
        assertFalse(
            answeringHapticEligible(
                activeSnapshot().copy(
                    streamingMessage = answer.copy(status = MessageStatus.SUCCESS),
                ),
                TopLevelPresentation.CHAT,
            ),
        )
    }

    private fun activeSnapshot() = ConversationGenerationSnapshot(
        conversationId = "conversation",
        streamingMessage = answer,
        isLoading = true,
        isGenerating = true,
    )
}
