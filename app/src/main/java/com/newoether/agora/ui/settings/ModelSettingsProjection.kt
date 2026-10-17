package com.newoether.agora.ui.settings

import com.newoether.agora.data.inferModelAlias
import com.newoether.agora.model.ModelId
import com.newoether.agora.model.apiModelName

internal data class ModelProviderGroup(
    val providerName: String,
    val models: List<String>,
)

internal fun customModelGroups(
    customModels: Set<String>,
    providerOrder: List<String>,
): List<ModelProviderGroup> {
    val providerPositions = providerOrder.withIndex().associate { (index, name) -> name to index }
    return customModels
        .groupBy { ModelId.parse(it).providerName }
        .map { (providerName, models) ->
            ModelProviderGroup(
                providerName = providerName,
                models = models.sortedBy { ModelId.parse(it).apiModelName.lowercase() },
            )
        }
        .sortedWith(
            compareBy<ModelProviderGroup>(
                { providerPositions[it.providerName] ?: Int.MAX_VALUE },
                { it.providerName.lowercase() },
            )
        )
}

internal fun fetchedModelGroups(
    availableModels: Map<String, List<String>>,
    customModels: Set<String>,
    modelAliases: Map<String, String>,
    query: String,
): List<ModelProviderGroup> {
    val normalizedQuery = query.trim()
    return availableModels.mapNotNull { (providerName, models) ->
        val providerMatches =
            normalizedQuery.isNotEmpty() &&
                providerName.contains(normalizedQuery, ignoreCase = true)
        val filteredModels = models
            .asSequence()
            .filterNot { it in customModels }
            .distinct()
            .filter { model ->
                val apiModelName = ModelId.parse(model).apiModelName
                normalizedQuery.isEmpty() ||
                    providerMatches ||
                    model.contains(normalizedQuery, ignoreCase = true) ||
                    apiModelName.contains(normalizedQuery, ignoreCase = true) ||
                    modelAliases[model]?.contains(normalizedQuery, ignoreCase = true) == true ||
                    inferModelAlias(apiModelName).contains(normalizedQuery, ignoreCase = true)
            }
            .toList()
        filteredModels.takeIf { it.isNotEmpty() }?.let {
            ModelProviderGroup(providerName = providerName, models = it)
        }
    }
}

internal fun modelAliasToPersist(
    rawAlias: String,
    initialDisplayAlias: String,
    editedAlias: String,
): String = if (editedAlias.trim() == initialDisplayAlias.trim()) {
    rawAlias.trim()
} else {
    editedAlias.trim()
}

