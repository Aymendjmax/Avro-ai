package com.newoether.agora.viewmodel

import com.newoether.agora.data.PredefinedVariables
import com.newoether.agora.data.PromptItemType
import com.newoether.agora.data.PromptTemplateItem
import com.newoether.agora.model.ChatMessage
import com.newoether.agora.model.Participant
import com.newoether.agora.util.Constants
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import java.util.Calendar

class GenerationManagerUserTemplateTest {
    @Test
    fun applyUserTemplateToMessages_formatsSentDateWithEnglishWeekday() {
        val sentAt = Calendar.getInstance().apply {
            clear()
            set(2026, Calendar.MAY, 9, 12, 34, 56)
        }.timeInMillis
        val messages = listOf(
            ChatMessage(id = "u1", text = "hello", participant = Participant.USER, timestamp = sentAt),
        )

        val result = applyUserTemplateToMessages(
            messages,
            "<sent date=\"{sent_date}\" time=\"{sent_time}\">",
            null,
        )

        assertEquals("<sent date=\"2026-05-09 Sat\" time=\"12:34:56\">hello", result.single().text)
    }

    @Test
    fun applyUserTemplateToMessages_wrapsOnlyNormalUserMessages() {
        val messages = listOf(
            ChatMessage(id = "u1", text = "hello", participant = Participant.USER),
            ChatMessage(id = Constants.RESULT_MSG_PREFIX + "r1", text = "tool output", participant = Participant.USER),
            ChatMessage(id = Constants.TOOL_MSG_PREFIX + "t1", text = "", participant = Participant.MODEL),
            ChatMessage(id = "m1", text = "assistant", participant = Participant.MODEL)
        )

        val result = applyUserTemplateToMessages(messages, "<wrap>", "</wrap>")

        assertEquals("<wrap>hello</wrap>", result[0].text)
        assertEquals("tool output", result[1].text)
        assertEquals("", result[2].text)
        assertEquals("assistant", result[3].text)
    }

    @Test
    fun messageTemplatesWrapOnlyOrdinaryUserAndAssistantMessages() {
        val messages = listOf(
            ChatMessage(id = "user", text = "question", participant = Participant.USER),
            ChatMessage(id = "assistant", text = "answer", participant = Participant.MODEL),
            ChatMessage(
                id = Constants.TOOL_MSG_PREFIX + "tool",
                text = "call",
                participant = Participant.MODEL,
            ),
            ChatMessage(
                id = Constants.RESULT_MSG_PREFIX + "result",
                text = "result",
                participant = Participant.USER,
            ),
            ChatMessage(
                id = Constants.COMPACT_MSG_PREFIX + "compact",
                text = "summary",
                participant = Participant.MODEL,
            ),
            ChatMessage(
                id = "context_summary_compact",
                text = "context",
                participant = Participant.USER,
            ),
            ChatMessage(
                id = "api_initial_user_prompt",
                text = "initial",
                participant = Participant.USER,
            ),
            ChatMessage(
                id = "api_compact_continuation_prompt",
                text = "continue",
                participant = Participant.USER,
            ),
        )

        val result = applyMessageTemplatesToMessages(
            messages = messages,
            userPrepend = "<user>",
            userPostpend = "</user>",
            assistantPrepend = "<assistant>",
            assistantPostpend = "</assistant>",
        )

        assertEquals("<user>question</user>", result[0].text)
        assertEquals("<assistant>answer</assistant>", result[1].text)
        assertEquals(
            messages.drop(2).map(ChatMessage::text),
            result.drop(2).map(ChatMessage::text),
        )
    }

    @Test
    fun messageModelVariableResolvesFromEachOrdinaryMessage() {
        val messages = listOf(
            ChatMessage(
                id = "user",
                text = "question",
                participant = Participant.USER,
                modelName = null,
            ),
            ChatMessage(
                id = "assistant-old",
                text = "answer",
                participant = Participant.MODEL,
                modelName = "OpenAI:gpt-4.1",
            ),
        )

        val result = applyMessageTemplatesToMessages(
            messages = messages,
            userPrepend = "<model>{message_model_id}</model>",
            userPostpend = null,
            assistantPrepend = "<model>{message_model_id}</model>",
            assistantPostpend = null,
        )

        assertEquals("<model></model>question", result[0].text)
        assertEquals("<model>gpt-4.1</model>answer", result[1].text)
    }

    @Test
    fun requestModelVariablesResolveAtRequestScope() {
        val runtimeValues = buildPromptRuntimeValues(
            now = java.util.Date(0L),
            modelId = "gpt-5.6-sol",
            activeMemory = "memory",
            skillCatalog = "skills",
        )

        assertEquals("gpt-5.6-sol", runtimeValues[PredefinedVariables.CURRENT_MODEL_ID])
        assertEquals("gpt-5.6-sol", runtimeValues[PredefinedVariables.MODEL_ID])
        assertEquals("", runtimeValues[PredefinedVariables.MESSAGE_MODEL_ID])
        val requestValues = runtimeValues.filterKeys {
            it !in PredefinedVariables.PER_MESSAGE_VARS
        }
        assertFalse(requestValues.containsKey(PredefinedVariables.MESSAGE_MODEL_ID))
        val template = listOf(
            PromptTemplateItem(
                type = PromptItemType.PREDEFINED,
                value = PredefinedVariables.CURRENT_MODEL_ID,
            ),
            PromptTemplateItem(type = PromptItemType.CUSTOM, value = "|"),
            PromptTemplateItem(
                type = PromptItemType.PREDEFINED,
                value = PredefinedVariables.MODEL_ID,
            ),
            PromptTemplateItem(type = PromptItemType.CUSTOM, value = "|"),
            PromptTemplateItem(
                type = PromptItemType.PREDEFINED,
                value = PredefinedVariables.MESSAGE_MODEL_ID,
            ),
        )
        assertEquals(
            "gpt-5.6-sol|gpt-5.6-sol|{message_model_id}",
            PredefinedVariables.compile(template, requestValues, emptyMap()),
        )
    }

    @Test
    fun exactInputProjectionAppliesTemplatesAndRelocatesAssistantImagesOnce() {
        val messages = listOf(
            ChatMessage(
                id = "assistant-image",
                text = "generated",
                images = listOf("generated.png"),
                participant = Participant.MODEL,
            ),
            ChatMessage(id = "latest-user", text = "inspect", participant = Participant.USER),
        )

        val projected = projectGenerationInputMessages(
            messages = messages,
            includeImages = true,
            userPrepend = "<",
            userPostpend = ">",
        )

        assertEquals(emptyList<String>(), projected[0].images)
        assertEquals(listOf("generated.png"), projected[1].images)
        assertEquals(
            "<[Visual context: the first attached image was generated by the assistant earlier in this conversation.]\n\ninspect>",
            projected[1].text,
        )
    }

    @Test
    fun nonVisionProjectionRemovesEveryRawImageFromAccountingAndDispatch() {
        val messages = listOf(
            ChatMessage(
                id = "user",
                text = "described attachment",
                images = listOf("attachment.png"),
                participant = Participant.USER,
            ),
            ChatMessage(
                id = Constants.RESULT_MSG_PREFIX + "result",
                text = "tool result",
                images = listOf("tool.png"),
                participant = Participant.USER,
            ),
        )

        val projected = projectGenerationInputMessages(
            messages = messages,
            includeImages = false,
            userPrepend = null,
            userPostpend = null,
        )

        assertEquals(emptyList<String>(), projected.flatMap { it.images })
        assertEquals("described attachment", projected.first().text)
    }

    @Test
    fun compactInvocationIsAnApiOnlyFinalUserMessage() {
        val durableMessages = listOf(
            ChatMessage(id = "user", text = "question", participant = Participant.USER),
            ChatMessage(id = "assistant", text = "answer", participant = Participant.MODEL),
        )

        val projected = projectGenerationInputMessages(
            messages = durableMessages,
            includeImages = true,
            userPrepend = null,
            userPostpend = null,
            initialUserPrompt = "Create the compact context summary now.",
        )

        assertEquals(durableMessages, projected.dropLast(1))
        assertEquals(Participant.USER, projected.last().participant)
        assertEquals("Create the compact context summary now.", projected.last().text)
        assertEquals("assistant", projected.last().parentId)
    }
}
