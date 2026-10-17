package com.newoether.agora.remote

/** Native control fixtures include the same body-page metadata returned by Filo. */
internal fun bodyPage(
    messages: List<RemoteMessage>, nextCursor: String?, queued: List<RemoteQueuedMessage>,
    runtime: RemoteRuntime? = null,
) = RemoteConversationPage(messages, nextCursor, queued, runtime, nodes = messages.map { message ->
    RemoteMessageNode(message.id, message.turnId, message.clientId, message.role, message.timestamp,
        java.security.MessageDigest.getInstance("SHA-256").digest(message.toString().toByteArray())
            .joinToString("") { "%02x".format(it) }, message.text.length,
        groupId = message.groupId, nativeId = message.nativeId,
        textOffset = message.textOffset, textContinues = message.textContinues, error = message.error,
        activity = message.activity?.let { RemoteNodeActivity(it.type, it.state, it.durationMs) })
})
