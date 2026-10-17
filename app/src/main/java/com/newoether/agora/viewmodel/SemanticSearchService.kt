package com.newoether.agora.viewmodel

import com.newoether.agora.data.EmbeddingModelConfig
import com.newoether.agora.data.local.MessageEntity
import com.newoether.agora.data.repository.SettingsRepository

/** Builds one immutable search context and delegates the actual semantic search. */
internal class SemanticSearchService(
    private val settings: SettingsRepository,
    private val activeEmbeddingConfig: () -> EmbeddingModelConfig?,
    private val resolveEmbeddingApiKey: () -> String?,
    private val search: suspend (
        query: String,
        limit: Int,
        context: GenerationContext,
    ) -> List<Pair<MessageEntity, Float>>,
) {
    suspend fun search(query: String, limit: Int): List<Pair<MessageEntity, Float>> {
        val context = GenerationContext(
            accessSavedMemories = settings.accessSavedMemories.value,
            accessActiveMemory = settings.accessActiveMemory.value,
            skillReadAccess = false,
            skillModifyAccess = false,
            accessPastConversations = settings.accessPastConversations.value,
            modelSearchMethod = settings.modelSearchMethod.value,
            activeEmbeddingConfig = activeEmbeddingConfig(),
            embeddingApiKey = resolveEmbeddingApiKey().orEmpty(),
            ragThreshold = settings.ragThreshold.value,
            searchMatchLimit = settings.searchMatchLimit.value,
            searchContextWindow = settings.searchContextWindow.value,
            webSearchEnabled = settings.webSearchEnabled.value,
            webSearchApiKeys = settings.webSearchApiKeys.value,
            webSearchProvider = settings.webSearchProvider.value,
            webSearchNumResults = settings.webSearchNumResults.value,
            webSearchBaseUrl = settings.webSearchBaseUrl.value,
        )
        return search(query, limit, context)
    }
}
