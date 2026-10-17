package com.newoether.agora.viewmodel

import com.newoether.agora.model.ConversationCommand
import com.newoether.agora.model.RunEffectIdentity
import com.newoether.agora.model.RunEndReason
import com.newoether.agora.model.RunStatus
import org.junit.Assert.assertEquals

internal suspend fun finalizeBoundRun(
    state: ConversationGenerationState,
    ownerToken: Long,
    runId: String,
    pass: Int = 0,
): Boolean {
    val identity = RunEffectIdentity(
        conversationId = "conversation",
        ownerToken = ownerToken,
        runId = runId,
        pass = pass,
        effectId = "finalize-$runId-$pass",
    )
    val effect = state.commands.requestRunFinalization(
        identity,
        RunStatus.COMPLETED,
        RunEndReason.MODEL_COMPLETED,
        markConversationUnread = true,
    )
    assertEquals(identity, effect?.identity)
    assertEquals(
        ConversationGenerationState.RunFinalizationOutcome.RECORDED,
        state.finishRunFinalization(identity, success = true),
    )
    return state.endGeneration(ownerToken)
}

internal fun ConversationGenerationState.StopResult.completion(
    success: Boolean,
): ConversationCommand.PersistenceSettled = ConversationCommand.PersistenceSettled(
    identity = requireNotNull(finalizationEffect).identity,
    success = success,
)
