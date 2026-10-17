package com.newoether.agora.viewmodel

import com.newoether.agora.data.local.MessageContextTopology
import com.newoether.agora.data.local.MessageEntity
import com.newoether.agora.model.MessageStatus
import com.newoether.agora.util.Constants

internal data class ApiPathAssemblyRow<T>(
    val row: T,
    val inheritedModelName: String? = null,
    val stripAggregateToolSegments: Boolean = false,
)

/**
 * Builds the API-facing history from the selected message ancestry.
 *
 * Tool protocol rows are stored as side chains below the visible model row, while a queued
 * intervention can itself have a tool/result row in its ancestry. Consequently, blindly walking
 * both the ancestry and every side chain can replay the same tool round twice. This assembler has
 * one ownership rule: every persisted message id may enter the API path exactly once.
 */
internal object ApiPathAssembler {
    fun assemble(
        ancestorPath: List<MessageEntity>,
        allMessages: List<MessageEntity>,
    ): List<MessageEntity> = assembleRows(
        ancestorPath = ancestorPath,
        allMessages = allMessages,
        idOf = MessageEntity::id,
        parentIdOf = MessageEntity::parentId,
        runIdOf = MessageEntity::runId,
        runSequenceOf = MessageEntity::runSequence,
        timestampOf = MessageEntity::timestamp,
        modelNameOf = MessageEntity::modelName,
        statusOf = MessageEntity::status,
    ).map { planned ->
        val entity = planned.row
        InterruptedToolRounds.Row(
            entity = entity.copy(modelName = entity.modelName ?: planned.inheritedModelName),
            stripAggregateToolSegments = planned.stripAggregateToolSegments,
        )
    }.let(InterruptedToolRounds::materialize)

    fun planTopology(
        ancestorPath: List<MessageContextTopology>,
        allMessages: List<MessageContextTopology>,
    ): List<ApiPathAssemblyRow<MessageContextTopology>> = assembleRows(
        ancestorPath = ancestorPath,
        allMessages = allMessages,
        idOf = MessageContextTopology::id,
        parentIdOf = MessageContextTopology::parentId,
        runIdOf = MessageContextTopology::runId,
        runSequenceOf = MessageContextTopology::runSequence,
        timestampOf = MessageContextTopology::timestamp,
        modelNameOf = MessageContextTopology::modelName,
        statusOf = MessageContextTopology::status,
    )

    private fun <T> assembleRows(
        ancestorPath: List<T>,
        allMessages: List<T>,
        idOf: (T) -> String,
        parentIdOf: (T) -> String?,
        runIdOf: (T) -> String,
        runSequenceOf: (T) -> Long,
        timestampOf: (T) -> Long,
        modelNameOf: (T) -> String?,
        statusOf: (T) -> MessageStatus,
    ): List<ApiPathAssemblyRow<T>> {
        if (ancestorPath.isEmpty()) return emptyList()

        val protocolChildren = allMessages
            .asSequence()
            .filter { isToolProtocolId(idOf(it)) }
            .groupBy(parentIdOf)
        val emittedIds = mutableSetOf<String>()
        val result = mutableListOf<ApiPathAssemblyRow<T>>()
        val messageOrder = compareBy<T> { runSequenceOf(it) }
            .thenBy { timestampOf(it) }
            .thenBy { idOf(it) }

        fun emitProtocolSubtree(
            root: T,
            runId: String,
            sourceModelName: String?,
        ) {
            val rootId = idOf(root)
            if (runIdOf(root) != runId || !emittedIds.add(rootId)) return
            result += ApiPathAssemblyRow(
                row = root,
                inheritedModelName = sourceModelName.takeIf { modelNameOf(root) == null },
            )
            protocolChildren[rootId]
                .orEmpty()
                .asSequence()
                .filter { runIdOf(it) == runId }
                .sortedWith(messageOrder)
                .forEach { emitProtocolSubtree(it, runId, sourceModelName) }
        }

        for (row in ancestorPath) {
            val rowId = idOf(row)
            if (isToolProtocolId(rowId)) {
                if (emittedIds.add(rowId)) result += ApiPathAssemblyRow(row)
                continue
            }

            val rowRunId = runIdOf(row)
            val toolRoots = protocolChildren[rowId]
                .orEmpty()
                .asSequence()
                .filter {
                    runIdOf(it) == rowRunId &&
                        idOf(it).startsWith(Constants.TOOL_MSG_PREFIX)
                }
                .sortedWith(messageOrder)
                .toList()
            toolRoots.forEach { emitProtocolSubtree(it, rowRunId, modelNameOf(row)) }

            if (emittedIds.add(rowId)) {
                // The visible model row aggregates every tool round for UI rendering. During the
                // live tool loop that row is still the in-progress output placeholder; replaying
                // it after the just-persisted result would make the next request end in assistant
                // output instead of tool input. Terminal rows, however, contain the completed
                // answer after those tool rounds and remain part of later historical requests.
                val omitAggregate =
                    toolRoots.isNotEmpty() && statusOf(row).isGenerationInProgress()
                if (!omitAggregate) {
                    result += ApiPathAssemblyRow(
                        row = row,
                        stripAggregateToolSegments = toolRoots.isNotEmpty(),
                    )
                }
            }
        }
        return result
    }

    private fun isToolProtocolId(id: String): Boolean =
        id.startsWith(Constants.TOOL_MSG_PREFIX) ||
            id.startsWith(Constants.RESULT_MSG_PREFIX)

    private fun MessageStatus.isGenerationInProgress(): Boolean = when (this) {
        MessageStatus.TRANSCRIBING,
        MessageStatus.SENDING,
        MessageStatus.THINKING,
        MessageStatus.TOOL_CALLING -> true
        MessageStatus.SUCCESS,
        MessageStatus.STOPPED,
        MessageStatus.ERROR -> false
    }
}
