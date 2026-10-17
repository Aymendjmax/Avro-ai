package com.newoether.agora.webui

import com.newoether.agora.data.local.MessageContextTopology
import com.newoether.agora.model.ChatMessage
import com.newoether.agora.model.MessageStatus
import com.newoether.agora.model.Participant

internal fun topology(id: String, parent: String?, participant: Participant, timestamp: Long) =
    MessageContextTopology(
        id = id,
        conversationId = "a",
        parentId = parent,
        status = MessageStatus.SUCCESS,
        participant = participant,
        timestamp = timestamp,
        modelName = null,
        runId = "run-$id",
        runSequence = timestamp,
        consumedAtPass = null,
    )

internal fun message(
    id: String,
    parent: String?,
    participant: Participant,
    text: String,
    status: MessageStatus = MessageStatus.SUCCESS,
) = ChatMessage(
    id = id,
    parentId = parent,
    text = text,
    participant = participant,
    status = status,
    timestamp = 10,
)
