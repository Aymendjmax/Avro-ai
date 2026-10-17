package com.newoether.agora.data

import java.util.Locale
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Test

class SystemPromptMigrationTest {
    @Test
    fun startupPreservesCustomizedTemplatesContainingLegacyRuntimeTags() {
        val original = DefaultSystemPrompt.create(Locale.ENGLISH).copy(
            id = "user-prompt",
            systemItems = listOf(text("<agora_runtime_context>old</agora_runtime_context>"), text("My rules")),
            userItems = listOf(text("My prefix"), PredefinedVariables.promptItem()),
            assistantItems = listOf(PredefinedVariables.promptItem(), text("My suffix")),
        )
        assertEquals(original, coldStart(original))
        assertEquals(original, coldStart(coldStart(original)))
    }

    @Test
    fun startupPreservesLegacyContentAndWrappersWhileNormalizingTheirStorage() {
        val original = SystemPromptEntry(
            id = "legacy-custom",
            title = "My prompt",
            content = "<agora_runtime_context>Custom runtime instructions</agora_runtime_context>",
            userPrependItems = listOf(text("User prefix")),
            userPostpendItems = listOf(text("User suffix")),
        )
        val migrated = coldStart(original)
        assertEquals(original.id, migrated.id)
        assertEquals(original.title, migrated.title)
        assertEquals(original.content, migrated.content)
        assertEquals(original.resolvedSystemItems.map { it.type to it.value },
            migrated.resolvedSystemItems.map { it.type to it.value })
        assertEquals(original.resolvedUserItems.map { it.type to it.value },
            migrated.resolvedUserItems.map { it.type to it.value })
        assertEquals(original.resolvedAssistantItems.map { it.type to it.value },
            migrated.resolvedAssistantItems.map { it.type to it.value })
        assertEquals(migrated, coldStart(migrated))
    }

    @Test
    fun startupStillUpgradesOnlyTheKnownUnmodifiedBuiltInDefault() {
        val previous = DefaultSystemPrompt.previousVersionForMigration(Locale.ENGLISH)
        val customized = previous.copy(systemItems = previous.systemItems + text("My rules"))
        val migrated = coldStart(previous)
        val current = DefaultSystemPrompt.create(Locale.ENGLISH)
        assertEquals(previous.id, migrated.id)
        assertEquals(current.resolvedSystemItems.map { it.type to it.value },
            migrated.resolvedSystemItems.map { it.type to it.value })
        assertEquals(customized.resolvedSystemItems, coldStart(customized).resolvedSystemItems)
        assertEquals(migrated, coldStart(migrated))
    }

    private fun coldStart(entry: SystemPromptEntry): SystemPromptEntry {
        val persisted = Json.encodeToString(listOf(entry))
        return migrateSystemPromptsOnStartup(
            Json.decodeFromString<List<SystemPromptEntry>>(persisted), Locale.ENGLISH,
        ).single()
    }

    private fun text(value: String) = PromptTemplateItem(type = PromptItemType.CUSTOM, value = value)
}
