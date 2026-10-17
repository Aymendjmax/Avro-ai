package com.newoether.agora.data

import java.util.Locale

/** The complete persisted prompt transformation applied during app initialization. */
internal fun migrateSystemPromptsOnStartup(
    prompts: List<SystemPromptEntry>,
    locale: Locale,
): List<SystemPromptEntry> {
    val defaultMigrated = migrateUnmodifiedBuiltInDefault(prompts, locale)
    val titleMigrated = migrateLegacyDefaultPromptTitle(defaultMigrated, locale)
    return migrateLegacyMessageTemplates(titleMigrated)
}

private fun migrateLegacyMessageTemplates(
    prompts: List<SystemPromptEntry>,
): List<SystemPromptEntry> = prompts.map { entry ->
    val normalizedUserItems = entry.resolvedUserItems
    val normalizedAssistantItems = entry.resolvedAssistantItems
    if (
        entry.userItems != normalizedUserItems ||
        entry.assistantItems != normalizedAssistantItems ||
        entry.userPrependItems.isNotEmpty() ||
        entry.userPostpendItems.isNotEmpty()
    ) {
        entry.copy(
            userItems = normalizedUserItems,
            assistantItems = normalizedAssistantItems,
            userPrependItems = emptyList(),
            userPostpendItems = emptyList(),
        )
    } else {
        entry
    }
}

private fun migrateLegacyDefaultPromptTitle(
    prompts: List<SystemPromptEntry>,
    locale: Locale
): List<SystemPromptEntry> {
    if (prompts.isEmpty()) return prompts
    val localizedTitle = DefaultSystemPrompt.titleForLocale(locale)
    val defaultPrompt = DefaultSystemPrompt.create(locale)
    return prompts.map { entry ->
        val legacyLowercaseEnglish = entry.title == "default"
        val legacySimplifiedTitleInTraditionalLocale =
            entry.title == "\u9ed8\u8ba4" && localizedTitle == "\u9810\u8a2d"
        if ((legacyLowercaseEnglish || legacySimplifiedTitleInTraditionalLocale) &&
            entry.sameTemplateAs(defaultPrompt)
        ) {
            entry.copy(title = localizedTitle)
        } else {
            entry
        }
    }
}

private fun SystemPromptEntry.sameTemplateAs(other: SystemPromptEntry): Boolean =
    resolvedSystemItems.sameTemplateItems(other.resolvedSystemItems) &&
        resolvedUserItems.sameTemplateItems(other.resolvedUserItems) &&
        resolvedAssistantItems.sameTemplateItems(other.resolvedAssistantItems)

private fun List<PromptTemplateItem>.sameTemplateItems(other: List<PromptTemplateItem>): Boolean =
    size == other.size && zip(other).all { (left, right) ->
        left.type == right.type && left.value == right.value
    }
