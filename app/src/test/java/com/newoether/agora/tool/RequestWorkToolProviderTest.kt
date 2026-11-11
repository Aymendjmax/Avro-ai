package com.newoether.agora.tool

import com.newoether.agora.model.ConversationMode
import com.newoether.agora.viewmodel.GenerationContext
import com.newoether.agora.viewmodel.RequestWorkController
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class RequestWorkToolProviderTest {

    private val askCtx = GenerationContext(
        conversationId = "conv_123",
        conversationMode = ConversationMode.ASK,
    )

    private val workCtx = GenerationContext(
        conversationId = "conv_123",
        conversationMode = ConversationMode.WORK,
    )

    @Test
    fun `definitions exposes request_work in ASK mode only`() {
        val controller = RequestWorkController()
        val provider = RequestWorkToolProvider(controller)

        val askDefs = provider.definitions(askCtx)
        assertEquals(1, askDefs.size)
        assertEquals("request_work", askDefs.first().function.name)
        assertTrue(askDefs.first().function.parameters.required.contains("reason"))

        val workDefs = provider.definitions(workCtx)
        assertTrue(workDefs.isEmpty())
    }

    @Test
    fun `handles matches request_work tool name`() {
        val controller = RequestWorkController()
        val provider = RequestWorkToolProvider(controller)

        assertTrue(provider.handles("request_work"))
        assertFalse(provider.handles("ask_user"))
        assertFalse(provider.handles("execute_shell_command"))
    }

    @Test
    fun `execute publishes pending request and completes on approval`() = runTest {
        val controller = RequestWorkController()
        val provider = RequestWorkToolProvider(controller)

        val execution = async {
            provider.execute(
                "request_work",
                """{"reason":"Need terminal to run python script"}""",
                askCtx,
            )
        }
        runCurrent()

        val pending = controller.pendingWorkRequest.value
        assertNotNull(pending)
        assertEquals("conv_123", pending!!.conversationId)
        assertEquals("Need terminal to run python script", pending.reason)

        controller.resolve(pending.id, allow = true)
        runCurrent()

        val resultJson = Json.parseToJsonElement(execution.await()) as JsonObject
        assertEquals("accepted", resultJson["status"]?.jsonPrimitive?.content)
        assertNull(controller.pendingWorkRequest.value)
    }

    @Test
    fun `execute publishes pending request and completes on decline`() = runTest {
        val controller = RequestWorkController()
        val provider = RequestWorkToolProvider(controller)

        val execution = async {
            provider.execute(
                "request_work",
                """{"reason":"Need root permissions to build packages"}""",
                askCtx,
            )
        }
        runCurrent()

        val pending = controller.pendingWorkRequest.value
        assertNotNull(pending)
        controller.resolve(pending.id, allow = false)
        runCurrent()

        val resultJson = Json.parseToJsonElement(execution.await()) as JsonObject
        assertEquals("declined", resultJson["status"]?.jsonPrimitive?.content)
        assertNull(controller.pendingWorkRequest.value)

        // Same turn subsequent call should immediately decline without suspending
        val secondResult = provider.execute(
            "request_work",
            """{"reason":"Still want to run scripts"}""",
            askCtx,
        )
        val secondJson = Json.parseToJsonElement(secondResult) as JsonObject
        assertEquals("declined", secondJson["status"]?.jsonPrimitive?.content)
        assertNull(controller.pendingWorkRequest.value)

        // After turn reset, it can request again
        controller.resetTurn("conv_123")
        val thirdExecution = async {
            provider.execute(
                "request_work",
                """{"reason":"New turn reason"}""",
                askCtx,
            )
        }
        runCurrent()
        assertNotNull(controller.pendingWorkRequest.value)
        controller.resolve(controller.pendingWorkRequest.value!!.id, allow = true)
        val thirdJson = Json.parseToJsonElement(thirdExecution.await()) as JsonObject
        assertEquals("accepted", thirdJson["status"]?.jsonPrimitive?.content)
    }
}
