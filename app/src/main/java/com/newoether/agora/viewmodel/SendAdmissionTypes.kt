package com.newoether.agora.viewmodel

import com.newoether.agora.model.MessageStatus
import com.newoether.agora.model.RunEffect

internal sealed interface SendPlacement {
    data class Direct(
        val uiToken: Long,
        val runId: String,
        val inputEffect: RunEffect.PersistAcceptedInput,
    ) : SendPlacement
    data class Queued(val messageId: String) : SendPlacement
    data class QueuedAndDrain(
        val messageId: String,
        val claim: QueuedDrainClaim,
    ) : SendPlacement
    data object RetryAfterRelease : SendPlacement

    /**
     * The slot was busy and the caller asked for a direct-only send, so NOTHING was persisted.
     *
     * Distinct from [RetryAfterRelease], which waits for the slot to free up. A direct-only caller
     * must never wait: an automation caller already holds the conversation lock that the current
     * slot owner may be blocked on, so waiting there deadlocks the whole conversation.
     */
    data object Rejected : SendPlacement
}

/**
 * Result of delegating one automation (Loop) cycle to the foreground send path.
 *
 * [SlotBusy] means nothing was persisted and nothing generated, so the caller reports a typed busy
 * cycle outcome. It must not fall back to a second headless writer. It is never a partial success:
 * a direct-only send either owns the slot for the whole turn or does not run at all.
 */
internal sealed interface AutomationSendOutcome {
    data object SlotBusy : AutomationSendOutcome

    /** [modelMessageId] is the row this very send created, not a re-derived conversation tail. */
    data class Delivered(val modelMessageId: String) : AutomationSendOutcome
}

/** Only durable Compact success may release any automatic queue or loop handoff. */
internal fun automaticCompactAllowsHandoff(status: MessageStatus?): Boolean =
    status == MessageStatus.SUCCESS

/** Dictates whether a send scrolls unconditionally or only while the user is at the bottom. */
internal enum class SendScrollPolicy {
    /** Always request absolute-bottom scroll (manual send, queue drain). */
    FORCE,
    /** Request absolute-bottom scroll only when the viewport is already at the bottom (loop cycle). */
    ATTACHED_ONLY,
}
