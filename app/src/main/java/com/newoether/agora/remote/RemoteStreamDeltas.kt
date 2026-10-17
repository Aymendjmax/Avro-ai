package com.newoether.agora.remote

import com.newoether.agora.model.StreamingTextDelta

/** Snapshot transport supplies real appended-text boundaries to the original glyph fade owner. */
internal class RemoteStreamDeltas {
    private var sequence = 0L

    fun apply(previous: List<RemoteMessage>, fresh: List<RemoteMessage>,
        previousRuntime: RemoteRuntime?, runtime: RemoteRuntime?): List<RemoteMessage> {
        val old = previous.associateBy { it.id }
        val liveTurns = listOfNotNull(previousRuntime?.activeTurnId, runtime?.activeTurnId).toSet()
        return fresh.map { message ->
            val before = old[message.id]
            val text = message.displayText()
            val oldText = before?.displayText().orEmpty()
            val preserved = before?.streamingTextDeltas.orEmpty()
            val deltas = if (previousRuntime != null && message.role == "assistant" && message.activity == null &&
                message.turnId in liveTurns && text.startsWith(oldText) && text.length > oldText.length) {
                preserved + StreamingTextDelta(++sequence, text.codePointCount(oldText.length, text.length))
            } else if (text == oldText || text.startsWith(oldText)) preserved else emptyList()
            message.copy(streamingTextDeltas = deltas)
        }
    }
}
