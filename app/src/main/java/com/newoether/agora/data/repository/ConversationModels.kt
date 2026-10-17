package com.newoether.agora.data.repository

/** Model mutations never replace unrelated conversation fields or create missing owners. */
suspend fun ConversationRepository.updateConversationModel(conversationId: String, modelId: String?): Boolean =
    chatDao.updateConversationModel(conversationId, modelId, System.currentTimeMillis()) == 1

suspend fun ConversationRepository.replaceConfiguredModelReferences(oldModelId: String, newModelId: String?) =
    chatDao.replaceConfiguredModelReferences(oldModelId, newModelId)

suspend fun ConversationRepository.renameConfiguredProviderModelReferences(oldProvider: String, newProvider: String) =
    chatDao.renameConfiguredProviderModelReferences(oldProvider, newProvider)
