package com.newoether.agora.viewmodel

import com.newoether.agora.readLocaleStringResourceSources
import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CompactI18nSourceContractTest {
    @Test
    fun `compact domain emits semantic failures and both consumers share one localized resolver`() {
        val model = sourceFile("app/src/main/java/com/newoether/agora/viewmodel/ContextCompactor.kt")
        val controller = sourceFile("app/src/main/java/com/newoether/agora/viewmodel/ConversationCompactController.kt")
        val compactUi = sourceFile("app/src/main/java/com/newoether/agora/viewmodel/ConversationCompactUiCoordinator.kt")
        val chat = sourceFile("app/src/main/java/com/newoether/agora/viewmodel/ChatViewModel.kt")
        val generation = sourceFile("app/src/main/java/com/newoether/agora/viewmodel/MessageGenerationController.kt")
        val presentation = sourceFile("app/src/main/java/com/newoether/agora/viewmodel/CompactFailurePresentation.kt")

        assertTrue(model.contains("enum class CompactFailureReason"))
        assertTrue(model.contains("val reason: CompactFailureReason"))
        assertTrue(model.contains("val externalDetail: String? = null"))
        assertTrue(controller.contains("CompactFailureReason.SELECT_MODEL"))
        assertTrue(controller.contains("CompactFailureReason.GENERIC"))
        assertFalse(controller.contains("CompactResult.Failed(\""))
        assertTrue(presentation.contains("internal fun compactFailureMessage("))
        assertTrue(presentation.contains("failed.externalDetail?.takeIf(String::isNotBlank)"))
        assertTrue(chat.contains("failureMessage = { result -> compactFailureMessage(appContext, result) }"))
        assertTrue(compactUi.contains("onFailure(failureMessage(result))"))
        assertTrue(generation.contains("compactFailureMessage(appContext, compact)"))
        assertFalse(chat.contains("Context compact failed"))
        assertFalse(generation.contains("Open a conversation first"))
    }

    @Test
    fun `compact chrome is resource backed and every supported locale covers app owned copy`() {
        val item = sourceFile("app/src/main/java/com/newoether/agora/ui/chat/message/MessageItem.kt")
        assertFalse(item.contains("private const val CompactStreamingStatusText"))
        assertFalse(item.contains("private const val CompactErrorText"))
        assertFalse(item.contains("private const val CompactStoppedText"))
        assertTrue(item.contains("R.string.context_compact_streaming"))
        assertTrue(item.contains("R.string.context_compact_error"))
        assertTrue(item.contains("R.string.context_compact_stopped"))

        val keys = setOf(
            "delete_compact_message_title",
            "delete_compact_message_confirm",
            "recompact",
            "context_boundary_active",
            "context_boundary_none",
            "context_compact",
            "context_compact_auto",
            "context_compact_auto_desc",
            "context_compact_model",
            "context_compact_prompt",
            "context_compact_prompt_desc",
            "context_compact_select_model",
            "context_compact_retain",
            "context_compact_retain_desc",
            "context_compact_threshold",
            "context_compact_threshold_desc",
            "context_compact_manual",
            "context_compacting",
            "context_compact_failed",
            "context_compact_select_available_model",
            "context_compact_prompt_empty",
            "context_compact_retain_invalid",
            "context_compact_streaming",
            "context_compact_error",
            "context_compact_stopped",
            "context_compact_setup_unavailable",
            "context_compact_setup_failed",
            "context_compact_not_ready_recompact",
            "context_compact_boundary_disappeared",
            "context_compact_wait_for_generation",
            "context_compact_generation_not_started",
            "context_compact_message_disappeared",
            "context_compact_open_conversation",
        )
        val directories = listOf(
            "values-ar",
            "values-de",
            "values-es",
            "values-fr",
            "values-ja",
            "values-ko",
            "values-pt-rBR",
            "values-ru",
            "values-vi",
            "values-zh",
            "values-zh-rTW",
        )
        val defaults = stringValues(sourceFile("app/src/main/res/values/strings.xml"))
        assertTrue(defaults.keys.containsAll(keys))
        directories.forEach { directory ->
            val localized = stringValues(sourceFile("app/src/main/res/$directory/strings.xml"))
            assertTrue("$directory missing ${keys - localized.keys}", localized.keys.containsAll(keys))
            keys.forEach { key ->
                assertNotEquals("$directory left $key in English", defaults[key], localized[key])
            }
        }
    }

    private fun stringValues(xml: String): Map<String, String> =
        Regex("""<string name="([^"]+)"(?: [^>]*)?>([^<]*)</string>""")
            .findAll(xml)
            .associate { it.groupValues[1] to it.groupValues[2] }

    private fun sourceFile(relativePath: String): String {
        var directory = File(requireNotNull(System.getProperty("user.dir"))).absoluteFile
        repeat(8) {
            val candidate = File(directory, relativePath)
            if (candidate.isFile) {
                return if (candidate.name == "strings.xml") {
                    candidate.readLocaleStringResourceSources()
                } else candidate.readText()
            }
            directory = directory.parentFile ?: error("Reached filesystem root")
        }
        error("Unable to locate $relativePath")
    }
}
