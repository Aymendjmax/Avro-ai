package com.newoether.agora.viewmodel

import com.newoether.agora.data.repository.SettingsRepository
import com.newoether.agora.util.Constants
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/** Projects configured local chat models into provider model ids and aliases. */
internal class LocalModelCatalogSynchronizer(
    private val settings: SettingsRepository,
    private val scope: CoroutineScope,
) {
    fun start() {
        scope.launch {
            var lastLocalIds: List<String>? = null
            var lastLocalAliases: Map<String, String>? = null
            settings.localChatModels.collect { models ->
                val localIds = models.map { "Local:${it.modelId}" }
                val localAliases = models.associate { "Local:${it.modelId}" to it.alias }
                if (localIds != lastLocalIds) {
                    settings.saveAvailableModels(Constants.PROVIDER_LOCAL, localIds)
                    lastLocalIds = localIds
                }
                if (localAliases != lastLocalAliases) {
                    settings.synchronizeLocalModelAliases(localAliases)
                    lastLocalAliases = localAliases
                }
            }
        }
    }
}
