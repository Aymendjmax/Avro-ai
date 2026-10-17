package com.newoether.agora.tool

import com.newoether.agora.viewmodel.AskUserController
import com.newoether.agora.viewmodel.GenerationContext
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What the model gets back from `ask_user`, and what it leaves on screen.
 *
 * A single question and a set of questions are the same tool call with different arguments, so both
 * shapes are pinned here, along with the two ways a call can end without answers: a refused
 * argument combination and a stopped generation.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AskUserToolProviderTest {

    private val ctx = GenerationContext(askUserEnabled = true, conversationId = "c1")

    private fun body(text: String): JsonObject = Json.parseToJsonElement(text).jsonObject

    private fun choicesOf(entry: JsonObject): List<String> =
        entry["choices"]?.jsonArray?.map { it.jsonPrimitive.content }.orEmpty()

    @Test
    fun `a single question answers with the flat shape`() = runTest {
        val controller = AskUserController()
        val provider = AskUserToolProvider(controller)
        val result = async {
            provider.execute("ask_user", """{"question":"Which one?","options":["A","B"]}""", ctx)
        }
        runCurrent()

        val request = controller.requests.value.single()
        assertEquals("Which one?", request.question)
        controller.submit(request.id, listOf("B"))

        val answer = body(result.await())
        assertTrue(answer["answered"]!!.jsonPrimitive.boolean)
        assertEquals(listOf("B"), choicesOf(answer))
        assertNull(answer["answers"])
        assertTrue(controller.requests.value.isEmpty())
    }

    @Test
    fun `a set of questions reaches the bar together with its own options`() = runTest {
        val controller = AskUserController()
        val provider = AskUserToolProvider(controller)
        val result = async {
            provider.execute(
                "ask_user",
                """
                {"questions":[
                  {"question":"First?","options":["A"]},
                  {"question":"Second?","options":["B","C"],"allow_multiple":true}
                ]}
                """.trimIndent(),
                ctx,
            )
        }
        runCurrent()

        val waiting = controller.requests.value
        assertEquals(listOf("First?", "Second?"), waiting.map { it.question })
        assertFalse(waiting[0].allowMultiple)
        assertTrue(waiting[1].allowMultiple)
        // One call, one card: both questions carry the same set id.
        assertTrue(waiting[0].setId != null)
        assertEquals(waiting[0].setId, waiting[1].setId)

        // Answered out of order, because both cards are on screen at the same time.
        controller.submit(waiting[1].id, listOf("B", "C"))
        controller.submit(waiting[0].id, emptyList(), "my own words")

        val answers = body(result.await())["answers"]!!.jsonArray.map { it.jsonObject }
        assertEquals(listOf("First?", "Second?"), answers.map { it["question"]!!.jsonPrimitive.content })
        assertEquals("my own words", answers[0]["text"]?.jsonPrimitive?.content)
        assertEquals(listOf("B", "C"), choicesOf(answers[1]))
        assertTrue(controller.requests.value.isEmpty())
    }

    @Test
    fun `skipping one question of a set does not hide the others`() = runTest {
        val controller = AskUserController()
        val provider = AskUserToolProvider(controller)
        val result = async {
            provider.execute(
                "ask_user",
                """{"questions":[{"question":"First?"},{"question":"Second?","options":["B"]}]}""",
                ctx,
            )
        }
        runCurrent()

        val waiting = controller.requests.value
        controller.dismiss(waiting[0].id)
        controller.submit(waiting[1].id, listOf("B"))

        val answers = body(result.await())["answers"]!!.jsonArray.map { it.jsonObject }
        assertFalse(answers[0]["answered"]!!.jsonPrimitive.boolean)
        assertTrue(answers[1]["answered"]!!.jsonPrimitive.boolean)
        assertEquals(listOf("B"), choicesOf(answers[1]))
    }

    @Test
    fun `asking one question and a set in the same call is refused`() = runTest {
        val controller = AskUserController()
        val result = AskUserToolProvider(controller).execute(
            "ask_user",
            """{"question":"Which one?","questions":[{"question":"Or this one?"}]}""",
            ctx,
        )
        assertEquals("ambiguous_questions", body(result)["error"]?.jsonPrimitive?.content)
        assertTrue(controller.requests.value.isEmpty())
    }

    @Test
    fun `a call with no question at all is refused`() = runTest {
        val controller = AskUserController()
        val result = AskUserToolProvider(controller).execute("ask_user", "{}", ctx)
        assertEquals("no_question", body(result)["error"]?.jsonPrimitive?.content)
        assertTrue(controller.requests.value.isEmpty())
    }

    @Test
    fun `more questions than the bar can hold is refused before any of them appears`() = runTest {
        val controller = AskUserController()
        val questions = (1..6).joinToString(",") { """{"question":"Q$it?"}""" }
        val result = AskUserToolProvider(controller)
            .execute("ask_user", """{"questions":[$questions]}""", ctx)
        assertEquals("too_many_questions", body(result)["error"]?.jsonPrimitive?.content)
        assertTrue(controller.requests.value.isEmpty())
    }

    @Test
    fun `a non-blocking set returns at once and says how many are waiting`() = runTest {
        val controller = AskUserController()
        val result = AskUserToolProvider(controller).execute(
            "ask_user",
            """{"blocking":false,"questions":[{"question":"First?"},{"question":"Second?"}]}""",
            ctx,
        )
        val answer = body(result)
        assertEquals("queued", answer["delivery"]?.jsonPrimitive?.content)
        assertEquals(2, answer["questions"]!!.jsonPrimitive.int)
        assertEquals(2, controller.requests.value.size)
    }

    @Test
    fun `leaving blocking out waits for the answer`() = runTest {
        val controller = AskUserController()
        val call = async {
            AskUserToolProvider(controller).execute("ask_user", """{"question":"Now?"}""", ctx)
        }
        runCurrent()
        assertTrue(controller.requests.value.single().blocking)
        assertFalse(call.isCompleted)
        call.cancel()
    }

    @Test
    fun `stopping the generation leaves no question waiting on screen`() = runTest {
        val controller = AskUserController()
        val provider = AskUserToolProvider(controller)
        val call = launch {
            provider.execute(
                "ask_user",
                """{"questions":[{"question":"First?"},{"question":"Second?"}]}""",
                ctx,
            )
        }
        runCurrent()
        assertEquals(2, controller.requests.value.size)

        call.cancel()
        runCurrent()

        // Both cards belong to a wait that no longer exists, so neither may stay behind.
        assertTrue(controller.requests.value.isEmpty())
    }
}
