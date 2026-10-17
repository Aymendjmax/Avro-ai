package com.newoether.agora.ui.chat.interaction

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import com.newoether.agora.viewmodel.AskUserController
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Sending a multi-question card makes it leave on the page it was on, and the lift it reports
 * follows it down to exactly zero.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class UserInteractionBarExitTest {
    @get:Rule val compose = createComposeRule()

    private fun question(id: Long) = AskUserController.Request(
        id = id,
        conversationId = "c",
        question = "Question $id?",
        options = listOf("A", "B"),
        allowMultiple = false,
        blocking = false,
    )

    @Test
    fun leavingCardKeepsItsPageAndItsLiftEndsAtZero() {
        val waiting = userInteractions("c", listOf(question(1), question(2)), null)
        var interactions by mutableStateOf(waiting)
        var pageIn by mutableStateOf(mapOf("c" to "question:2"))
        val lifts = mutableListOf<Float>()
        val gone = mutableListOf<String>()
        compose.setContent {
            UserInteractionBar(
                conversationId = "c",
                interactions = interactions,
                autoWrapCodeBlocks = false,
                onSubmitQuestions = {},
                onSkipQuestion = {},
                onShellDecision = { _, _, _ -> },
                onHeightChanged = { lifts += it },
                minimizedIn = emptySet(),
                onMinimizedChange = { _, _ -> },
                drafts = QuestionDrafts(),
                pageIn = pageIn,
                onPageChange = { owner, key -> pageIn = pageIn + (owner to key) },
                onCardGone = { owner ->
                    gone += owner
                    pageIn = pageIn - owner
                },
            )
        }
        compose.onNodeWithText("2 / 2").assertExists()
        val fullLift = lifts.last()
        assertTrue(fullLift > 0f)

        compose.mainClock.autoAdvance = false
        compose.runOnIdle { interactions = emptyList() }
        // Step frame by frame until the card is visibly on its way out.
        repeat(30) {
            if (lifts.last() > 0f && lifts.last() < fullLift) return@repeat
            compose.mainClock.advanceTimeByFrame()
            // The lift is reported from an effect coroutine on the main looper.
            compose.waitForIdle()
        }
        val midway = lifts.last()
        assertTrue("$lifts", midway > 0f && midway < fullLift)
        // Midway out: still the page it was sent from, and not yet reported gone.
        compose.onNodeWithText("2 / 2").assertExists()
        assertEquals(emptyList<String>(), gone)

        compose.mainClock.advanceTimeBy(1_000)
        compose.waitForIdle()
        val leaving = lifts.dropWhile { it < fullLift }
        assertEquals("$lifts", leaving, leaving.sortedDescending())
        assertEquals(0f, lifts.last())
        assertEquals(listOf("c"), gone)
    }
}
