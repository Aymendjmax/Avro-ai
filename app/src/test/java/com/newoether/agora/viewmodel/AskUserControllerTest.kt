package com.newoether.agora.viewmodel

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AskUserControllerTest {

    @Test
    fun `an answer resolves the waiting call and clears the request`() = runTest {
        val controller = AskUserController()
        val request = controller.open(
            conversationId = "c1",
            question = "Which one?",
            options = listOf("A", "B"),
            allowMultiple = false,
            blocking = true,
        )
        val answer = async { controller.awaitAnswer(request) }
        runCurrent()
        assertEquals(listOf(request), controller.requests.value)

        controller.submit(request.id, listOf("B"))

        assertEquals(listOf("B"), answer.await().choices)
        assertTrue(answer.await().answered)
        assertTrue(controller.requests.value.isEmpty())
    }

    @Test
    fun `a typed answer reaches the waiting call next to the picked options`() = runTest {
        val controller = AskUserController()
        val request = controller.open("c1", "Which one?", listOf("A"), allowMultiple = false, blocking = true)
        val answer = async { controller.awaitAnswer(request) }
        runCurrent()

        controller.submit(request.id, listOf("A"), "and also this")

        assertEquals(listOf("A"), answer.await().choices)
        assertEquals("and also this", answer.await().text)
    }

    @Test
    fun `an open question is answered by text alone`() = runTest {
        val controller = AskUserController()
        val request = controller.open("c1", "What should it be called?", emptyList(), allowMultiple = false, blocking = true)
        val answer = async { controller.awaitAnswer(request) }
        runCurrent()

        controller.submit(request.id, emptyList(), "Agora")

        assertTrue(answer.await().choices.isEmpty())
        assertEquals("Agora", answer.await().text)
        assertTrue(answer.await().answered)
    }

    @Test
    fun `blank text is dropped rather than reported as an answer`() = runTest {
        val controller = AskUserController()
        val request = controller.open("c1", "Which one?", listOf("A"), allowMultiple = false, blocking = true)
        val answer = async { controller.awaitAnswer(request) }
        runCurrent()

        controller.submit(request.id, listOf("A"), "   ")

        assertNull(answer.await().text)
    }

    @Test
    fun `skipping reports no answer instead of an empty choice`() = runTest {
        val controller = AskUserController()
        val request = controller.open("c1", "Which one?", listOf("A"), allowMultiple = false, blocking = true)
        val answer = async { controller.awaitAnswer(request) }
        runCurrent()

        controller.dismiss(request.id)

        assertFalse(answer.await().answered)
        assertTrue(answer.await().choices.isEmpty())
        assertTrue(controller.requests.value.isEmpty())
    }

    @Test
    fun `a question waits indefinitely because no timeout may answer it`() = runTest {
        val controller = AskUserController()
        val request = controller.open("c1", "Which one?", listOf("A", "B"), allowMultiple = false, blocking = true)
        val answer = async { controller.awaitAnswer(request) }
        runCurrent()

        advanceTimeBy(24L * 60 * 60 * 1_000)
        runCurrent()
        assertFalse(answer.isCompleted)
        assertEquals(listOf(request), controller.requests.value)

        controller.submit(request.id, listOf("A"))
        assertEquals(listOf("A"), answer.await().choices)
    }

    @Test
    fun `an answer carrying a stale request id decides nothing`() = runTest {
        val controller = AskUserController()
        val first = controller.open("c1", "First?", listOf("A"), allowMultiple = false, blocking = true)
        val firstAnswer = async { controller.awaitAnswer(first) }
        runCurrent()
        controller.submit(first.id, listOf("A"))
        firstAnswer.await()

        val second = controller.open("c1", "Second?", listOf("B"), allowMultiple = false, blocking = true)
        val secondAnswer = async { controller.awaitAnswer(second) }
        runCurrent()

        controller.submit(first.id, listOf("A"))
        runCurrent()
        assertFalse(secondAnswer.isCompleted)
        assertEquals(listOf(second), controller.requests.value)

        controller.submit(second.id, listOf("B"))
        assertEquals(listOf("B"), secondAnswer.await().choices)
    }

    @Test
    fun `requests keep the order they were asked in and stay addressable by id`() = runTest {
        val controller = AskUserController()
        val first = controller.open("c1", "First?", listOf("A"), allowMultiple = false, blocking = true)
        val second = controller.open("c2", "Second?", listOf("B", "C"), allowMultiple = true, blocking = true)

        assertEquals(listOf(first, second), controller.requests.value)
        assertEquals(second, controller.requestById(second.id))
        assertEquals(listOf("B", "C"), controller.requestById(second.id)?.options)

        controller.dismiss(first.id)
        assertEquals(listOf(second), controller.requests.value)
        assertEquals(null, controller.requestById(first.id))
    }

    @Test
    fun `a non-blocking answer is published for the send queue instead of resuming a caller`() = runTest {
        val controller = AskUserController()
        val delivered = mutableListOf<AskUserController.DeferredAnswer>()
        val collector = async { controller.deferredAnswers.collect { delivered += it } }
        runCurrent()
        val request = controller.open(
            conversationId = "c1",
            question = "Which one?",
            options = listOf("A", "B"),
            allowMultiple = false,
            blocking = false,
        )
        assertEquals(listOf(request), controller.requests.value)

        controller.submit(request.id, listOf("B"), "because of this")
        runCurrent()

        assertEquals(1, delivered.size)
        assertEquals("c1", delivered.single().conversationId)
        // The question travels with the answer: the message lands turns after it was asked.
        assertEquals("Which one?\nB\nbecause of this", delivered.single().text)
        assertTrue(controller.requests.value.isEmpty())
        collector.cancel()
    }

    @Test
    fun `skipping a non-blocking question sends nothing`() = runTest {
        val controller = AskUserController()
        val delivered = mutableListOf<AskUserController.DeferredAnswer>()
        val collector = async { controller.deferredAnswers.collect { delivered += it } }
        runCurrent()
        val request = controller.open("c1", "Which one?", listOf("A"), allowMultiple = false, blocking = false)

        controller.dismiss(request.id)
        runCurrent()

        assertTrue(delivered.isEmpty())
        assertTrue(controller.requests.value.isEmpty())
        collector.cancel()
    }

    @Test
    fun `one send of several non-blocking questions is one message naming the blank ones`() = runTest {
        val controller = AskUserController()
        val delivered = mutableListOf<AskUserController.DeferredAnswer>()
        val collector = async { controller.deferredAnswers.collect { delivered += it } }
        runCurrent()
        val first = controller.open("c1", "First?", listOf("A", "B"), allowMultiple = true, blocking = false)
        val second = controller.open("c1", "Second?", emptyList(), allowMultiple = false, blocking = false)
        val third = controller.open("c1", "Third?", listOf("C"), allowMultiple = false, blocking = false)

        controller.submitAll(
            listOf(
                first.id to AskUserController.Answer(listOf("A", "B"), answered = true),
                second.id to AskUserController.Answer.Unanswered,
                third.id to AskUserController.Answer(emptyList(), "typed", answered = true),
            ),
        )
        runCurrent()

        assertEquals(1, delivered.size)
        assertEquals(
            "First?\nA, B\n\nSecond?\n${com.newoether.agora.model.MessageSource.NO_ANSWER}\n\nThird?\ntyped",
            delivered.single().text,
        )
        assertTrue(controller.requests.value.isEmpty())
        collector.cancel()
    }

    @Test
    fun `one send removes the whole set in a single update`() = runTest {
        val controller = AskUserController()
        val first = controller.open("c1", "First?", listOf("A"), allowMultiple = false, blocking = false)
        val second = controller.open("c1", "Second?", listOf("B"), allowMultiple = false, blocking = false)
        val third = controller.open("c1", "Third?", listOf("C"), allowMultiple = false, blocking = false)
        val sizes = mutableListOf<Int>()
        val observer = launch(
            kotlinx.coroutines.test.UnconfinedTestDispatcher(testScheduler),
        ) { controller.requests.collect { sizes += it.size } }

        controller.submitAll(
            listOf(first, second, third).map { it.id to AskUserController.Answer(listOf("x"), answered = true) },
        )

        // Unconfined collection sees every emission: the card goes from three to none directly.
        assertEquals(listOf(3, 0), sizes)
        observer.cancel()
    }

    @Test
    fun `one send resumes blocking callers and still sends nothing for an all-blank set`() = runTest {
        val controller = AskUserController()
        val delivered = mutableListOf<AskUserController.DeferredAnswer>()
        val collector = async { controller.deferredAnswers.collect { delivered += it } }
        runCurrent()
        val blocking = controller.open("c1", "Wait?", listOf("Y"), allowMultiple = false, blocking = true)
        val waiting = async { controller.awaitAnswer(blocking) }
        val queued = controller.open("c1", "Later?", listOf("Z"), allowMultiple = false, blocking = false)
        runCurrent()

        controller.submitAll(
            listOf(
                blocking.id to AskUserController.Answer(listOf("Y"), answered = true),
                queued.id to AskUserController.Answer.Unanswered,
            ),
        )
        runCurrent()

        assertEquals(listOf("Y"), waiting.await().choices)
        assertTrue(delivered.isEmpty())
        assertTrue(controller.requests.value.isEmpty())
        collector.cancel()
    }
}
