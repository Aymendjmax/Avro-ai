package com.newoether.agora.viewmodel

import android.content.Context
import androidx.annotation.StringRes
import com.newoether.agora.R

@StringRes
internal fun CompactFailureReason.stringResourceId(): Int = when (this) {
    CompactFailureReason.SELECT_MODEL -> R.string.context_compact_select_available_model
    CompactFailureReason.EMPTY_PROMPT -> R.string.context_compact_prompt_empty
    CompactFailureReason.INVALID_RETAIN_COUNT -> R.string.context_compact_retain_invalid
    CompactFailureReason.SETUP_UNAVAILABLE -> R.string.context_compact_setup_unavailable
    CompactFailureReason.SETUP_FAILED -> R.string.context_compact_setup_failed
    CompactFailureReason.NOT_READY_TO_RECOMPACT -> R.string.context_compact_not_ready_recompact
    CompactFailureReason.BOUNDARY_DISAPPEARED -> R.string.context_compact_boundary_disappeared
    CompactFailureReason.GENERATION_BUSY -> R.string.context_compact_wait_for_generation
    CompactFailureReason.GENERATION_NOT_STARTED -> R.string.context_compact_generation_not_started
    CompactFailureReason.MESSAGE_DISAPPEARED -> R.string.context_compact_message_disappeared
    CompactFailureReason.OPEN_CONVERSATION -> R.string.context_compact_open_conversation
    CompactFailureReason.GENERIC -> R.string.context_compact_failed
}

internal fun compactFailureMessage(
    context: Context,
    failed: CompactResult.Failed,
): String = failed.externalDetail?.takeIf(String::isNotBlank)
    ?: context.getString(failed.reason.stringResourceId())
