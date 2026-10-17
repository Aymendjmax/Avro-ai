package com.newoether.agora.viewmodel

import com.newoether.agora.R
import org.junit.Assert.assertEquals
import org.junit.Test

class CompactFailurePresentationTest {
    @Test
    fun `every semantic compact failure maps to its owned resource`() {
        val expected = mapOf(
            CompactFailureReason.SELECT_MODEL to R.string.context_compact_select_available_model,
            CompactFailureReason.EMPTY_PROMPT to R.string.context_compact_prompt_empty,
            CompactFailureReason.INVALID_RETAIN_COUNT to R.string.context_compact_retain_invalid,
            CompactFailureReason.SETUP_UNAVAILABLE to R.string.context_compact_setup_unavailable,
            CompactFailureReason.SETUP_FAILED to R.string.context_compact_setup_failed,
            CompactFailureReason.NOT_READY_TO_RECOMPACT to R.string.context_compact_not_ready_recompact,
            CompactFailureReason.BOUNDARY_DISAPPEARED to R.string.context_compact_boundary_disappeared,
            CompactFailureReason.GENERATION_BUSY to R.string.context_compact_wait_for_generation,
            CompactFailureReason.GENERATION_NOT_STARTED to R.string.context_compact_generation_not_started,
            CompactFailureReason.MESSAGE_DISAPPEARED to R.string.context_compact_message_disappeared,
            CompactFailureReason.OPEN_CONVERSATION to R.string.context_compact_open_conversation,
            CompactFailureReason.GENERIC to R.string.context_compact_failed,
        )

        assertEquals(CompactFailureReason.entries.toSet(), expected.keys)
        expected.forEach { (reason, resourceId) ->
            assertEquals(resourceId, reason.stringResourceId())
        }
    }
}
