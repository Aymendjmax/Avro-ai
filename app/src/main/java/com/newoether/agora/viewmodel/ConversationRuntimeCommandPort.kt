package com.newoether.agora.viewmodel

import com.newoether.agora.model.ConversationCommand
import com.newoether.agora.model.ProviderPassResult
import com.newoether.agora.model.RunEffect
import com.newoether.agora.model.RunEffectIdentity
import com.newoether.agora.model.RunEndReason
import com.newoether.agora.model.RunStatus
import com.newoether.agora.model.Transition
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/**
 * Typed application port for commands that do not manipulate process resources directly.
 *
 * The identity suppliers are evaluated only inside a mailbox command factory, where the runtime
 * host already holds its generation lock. This port cannot reduce/apply state, cancel resources,
 * release a slot, or authorize a next lifecycle stage outside the effects returned by the mailbox.
 */
internal class ConversationRuntimeCommandPort(
    private val conversationId: String,
    private val mailbox: ConversationCommandMailbox,
    private val nextOwnerToken: () -> Long,
) {
    /** Submit one ordinary foreground/headless Send placement decision. */
    suspend fun requestSend(
        proposedRunId: String,
        effectId: String,
        directOnly: Boolean,
        hasPendingGuidance: Boolean,
    ): Transition {
        require(proposedRunId.isNotBlank())
        require(effectId.isNotBlank())
        return mailbox.submit(
            commandFactory = ConversationCommandFactory {
                ConversationCommand.SendRequested(
                    identity = RunEffectIdentity(
                        conversationId = conversationId,
                        ownerToken = nextOwnerToken(),
                        runId = proposedRunId,
                        pass = 0,
                        effectId = effectId,
                    ),
                    directOnly = directOnly,
                    hasPendingGuidance = hasPendingGuidance,
                )
            },
            cancellationCommand = { transition ->
                transition.effects
                    .filterIsInstance<RunEffect.PersistAcceptedInput>()
                    .singleOrNull()
                    ?.let { effect -> ConversationCommand.SendLaunchAbandoned(effect.identity) }
            },
        )
    }

    /** Echo the exact Room acceptance effect back through the mailbox. */
    suspend fun finishInputPersistence(
        identity: RunEffectIdentity,
    ): Transition = mailbox.submit(
        ConversationCommandFactory { ConversationCommand.InputPersisted(identity) },
    )

    suspend fun inputPersistenceFailed(identity: RunEffectIdentity): Boolean = mailbox.submit(
        ConversationCommandFactory { ConversationCommand.InputPersistenceFailed(identity) },
    ).accepted

    suspend fun abandonSendLaunch(identity: RunEffectIdentity): Boolean = mailbox.submit(
        ConversationCommandFactory { ConversationCommand.SendLaunchAbandoned(identity) },
    ).accepted

    /** Authorize one exact validated Provider tool batch. */
    suspend fun requestToolBatch(
        providerOutcomeIdentity: RunEffectIdentity,
    ): RunEffect.ExecuteToolBatch? = withContext(NonCancellable) {
        mailbox.submit(
            ConversationCommandFactory {
                ConversationCommand.ToolBatchRequested(providerOutcomeIdentity)
            },
        ).effects.filterIsInstance<RunEffect.ExecuteToolBatch>().singleOrNull()
    }

    suspend fun completeToolBatch(
        batchIdentity: RunEffectIdentity,
    ): RunEffect.CommitToolRound? = withContext(NonCancellable) {
        mailbox.submit(
            ConversationCommandFactory {
                ConversationCommand.ToolBatchCompleted(batchIdentity)
            },
        ).effects.filterIsInstance<RunEffect.CommitToolRound>().singleOrNull()
    }

    suspend fun finishToolRoundCommit(
        commitIdentity: RunEffectIdentity,
        success: Boolean,
    ): RunEffect? = withContext(NonCancellable) {
        mailbox.submit(
            ConversationCommandFactory {
                ConversationCommand.ToolRoundCommitted(commitIdentity, success)
            },
        ).effects.singleOrNull()
    }

    /** Authorize exactly one Provider pass for the current Run/pass. */
    suspend fun requestProviderPass(
        identity: RunEffectIdentity,
    ): RunEffect.StartProviderPass? = mailbox.submit(
        commandFactory = ConversationCommandFactory {
            ConversationCommand.ProviderPassRequested(identity)
        },
        cancellationCommand = { transition ->
            transition.effects.filterIsInstance<RunEffect.StartProviderPass>()
                .singleOrNull()
                ?.let { effect ->
                    ConversationCommand.ProviderPassCompleted(
                        effect.identity,
                        ProviderPassResult.CANCELLED,
                    )
                }
        },
    ).effects.filterIsInstance<RunEffect.StartProviderPass>().singleOrNull()

    suspend fun finishProviderPass(
        identity: RunEffectIdentity,
        result: ProviderPassResult,
    ): RunEffect.ProviderPassAccepted? = withContext(NonCancellable) {
        mailbox.submit(
            ConversationCommandFactory {
                ConversationCommand.ProviderPassCompleted(identity, result)
            },
        ).effects.filterIsInstance<RunEffect.ProviderPassAccepted>().singleOrNull()
    }

    suspend fun requestRunFinalization(
        identity: RunEffectIdentity,
        status: RunStatus,
        reason: RunEndReason,
        markConversationUnread: Boolean,
    ): RunEffect.FinalizeRun? = withContext(NonCancellable) {
        mailbox.submit(
            ConversationCommandFactory {
                ConversationCommand.FinalizationRequested(
                    identity = identity,
                    status = status,
                    reason = reason,
                    markConversationUnread = markConversationUnread,
                )
            },
        ).effects.filterIsInstance<RunEffect.FinalizeRun>().singleOrNull()
    }

    suspend fun finishRunFinalization(
        identity: RunEffectIdentity,
        success: Boolean,
    ): Transition = withContext(NonCancellable) {
        mailbox.submit(
            ConversationCommandFactory {
                ConversationCommand.FinalizationCompleted(identity, success)
            },
        )
    }


}
